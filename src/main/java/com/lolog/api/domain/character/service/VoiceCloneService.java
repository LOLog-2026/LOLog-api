package com.lolog.api.domain.character.service;

import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.util.SafeLog;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 내 목소리(characters.md "내 목소리 (FR-CHAR-05)"). 상태와 파일 키는 user_profiles.voice_clone_*에 있다.
// 녹음 → PROCESSING + VOICE_CLONE 작업(lolog:video, kind "voice_clone") → 워커가 미리 듣기를 보내면 READY, 실패하면 FAILED.
// 원본 녹음은 READY인 동안 계속 보관하고(필수 동의), 워커만 받아 간다. 쇼츠를 만들 때 참조 음성으로 쓴다.
// 다시 녹음하면 원본을 바꾸고 미리 듣기는 새로 만든다. 실패하면 쓸 수 있는 목소리가 없으니 녹음·미리 듣기를 지운다(초안).
@Service
public class VoiceCloneService {

	private static final Logger log = LoggerFactory.getLogger(VoiceCloneService.class);

	// 문장 하나 5~15초면 wav도 2MB 안팎이다. Nginx 한도(5MB) 안으로 둔다(초안).
	public static final long MAX_AUDIO_BYTES = 4L * 1024 * 1024;
	static final int MAX_FAIL_REASON = 200;
	// 녹음 문장(characters.md "내 목소리" 녹음, 정함 11-14). 문장이 하나라 앱은 보내지 않고, 받을 때 이 글자를 띄어쓰기·문장부호까지
	// 그대로 voice_clone_text에 저장한다. 워커가 큐 text·쇼츠 voiceText로 받아 ref_text로 쓴다.
	// ai 레포 src/lolog_ai/worker/voice_clone.py의 RECORD_SENTENCE, 앱 VOICE_CLONE_SENTENCE와 같아야 한다. 바꾸면 셋 다 바꾼다.
	// 문장을 바꿔도 예전 녹음은 그때 저장한 글자로 계속 읽힌다.
	public static final String RECORD_SENTENCE = "오늘 친구랑 카페에 갔는데, 주문을 잘못해서 둘이 한참 웃었어요. 그래도 덕분에 즐거운 하루였어요.";
	// 최장 녹음 시간. 이 안에 시작해 마지막 묶음이 안 온 녹음이 있으면 녹음 중으로 본다(초안).
	static final int MAX_RECORDING_HOURS = 3;

	private final UserProfileRepository profiles;
	private final RecordingRepository recordings;
	private final ImageStorage storage;
	private final JobRepository jobs;
	private final VoiceClonePublisher publisher;
	private final PushService push;
	private final CharacterViews views;

	public VoiceCloneService(UserProfileRepository profiles, RecordingRepository recordings, ImageStorage storage,
			JobRepository jobs, VoiceClonePublisher publisher, PushService push, CharacterViews views) {
		this.profiles = profiles;
		this.recordings = recordings;
		this.storage = storage;
		this.jobs = jobs;
		this.publisher = publisher;
		this.push = push;
		this.views = views;
	}

	// ProfileCharacterResponse.voiceClone. 만든 적이 없거나 지웠으면 null.
	public record VoiceCloneView(String status, String previewUrl, String failReason, boolean shareWithFriends) {
	}

	// 바꾸기 전에 읽어 둔 내 목소리 상태와 파일 키.
	private record State(String status, String sampleKey, String previewKey) {

		// 프로필에서 읽는다.
		static State of(UserProfile p) {
			return new State(p.getVoiceCloneStatus(), p.getVoiceCloneKey(), p.getVoiceClonePreviewKey());
		}

	}

	// 녹음 파일 확장자와 Content-Type.
	private record Audio(String ext, String contentType) {
	}

