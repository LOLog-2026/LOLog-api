package com.lolog.api.domain.audio.service;

import com.lolog.api.domain.audio.repository.RecordingAudioFileRepository;
import com.lolog.api.domain.audio.repository.RecordingAudioFileRepository.ExpiredRecording;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.util.SafeLog;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 한 시간마다 음성을 정리한다(audio.md 5장).
// 1) 아직 구간 음성이 없는 카드를 다시 자른다(앞서 자르다 실패했거나 서버가 그 사이 꺼졌던 경우).
// 2) 녹음이 끝난 지 7일 지난 녹음의 전체 파일을 지운다. 녹음 하나의 파일은 한꺼번에 지운다.
// 지우기 전에 자르기를 먼저 해서, 마지막 기회에 자를 수 있는 카드는 자른다.
@Component
public class AudioRetentionJob {

	private static final Logger log = LoggerFactory.getLogger(AudioRetentionJob.class);

	static final Duration KEEP = Duration.ofDays(7);
	// 한 번에 다시 자를 카드 수. 남은 카드는 다음 시간에 자른다.
	private static final int RETRY_LIMIT = 500;

	private final RecordingAudioFileRepository files;
	private final ImageStorage storage;
	private final CardAudioClipper clipper;

	// 리포지토리, 저장소, 자르기를 받는다.
	public AudioRetentionJob(RecordingAudioFileRepository files, ImageStorage storage, CardAudioClipper clipper) {
		this.files = files;
		this.storage = storage;
		this.clipper = clipper;
	}

	// 정해진 시간마다 자르기와 지우기를 차례로 한다.
	@Scheduled(fixedDelayString = "${lolog.audio.retention-delay-ms:3600000}",
			initialDelayString = "${lolog.audio.retention-initial-delay-ms:60000}")
	public void run() {
		try {
			retryClips();
			deleteExpired();
		}
		catch (Exception e) {
			log.warn("음성을 정리하지 못했습니다. 다음 시간에 다시 합니다.", SafeLog.withoutMessages(e));
		}
	}

	// 구간 음성이 없는 카드를 다시 자른다.
	void retryClips() {
		List<Long> cardIds = files.findCardIdsToClip(RETRY_LIMIT);
		cardIds.forEach(clipper::clipQuietly);
	}

	// 7일 지난 녹음의 전체 음성 파일을 지운다.
	void deleteExpired() {
		List<ExpiredRecording> expired = files.findExpiredRecordings(KEEP.toSeconds());
		for (ExpiredRecording recording : expired) {
			long recordingId = recording.getId();
			try {
				// 저장소를 먼저 지운다. 실패하면 줄이 남아 다음 시간에 다시 지운다.
				storage.deletePrefix(ImageStorage.recordingAudioPrefix(recording.getUserId(), recordingId));
				files.deleteAllByRecordingId(recordingId);
			}
			catch (Exception e) {
				log.warn("녹음 {}의 음성 파일을 지우지 못했습니다.", recordingId, SafeLog.withoutMessages(e));
			}
		}
		if (!expired.isEmpty()) {
			log.info("7일 지난 녹음 {}개의 음성 파일을 지웠습니다.", expired.size());
		}
	}

}
