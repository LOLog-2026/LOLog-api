package com.lolog.api.domain.video.service;

import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.video.entity.Video;
import com.lolog.api.domain.video.repository.VideoRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
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
	private final VideoRepository videos;
	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;

	/** 큐에 넣는 데 필요한 저장소와 도구를 받는다. */
	public VideoPublisher(JobRepository jobs, VideoRepository videos, StringRedisTemplate redis, ObjectMapper objectMapper,
			PlatformTransactionManager transactionManager) {
		this.jobs = jobs;
		this.videos = videos;
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.tx = new TransactionTemplate(transactionManager);
		// 커밋 뒤에 불려도 끝난 트랜잭션에 끼지 않게 새 트랜잭션으로 넣음 표시를 저장한다.
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 3호에 보내는 목소리 단계 메시지.
	 *
	 * @param jobId 목소리 단계 작업 ID
	 * @param videoId 영상 ID
	 * @param cardId 영상을 만들 카드 ID. 카드를 지웠으면 null
	 */
	record VoiceMessage(Long jobId, Long videoId, Long cardId) {
	}

	/**
	 * 2호에 보내는 그림 단계 메시지.
	 *
	 * @param jobId 그림 단계 작업 ID
	 * @param kind 작업 종류(늘 "video")
	 * @param videoId 영상 ID
	 * @param cardId 영상을 만든 카드 ID. 카드를 지웠으면 null
	 */
	record DrawMessage(Long jobId, String kind, Long videoId, Long cardId) {
	}

	/**
	 * 실패해도 오류를 넘기지 않는다. 못 넣은 작업은 다시 넣기에서 넣는다.
	 *
	 * @param jobId 큐에 넣을 영상 작업 ID
	 */
	public void publishQuietly(Long jobId) {
		try {
			publish(jobId);
		}
		catch (RuntimeException e) {
			log.error("영상 작업을 큐에 넣지 못했습니다. jobId={}", jobId, e);
		}
	}

	// 큐에 넣지 못한 영상 작업을 다시 넣는다.
	@Scheduled(fixedDelayString = "${lolog.detect.republish-delay-ms:60000}")
	void republishPending() {
		OffsetDateTime before = OffsetDateTime.now().minusMinutes(1);
		jobs.findUnpublishedIds("VIDEO_VOICE", before).forEach(this::publishQuietly);
		jobs.findUnpublishedIds("VIDEO_DRAW", before).forEach(this::publishQuietly);
	}

	/**
	 * 작업 단계에 맞는 스트림에 메시지를 넣는다.
	 *
	 * @param jobId 넣을 영상 작업 ID. 이미 시도한 작업이면 넣지 않는다
	 */
	private void publish(Long jobId) {
		tx.executeWithoutResult(status -> {
			Job job = jobs.findById(jobId).orElseThrow();
			if (job.getAttempts() > 0) {
				return;
			}
			Long cardId = videos.findById(job.getVideoId()).map(Video::getCardId).orElse(null);
			boolean voice = "VIDEO_VOICE".equals(job.getJobType());
			Object message = voice ? new VoiceMessage(job.getId(), job.getVideoId(), cardId)
					: new DrawMessage(job.getId(), "video", job.getVideoId(), cardId);
			redis.opsForStream().add(StreamRecords.string(
					Map.of("payload", objectMapper.writeValueAsString(message))).withStreamKey(voice ? VOICE_STREAM : DRAW_STREAM));
			job.markPublished();
		});
	}

}
