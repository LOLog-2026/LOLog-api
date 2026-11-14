package com.lolog.api.character;

import com.lolog.api.common.ApiException;
import com.lolog.api.common.SafeLog;
import com.lolog.api.job.Job;
import com.lolog.api.job.JobRepository;
import com.lolog.api.notification.PushService;
import com.lolog.api.storage.ImageStorage;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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
	static final long MAX_AUDIO_BYTES = 4L * 1024 * 1024;
	static final int MAX_FAIL_REASON = 200;
	// 읽은 문장 글자(voice_clone_text). 문장 목록이 바뀌어도 남게 id가 아니라 글자를 저장한다(정함 11-14).
	static final int MAX_TEXT = 200;
	// 최장 녹음 시간. 이 안에 시작해 마지막 묶음이 안 온 녹음이 있으면 녹음 중으로 본다(초안).
	static final int MAX_RECORDING_HOURS = 3;

	private final JdbcTemplate jdbc;
	private final ImageStorage storage;
	private final JobRepository jobs;
	private final VoiceClonePublisher publisher;
	private final PushService push;
	private final CharacterViews views;

	public VoiceCloneService(JdbcTemplate jdbc, ImageStorage storage, JobRepository jobs, VoiceClonePublisher publisher,
			PushService push, CharacterViews views) {
		this.jdbc = jdbc;
		this.storage = storage;
		this.jobs = jobs;
		this.publisher = publisher;
		this.push = push;
		this.views = views;
	}

	// ProfileCharacterResponse.voiceClone. 만든 적이 없거나 지웠으면 null.
	public record VoiceCloneView(String status, String previewUrl, String failReason, boolean shareWithFriends) {
	}

	private record State(String status, String sampleKey, String previewKey) {
	}

	private record Audio(String ext, String contentType) {
	}

	// 녹음 올리기·다시 올리기(PUT .../voice-clone). 앱에서 바로 녹음한 wav·m4a 하나, 읽은 문장(text), 필수 동의(consent=true).
	@Transactional
	public ProfileCharacterService.ProfileCharacterResponse upload(long userId, byte[] audio, String text,
			Boolean consent) {
		if (!publisher.enabled()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VOICE_CLONE_UNAVAILABLE", "아직 내 목소리를 만들 수 없어요.");
		}
		if (!Boolean.TRUE.equals(consent)) {
			throw ApiException.invalid("consent", "녹음 보관에 동의해야 만들 수 있어요.");
		}
		if (audio == null || audio.length == 0) {
			throw ApiException.invalid("audio", "녹음이 없어요.");
		}
		if (audio.length > MAX_AUDIO_BYTES) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "녹음이 너무 큽니다.");
		}
		Audio type = audioType(audio);
		if (type == null) {
			throw ApiException.invalid("audio", "wav나 m4a 녹음만 받아요.");
		}
		String sentence = text == null ? "" : text.strip();
		if (sentence.isEmpty() || sentence.codePointCount(0, sentence.length()) > MAX_TEXT) {
			throw ApiException.invalid("text", "읽은 문장은 1~" + MAX_TEXT + "자예요.");
		}
		if (recording(userId)) {
			throw new ApiException(HttpStatus.CONFLICT, "RECORDING_IN_PROGRESS", "대화를 녹음하는 중에는 만들 수 없어요.");
		}
		// 목소리는 프로필 캐릭터가 있을 때만 고를 수 있어서 만들기도 같다(FR-CHAR-04).
		List<String> character = jdbc.queryForList("select character_key from user_profiles where user_id = ? for update",
				String.class, userId);
		if (character.isEmpty() || character.getFirst() == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "CHARACTER_NOT_FOUND", "프로필 캐릭터가 없어요.");
		}
		State state = state(userId);
		if ("PROCESSING".equals(state.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "VOICE_CLONE_IN_PROGRESS", "내 목소리를 만드는 중이에요.");
		}
		String key = ImageStorage.voiceCloneSampleKey(userId, type.ext());
		storage.put(key, audio, type.contentType());
		jdbc.update("""
				update user_profiles set voice_clone_status = 'PROCESSING', voice_clone_key = ?, voice_clone_preview_key = null,
				    voice_clone_text = ?, voice_clone_fail_reason = null, voice_clone_consented_at = current_timestamp,
				    voice_clone_updated_at = current_timestamp, updated_at = current_timestamp
				where user_id = ?
				""", key, sentence, userId);
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
		List<State> found = jdbc.query("""
				select voice_clone_status, voice_clone_key, voice_clone_preview_key from user_profiles where user_id = ? for update
				""", (rs, n) -> new State(rs.getString(1), rs.getString(2), rs.getString(3)), userId);
		if (found.isEmpty() || found.getFirst().status() == null) {
			return;
		}
		State state = found.getFirst();
		jdbc.update("""
				update user_profiles set voice_clone_status = null, voice_clone_key = null, voice_clone_preview_key = null,
				    voice_clone_consented_at = null, voice_clone_fail_reason = null, voice_clone_share_friends = false,
				    voice_clone_text = null, voice_clone_updated_at = current_timestamp,
				    character_voice = case when character_voice = 'self' then null else character_voice end,
				    updated_at = current_timestamp
				where user_id = ?
				""", userId);
		// 만드는 중이던 작업은 실패로 둔다. 워커가 나중에 녹음을 찾으면 404라 그 메시지를 버린다.
		jdbc.update("update jobs set status = 'FAILED', fail_reason = '내 목소리를 지움', updated_at = current_timestamp "
				+ "where user_id = ? and job_type = 'VOICE_CLONE' and status = 'QUEUED'", userId);
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
			throw ApiException.invalid("shareWithFriends", "shareWithFriends가 필요해요.");
		}
		int updated = jdbc.update("""
				update user_profiles set voice_clone_share_friends = ?, updated_at = current_timestamp
				where user_id = ? and voice_clone_status is not null
				""", shareWithFriends, userId);
		if (updated == 0) {
			throw notFound("내 목소리가 없어요.");
		}
	}

	// 워커가 원본 녹음을 받는다. 만드는 중이거나 만든 목소리가 있을 때만(쇼츠의 참조 음성으로도 쓴다).
	@Transactional(readOnly = true)
	public ImageStorage.StoredObject sample(long userId) {
		List<String> key = jdbc.queryForList("""
				select voice_clone_key from user_profiles
				where user_id = ? and voice_clone_status in ('PROCESSING', 'READY') and voice_clone_key is not null
				""", String.class, userId);
		if (key.isEmpty()) {
			throw notFound("받을 녹음이 없습니다.");
		}
		try {
			return storage.getObject(key.getFirst());
		}
		catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
			throw notFound("받을 녹음이 없습니다.");
		}
	}

	// 워커가 미리 듣기(wav)를 보낸다. READY로 바꾸고 본인에게 알린다. 만드는 중이 아니면 404(지웠거나 이미 끝남).
	@Transactional
	public void preview(long userId, byte[] wav) {
		if (wav == null || !isWav(wav)) {
			throw ApiException.invalid("file", "wav 파일이어야 해요.");
		}
		if (wav.length > MAX_AUDIO_BYTES) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "미리 듣기가 너무 큽니다.");
		}
		State state = lockedState(userId);
		if (state == null || !"PROCESSING".equals(state.status())) {
			throw notFound("만드는 중인 내 목소리가 없습니다.");
		}
		String key = ImageStorage.voiceClonePreviewKey(userId);
		storage.put(key, wav, "audio/wav");
		jdbc.update("""
				update user_profiles set voice_clone_status = 'READY', voice_clone_preview_key = ?,
				    voice_clone_updated_at = current_timestamp, updated_at = current_timestamp
				where user_id = ?
				""", key, userId);
		jdbc.update("update jobs set status = 'DONE', updated_at = current_timestamp "
				+ "where user_id = ? and job_type = 'VOICE_CLONE' and status = 'QUEUED'", userId);
		push.notify(userId, PushService.voiceCloneReady());
	}

	// voice 작업 fail(POST /internal/jobs/{id}/fail). FAILED와 이유를 남기고, 쓸 수 없는 녹음은 지우고 알린다.
	// 이미 결과나 fail을 받은 작업이면 무시한다.
	@Transactional
	public void fail(long jobId, String reason) {
		Job job = jobs.findByIdForUpdate(jobId).orElseThrow(() -> ApiException.invalid(null, "작업을 찾을 수 없습니다."));
		if (job.isFinished()) {
			return;
		}
		job.fail(reason);
		long userId = job.getUserId();
		State state = lockedState(userId);
		if (state == null || !"PROCESSING".equals(state.status())) {
			return;
		}
		String text = reason == null || reason.isBlank() ? null : reason.strip();
		if (text != null && text.length() > MAX_FAIL_REASON) {
			text = text.substring(0, MAX_FAIL_REASON);
		}
		jdbc.update("""
				update user_profiles set voice_clone_status = 'FAILED', voice_clone_fail_reason = ?, voice_clone_key = null,
				    voice_clone_preview_key = null, voice_clone_text = null, voice_clone_updated_at = current_timestamp,
				    character_voice = case when character_voice = 'self' then null else character_voice end,
				    updated_at = current_timestamp
				where user_id = ?
				""", text, userId);
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
		Boolean found = jdbc.queryForObject("""
				select exists (select 1 from recordings r where r.user_id = ?
				  and r.started_at > current_timestamp - make_interval(hours => ?)
				  and not exists (select 1 from recording_parts p where p.recording_id = r.id and p.is_last))
				""", Boolean.class, userId, MAX_RECORDING_HOURS);
		return Boolean.TRUE.equals(found);
	}

	private State state(long userId) {
		return jdbc.queryForObject(
				"select voice_clone_status, voice_clone_key, voice_clone_preview_key from user_profiles where user_id = ?",
				(rs, n) -> new State(rs.getString(1), rs.getString(2), rs.getString(3)), userId);
	}

	private State lockedState(long userId) {
		List<State> found = jdbc.query("""
				select voice_clone_status, voice_clone_key, voice_clone_preview_key from user_profiles where user_id = ? for update
				""", (rs, n) -> new State(rs.getString(1), rs.getString(2), rs.getString(3)), userId);
		return found.isEmpty() ? null : found.getFirst();
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

	static boolean isWav(byte[] bytes) {
		return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
	}

	private void deleteQuietly(String key) {
		try {
			storage.delete(key);
		}
		catch (Exception e) {
			log.warn("내 목소리 파일을 지우지 못했습니다.", SafeLog.withoutMessages(e));
		}
	}

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

	private static ApiException notFound(String message) {
		return new ApiException(HttpStatus.NOT_FOUND, "VOICE_CLONE_NOT_FOUND", message);
	}

}
