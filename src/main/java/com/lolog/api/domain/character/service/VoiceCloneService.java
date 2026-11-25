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
import com.lolog.api.global.storage.StorageDeletions;
import com.lolog.api.global.transaction.AfterCommit;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 내 목소리(characters.md "내 목소리 (FR-CHAR-05)"). 상태와 파일 키는 user_profiles.voice_clone_*에 있다.
// 녹음 → PROCESSING + VOICE_CLONE 작업(lolog:video, kind "voice_clone") → 워커가 미리 듣기를 보내면 READY, 실패하면 FAILED.
// 원본 녹음은 READY인 동안 계속 보관하고(필수 동의), 워커만 받아 간다. 쇼츠를 만들 때 참조 음성으로 쓴다.
// 다시 녹음하면 원본을 바꾸고 미리 듣기는 새로 만든다. 실패하면 쓸 수 있는 목소리가 없으니 녹음·미리 듣기를 지운다(초안).
@Service
public class VoiceCloneService {

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
	private final StorageDeletions deletions;
	private final JobRepository jobs;
	private final VoiceClonePublisher publisher;
	private final PushService push;
	private final CharacterViews views;

	/** 쓰는 저장소와 도구, 캐릭터 관련 서비스를 받는다. */
	public VoiceCloneService(UserProfileRepository profiles, RecordingRepository recordings, ImageStorage storage,
			StorageDeletions deletions, JobRepository jobs, VoiceClonePublisher publisher, PushService push, CharacterViews views) {
		this.profiles = profiles;
		this.recordings = recordings;
		this.storage = storage;
		this.deletions = deletions;
		this.jobs = jobs;
		this.publisher = publisher;
		this.push = push;
		this.views = views;
	}

	/**
	 * ProfileCharacterResponse.voiceClone. 만든 적이 없거나 지웠으면 null.
	 *
	 * @param status 내 목소리 상태
	 * @param previewUrl 미리 듣기 wav의 10분 서명 주소(없으면 null)
	 * @param failReason 실패 이유(없으면 null)
	 * @param shareWithFriends 친구 쇼츠 동의(③)
	 */
	public record VoiceCloneView(String status, String previewUrl, String failReason, boolean shareWithFriends) {
	}

	/**
	 * 바꾸기 전에 읽어 둔 내 목소리 상태와 파일 키.
	 *
	 * @param status 내 목소리 상태
	 * @param sampleKey 원본 녹음 저장소 키(없으면 null)
	 * @param previewKey 미리 듣기 저장소 키(없으면 null)
	 */
	private record State(String status, String sampleKey, String previewKey) {

		/**
		 * 프로필에서 읽는다.
		 *
		 * @param p 읽을 프로필
		 * @return 그 프로필의 내 목소리 상태와 파일 키
		 */
		static State of(UserProfile p) {
			return new State(p.getVoiceCloneStatus(), p.getVoiceCloneKey(), p.getVoiceClonePreviewKey());
		}

	}

	/**
	 * 녹음 파일 확장자와 Content-Type.
	 *
	 * @param ext 저장소 키에 붙일 확장자(wav 또는 m4a)
	 * @param contentType 저장할 Content-Type
	 */
	private record Audio(String ext, String contentType) {
	}