	// 녹음 올리기·다시 올리기(PUT .../voice-clone). 앱에서 바로 녹음한 wav·m4a 하나와 필수 동의(consent=true).
	@Transactional
	public ProfileCharacterService.ProfileCharacterResponse upload(long userId, byte[] audio, Boolean consent) {
		if (!publisher.enabled()) {
			throw new ApiException(ErrorCode.VOICE_CLONE_UNAVAILABLE);
		}
		if (!Boolean.TRUE.equals(consent)) {
			throw ApiException.invalid("consent");
		}
		if (audio == null || audio.length == 0) {
			throw ApiException.invalid("audio");
		}
		if (audio.length > MAX_AUDIO_BYTES) {
			throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
		}
		Audio type = audioType(audio);
		if (type == null) {
			throw ApiException.invalid("audio");
		}
		if (recording(userId)) {
			throw new ApiException(ErrorCode.RECORDING_IN_PROGRESS);
		}
		// 목소리는 프로필 캐릭터가 있을 때만 고를 수 있어서 만들기도 같다(FR-CHAR-04).
		UserProfile profile = profiles.findByIdForUpdate(userId)
			.filter(p -> p.getCharacterKey() != null)
			.orElseThrow(() -> new ApiException(ErrorCode.CHARACTER_NOT_FOUND));
		State state = State.of(profile);
		if (UserProfile.CHARACTER_PROCESSING.equals(state.status())) {
			throw new ApiException(ErrorCode.VOICE_CLONE_IN_PROGRESS);
		}
		String key = ImageStorage.voiceCloneSampleKey(userId, type.ext());
		storage.put(key, audio, type.contentType());
		profile.startVoiceClone(key, RECORD_SENTENCE);
		long jobId = jobs.save(Job.voiceClone(userId)).getId();
		afterCommit(() -> {
			// 확장자가 바뀌었으면 예전 원본, 그리고 예전 미리 듣기를 지운다.
			if (state.sampleKey() != null && !state.sampleKey().equals(key)) {
				deleteQuietly(state.sampleKey());
			}
			if (state.previewKey() != null) {
				deleteQuietly(state.previewKey());
			}
			publisher.publishQuietly(jobId);
		});
		return views.profileResponse(userId);
	}

	// 내 목소리 지우기. 없어도 204. 골랐던 목소리가 "self"면 null로 바꾼다. 프로필 캐릭터를 지울 때도 부른다.
	@Transactional
	public void delete(long userId) {
		UserProfile profile = profiles.findByIdForUpdate(userId).orElse(null);
		if (profile == null || profile.getVoiceCloneStatus() == null) {
			return;
		}
		State state = State.of(profile);
		profile.removeVoiceClone();
		// 만드는 중이던 작업은 실패로 둔다. 워커가 나중에 녹음을 찾으면 404라 그 메시지를 버린다.
		jobs.failQueued(userId, "VOICE_CLONE", "내 목소리를 지움");
		afterCommit(() -> {
			if (state.sampleKey() != null) {
				deleteQuietly(state.sampleKey());
			}
			if (state.previewKey() != null) {
				deleteQuietly(state.previewKey());
			}
		});
	}

	// 친구 쇼츠 동의(③)만 바꾼다. 녹음은 그대로. 만든 적이 없으면 404 VOICE_CLONE_NOT_FOUND(초안).
	@Transactional
	public void share(long userId, Boolean shareWithFriends) {
		if (shareWithFriends == null) {
			throw ApiException.invalid("shareWithFriends");
		}
		// 지우기와 겹쳐도 지운 뒤에 동의가 남지 않게 행을 잠그고 본다.
		profiles.findByIdForUpdate(userId)
			.filter(p -> p.getVoiceCloneStatus() != null)
			.orElseThrow(VoiceCloneService::notFound)
			.shareVoiceClone(shareWithFriends);
	}

