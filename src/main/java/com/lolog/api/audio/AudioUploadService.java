package com.lolog.api.audio;

import com.lolog.api.common.ApiException;
import com.lolog.api.storage.ImageStorage;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 녹음 음성 파일 하나(10분 단위)를 받아 저장한다(audio.md 6장 PUT /api/recordings/{id}/audio/{seq}).
// 같은 번호를 다시 올리면 덮어쓴다(앱 재시도). 저장한 뒤 그 녹음의 카드 구간 음성을 자른다.
@Service
public class AudioUploadService {

	// Nginx 본문 한도와 같게 맞춘다(audio.md 오류 코드 PAYLOAD_TOO_LARGE).
	static final long MAX_BYTES = 5L * 1024 * 1024;

	private final JdbcTemplate jdbc;
	private final ImageStorage storage;
	private final CardAudioClipper clipper;

	public AudioUploadService(JdbcTemplate jdbc, ImageStorage storage, CardAudioClipper clipper) {
		this.jdbc = jdbc;
		this.storage = storage;
		this.clipper = clipper;
	}

	@Transactional
	public void upload(long userId, long recordingId, int seq, byte[] file, Long startMs, Long endMs) {
		if (seq < 1) {
			throw ApiException.invalid("seq", "파일 번호는 1부터예요.");
		}
		if (startMs == null || startMs < 0) {
			throw ApiException.invalid("startMs", "startMs는 0 이상이어야 해요.");
		}
		if (endMs == null || endMs <= startMs) {
			throw ApiException.invalid("endMs", "endMs는 startMs보다 커야 해요.");
		}
		if (file == null) {
			throw ApiException.invalid("file", "음성 파일이 없어요.");
		}
		if (file.length > MAX_BYTES) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "파일이 너무 큽니다.");
		}
		try {
			Adts.frames(file);
		}
		catch (IllegalArgumentException e) {
			throw ApiException.invalid("file", "AAC(ADTS) 파일이 아니에요.");
		}
		// 녹음 행을 잠가 같은 번호가 동시에 와도 차례로 덮어쓴다.
		Boolean audioSaved = jdbc.query("select audio_saved from recordings where id = ? and user_id = ? for update",
				(rs, n) -> rs.getBoolean("audio_saved"), recordingId, userId)
			.stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RECORDING_NOT_FOUND", "녹음을 찾을 수 없습니다."));
		if (!audioSaved) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "음성 저장을 켜지 않은 녹음이에요.");
		}

		// 키가 번호마다 같아서, 파일을 올린 뒤 DB 저장이 실패해도 다시 올리면 덮어써진다.
		String key = ImageStorage.recordingAudioKey(userId, recordingId, seq);
		storage.putAac(key, file);
		jdbc.update("""
				insert into recording_audio_files (recording_id, seq, audio_key, start_ms, end_ms) values (?, ?, ?, ?, ?)
				on conflict (recording_id, seq) do update
				set audio_key = excluded.audio_key, start_ms = excluded.start_ms, end_ms = excluded.end_ms,
				    updated_at = current_timestamp
				""", recordingId, seq, key, startMs, endMs);
		clipper.clipRecordingLater(recordingId);
	}

}
