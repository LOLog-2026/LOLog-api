package com.lolog.api.domain.audio.service;

import com.lolog.api.domain.audio.repository.RecordingAudioFileRepository;
import com.lolog.api.domain.recording.entity.Recording;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 녹음 음성 파일 하나(10분 단위)를 받아 저장한다(audio.md 6장 PUT /api/recordings/{id}/audio/{seq}).
// 같은 번호를 다시 올리면 덮어쓴다(앱 재시도). 저장한 뒤 그 녹음의 카드 구간 음성을 자른다.
@Service
public class AudioUploadService {

	// Nginx 본문 한도와 같게 맞춘다(audio.md 오류 코드 PAYLOAD_TOO_LARGE).
	public static final long MAX_BYTES = 5L * 1024 * 1024;

	private final RecordingRepository recordings;
	private final ImageStorage storage;
	private final CardAudioClipper clipper;
	private final RecordingAudioFileRepository files;

	public AudioUploadService(RecordingRepository recordings, ImageStorage storage, CardAudioClipper clipper,
			RecordingAudioFileRepository files) {
		this.recordings = recordings;
		this.storage = storage;
		this.clipper = clipper;
		this.files = files;
	}

	// 음성 파일을 확인해 저장하고 카드 구간 자르기를 맡긴다.
	@Transactional
	public void upload(long userId, long recordingId, int seq, byte[] file, Long startMs, Long endMs) {
		if (seq < 1) {
			throw ApiException.invalid("seq");
		}
		if (startMs == null || startMs < 0) {
			throw ApiException.invalid("startMs");
		}
		if (endMs == null || endMs <= startMs) {
			throw ApiException.invalid("endMs");
		}
		if (file == null) {
			throw ApiException.invalid("file");
		}
		if (file.length > MAX_BYTES) {
			throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
		}
		try {
			Adts.frames(file);
		}
		catch (IllegalArgumentException e) {
			throw ApiException.invalid("file");
		}
		// 녹음 행을 잠가 같은 번호가 동시에 와도 차례로 덮어쓴다.
		Recording recording = recordings.findByIdAndUserIdForUpdate(recordingId, userId)
			.orElseThrow(() -> new ApiException(ErrorCode.RECORDING_NOT_FOUND));
		if (!recording.isAudioSaved()) {
			throw new ApiException(ErrorCode.INVALID_REQUEST);
		}

		// 키가 번호마다 같아서, 파일을 올린 뒤 DB 저장이 실패해도 다시 올리면 덮어써진다.
		String key = ImageStorage.recordingAudioKey(userId, recordingId, seq);
		storage.putAac(key, file);
		files.upsert(recordingId, seq, key, startMs, endMs);
		clipper.clipRecordingLater(recordingId);
	}

}