	// 워커가 원본 녹음을 받는다. 만드는 중이거나 만든 목소리가 있을 때만(쇼츠의 참조 음성으로도 쓴다).
	@Transactional(readOnly = true)
	public ImageStorage.StoredObject sample(long userId) {
		String key = profiles.findById(userId)
			.filter(p -> UserProfile.CHARACTER_PROCESSING.equals(p.getVoiceCloneStatus())
					|| UserProfile.CHARACTER_READY.equals(p.getVoiceCloneStatus()))
			.map(UserProfile::getVoiceCloneKey)
			.orElseThrow(VoiceCloneService::notFound);
		try {
			return storage.getObject(key);
		}
		catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
			throw notFound();
		}
	}

	// 워커가 미리 듣기(wav)를 보낸다. READY로 바꾸고 본인에게 알린다. 만드는 중이 아니면 404(지웠거나 이미 끝남).
	@Transactional
	public void preview(long userId, byte[] wav) {
		if (wav == null || !isWav(wav)) {
			throw ApiException.invalid("file");
		}
		if (wav.length > MAX_AUDIO_BYTES) {
			throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
		}
		UserProfile profile = profiles.findByIdForUpdate(userId)
			.filter(p -> UserProfile.CHARACTER_PROCESSING.equals(p.getVoiceCloneStatus()))
			.orElseThrow(VoiceCloneService::notFound);
		String key = ImageStorage.voiceClonePreviewKey(userId);
		storage.put(key, wav, "audio/wav");
		profile.voiceCloneReady(key);
		jobs.completeQueued(userId, "VOICE_CLONE");
		push.notify(userId, PushService.voiceCloneReady());
	}

	// voice 작업 fail(POST /internal/jobs/{id}/fail). FAILED와 이유를 남기고, 쓸 수 없는 녹음은 지우고 알린다.
	// 이미 결과나 fail을 받은 작업이면 무시한다.
	@Transactional
	public void fail(long jobId, String reason) {
		Job job = jobs.findByIdForUpdate(jobId).orElseThrow(() -> ApiException.invalid(null));
		if (job.isFinished()) {
			return;
		}
		job.fail(reason);
		long userId = job.getUserId();
		UserProfile profile = profiles.findByIdForUpdate(userId).orElse(null);
		if (profile == null || !UserProfile.CHARACTER_PROCESSING.equals(profile.getVoiceCloneStatus())) {
			return;
		}
		State state = State.of(profile);
		String text = reason == null || reason.isBlank() ? null : reason.strip();
		if (text != null && text.length() > MAX_FAIL_REASON) {
			text = text.substring(0, MAX_FAIL_REASON);
		}
		profile.voiceCloneFailed(text);
		afterCommit(() -> {
			if (state.sampleKey() != null) {
				deleteQuietly(state.sampleKey());
			}
		});
		push.notify(userId, PushService.voiceCloneFailed());
	}

	// 대화 녹음 중인지(초안): 최장 녹음 시간(3시간) 안에 시작했고 마지막 묶음(isLast)이 아직 안 온 녹음이 있으면 녹음 중이다.
	// 녹음 첫 묶음이 오기 전(첫 1시간)은 서버가 모르니 앱이 막는다.
	boolean recording(long userId) {
		return recordings.existsUnfinishedSince(userId, OffsetDateTime.now().minusHours(MAX_RECORDING_HOURS));
	}

	// 첫 바이트로 wav(RIFF....WAVE)·m4a(....ftyp)를 가린다. 둘 다 아니면 null.
	static Audio audioType(byte[] bytes) {
		if (isWav(bytes)) {
			return new Audio("wav", "audio/wav");
		}
		if (bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
			return new Audio("m4a", "audio/mp4");
		}
		return null;
	}

	// wav 머리말(RIFF....WAVE)인지 본다.
	static boolean isWav(byte[] bytes) {
		return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
	}

	// 저장소 파일을 지운다. 실패하면 로그만 남긴다.
	private void deleteQuietly(String key) {
		try {
			storage.delete(key);
		}
		catch (Exception e) {
			log.warn("내 목소리 파일을 지우지 못했습니다.", SafeLog.withoutMessages(e));
		}
	}

	// 트랜잭션이 커밋된 뒤에 할 일을 맡긴다. 트랜잭션 밖이면 바로 한다.
	private static void afterCommit(Runnable task) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					task.run();
				}
			});
		}
		else {
			task.run();
		}
	}

	// VOICE_CLONE_NOT_FOUND 예외를 만든다.
	private static ApiException notFound() {
		return new ApiException(ErrorCode.VOICE_CLONE_NOT_FOUND);
	}

}
