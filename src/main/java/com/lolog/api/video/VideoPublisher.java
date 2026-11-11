package com.lolog.api.video;

import com.lolog.api.job.Job;
import com.lolog.api.job.JobRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// 영상 작업을 큐에 넣는다(video.md 4장). 만화(ComicPublisher)와 같은 방식: DB 커밋 뒤에 넣고, 못 넣은 작업은 1분마다 다시 넣는다.
// 목소리 단계(VIDEO_VOICE)는 3호가 읽는 lolog:video, 그림 단계(VIDEO_DRAW)는 2호가 읽는 lolog:comic(kind "video")이다.
@Component
public class VideoPublisher {

	static final String VOICE_STREAM = "lolog:video";
	static final String DRAW_STREAM = "lolog:comic";
	private static final Logger log = LoggerFactory.getLogger(VideoPublisher.class);

	private final JobRepository jobs;
	private final JdbcTemplate jdbc;
	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;

	public VideoPublisher(JobRepository jobs, JdbcTemplate jdbc, StringRedisTemplate redis, ObjectMapper objectMapper,
			PlatformTransactionManager transactionManager) {
		this.jobs = jobs;
		this.jdbc = jdbc;
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.tx = new TransactionTemplate(transactionManager);
	}

	record VoiceMessage(Long jobId, Long videoId, Long cardId) {
	}

	record DrawMessage(Long jobId, String kind, Long videoId, Long cardId) {
	}

	public void publishQuietly(Long jobId) {
		try {
			publish(jobId);
		}
		catch (RuntimeException e) {
			log.error("영상 작업을 큐에 넣지 못했습니다. jobId={}", jobId, e);
		}
	}

	@Scheduled(fixedDelayString = "${lolog.detect.republish-delay-ms:60000}")
	void republishPending() {
		OffsetDateTime before = OffsetDateTime.now().minusMinutes(1);
		jobs.findUnpublishedIds("VIDEO_VOICE", before).forEach(this::publishQuietly);
		jobs.findUnpublishedIds("VIDEO_DRAW", before).forEach(this::publishQuietly);
	}

	private void publish(Long jobId) {
		tx.executeWithoutResult(status -> {
			Job job = jobs.findById(jobId).orElseThrow();
			if (job.getAttempts() > 0) {
				return;
			}
			Long cardId = jdbc.queryForList("select card_id from videos where id = ?", Long.class, job.getVideoId())
				.stream()
				.findFirst()
				.orElse(null);
			boolean voice = "VIDEO_VOICE".equals(job.getJobType());
			Object message = voice ? new VoiceMessage(job.getId(), job.getVideoId(), cardId)
					: new DrawMessage(job.getId(), "video", job.getVideoId(), cardId);
			redis.opsForStream().add(StreamRecords.string(
					Map.of("payload", objectMapper.writeValueAsString(message))).withStreamKey(voice ? VOICE_STREAM : DRAW_STREAM));
			job.markPublished();
		});
	}

}