	/**
	 * 녹음 올리기·다시 올리기(PUT .../voice-clone). 앱에서 바로 녹음한 wav·m4a 하나와 필수 동의(consent=true).
	 *
	 * @param userId 녹음을 올린 회원 ID
	 * @param audio 녹음 바이트(wav 또는 m4a, 4MB 이하, null이면 400)
	 * @param consent 필수 동의(true가 아니면 400)
	 * @return 녹음을 받은 뒤의 프로필 캐릭터
	 */
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
		// 예전 녹음을 아직 못 지웠으면 그 지우기를 빼고 올린다(새 녹음이 지워지지 않게).
		deletions.cancel(key);
		storage.put(key, audio, type.contentType());
		profile.startVoiceClone(key, RECORD_SENTENCE);
		long jobId = jobs.save(Job.voiceClone(userId)).getId();
		// 확장자가 바뀌었으면 예전 원본, 그리고 예전 미리 듣기를 지운다. 지우기를 먼저 맡겨 큐에 넣기보다 먼저 지운다.
		if (state.sampleKey() != null && !state.sampleKey().equals(key)) {
			deletions.schedule(state.sampleKey());
		}
		if (state.previewKey() != null) {
			deletions.schedule(state.previewKey());
		}
		AfterCommit.run(() -> publisher.publishQuietly(jobId));
		return views.profileResponse(userId);
	}

	/**
	 * 내 목소리 지우기. 없어도 204. 골랐던 목소리가 "self"면 null로 바꾼다. 프로필 캐릭터를 지울 때도 부른다.
	 *
	 * @param userId 내 목소리를 지울 회원 ID
	 */
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
		if (state.sampleKey() != null) {
			deletions.schedule(state.sampleKey());
		}
		if (state.previewKey() != null) {
			deletions.schedule(state.previewKey());
		}
	}

	/**
	 * 친구 쇼츠 동의(③)만 바꾼다. 녹음은 그대로. 만든 적이 없으면 404 VOICE_CLONE_NOT_FOUND(초안).
	 *
	 * @param userId 동의를 바꾸는 회원 ID
	 * @param shareWithFriends 친구 쇼츠에 써도 되면 true(null이면 400)
	 */
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

	/**
	 * 워커가 원본 녹음을 받는다. 만드는 중이거나 만든 목소리가 있을 때만(쇼츠의 참조 음성으로도 쓴다).
	 *
	 * @param userId 녹음 주인 회원 ID
	 * @return 저장소의 원본 녹음
	 */
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

	/**
	 * 워커가 미리 듣기(wav)를 보낸다. READY로 바꾸고 본인에게 알린다. 만드는 중이 아니면 404(지웠거나 이미 끝남).
	 *
	 * @param userId 목소리 주인 회원 ID
	 * @param wav 워커가 만든 미리 듣기(wav, 4MB 이하)
	 */
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
		// 예전 미리 듣기를 아직 못 지웠으면 그 지우기를 빼고 올린다.
		deletions.cancel(key);
		storage.put(key, wav, "audio/wav");
		profile.voiceCloneReady(key);
		jobs.completeQueued(userId, "VOICE_CLONE");
		push.notify(userId, PushService.voiceCloneReady());
	}

	/**
	 * voice 작업 fail(POST /internal/jobs/{id}/fail). FAILED와 이유를 남기고, 쓸 수 없는 녹음은 지우고 알린다.
	 * 이미 결과나 fail을 받은 작업이면 무시한다.
	 *
	 * @param jobId 실패한 VOICE_CLONE 작업 ID
	 * @param reason 워커가 보낸 실패 이유(앞뒤 공백을 빼고 200자까지 남김, null 가능)
	 */
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
		if (state.sampleKey() != null) {
			deletions.schedule(state.sampleKey());
		}
		push.notify(userId, PushService.voiceCloneFailed());
	}

	/**
	 * 대화 녹음 중인지(초안): 최장 녹음 시간(3시간) 안에 시작했고 마지막 묶음(isLast)이 아직 안 온 녹음이 있으면 녹음 중이다.
	 * 녹음 첫 묶음이 오기 전(첫 1시간)은 서버가 모르니 앱이 막는다.
	 *
	 * @param userId 확인할 회원 ID
	 * @return 녹음 중이면 true
	 */
	boolean recording(long userId) {
		return recordings.existsUnfinishedSince(userId, OffsetDateTime.now().minusHours(MAX_RECORDING_HOURS));
	}

	/**
	 * 첫 바이트로 wav(RIFF....WAVE)·m4a(....ftyp)를 가린다. 둘 다 아니면 null.
	 *
	 * @param bytes 살펴볼 파일 바이트
	 * @return 확장자와 Content-Type(둘 다 아니면 null)
	 */
	static Audio audioType(byte[] bytes) {
		if (isWav(bytes)) {
			return new Audio("wav", "audio/wav");
		}
		if (bytes.length >= 12 && bytes[4] == 'f' && bytes[5] == 't' && bytes[6] == 'y' && bytes[7] == 'p') {
			return new Audio("m4a", "audio/mp4");
		}
		return null;
	}

	/**
	 * wav 머리말(RIFF....WAVE)인지 본다.
	 *
	 * @param bytes 살펴볼 파일 바이트
	 * @return wav 머리말이면 true
	 */
	static boolean isWav(byte[] bytes) {
		return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
	}

	// VOICE_CLONE_NOT_FOUND 예외를 만든다.
	private static ApiException notFound() {
		return new ApiException(ErrorCode.VOICE_CLONE_NOT_FOUND);
	}

}
