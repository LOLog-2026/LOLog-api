package com.lolog.api.domain.comic.service;

import com.lolog.api.domain.comic.entity.Comic;
import com.lolog.api.domain.comic.repository.ComicRepository;
import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
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

// 만화 작업을 lolog:comic에 넣는다. 감지 작업(DetectPublisher)과 같은 방식: DB 커밋 뒤에 넣고, 못 넣은 작업은 1분마다 다시 넣는다.
@Component
public class ComicPublisher {

	public static final String STREAM = "lolog:comic";
	private static final Logger log = LoggerFactory.getLogger(ComicPublisher.class);

	private final JobRepository jobs;
	private final ComicRepository comics;
	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;

	// 큐에 넣는 데 필요한 저장소와 도구를 받는다.
	public ComicPublisher(JobRepository jobs, ComicRepository comics, StringRedisTemplate redis,
			ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
		this.jobs = jobs;
		this.comics = comics;
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.tx = new TransactionTemplate(transactionManager);
		// 커밋 뒤에 불려도 끝난 트랜잭션에 끼지 않게 새 트랜잭션으로 넣음 표시를 저장한다.
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	// lolog:comic의 payload 모양. API DTO 명세 4장 ComicMessage와 같다. 카드 내용은 워커가 /internal에서 받아 간다.
	record ComicMessage(Long jobId, Long comicId, Long cardId) {
	}

	// 프로필 캐릭터 작업(characters.md 큐). 만화와 같은 스트림을 써서 2호 젯슨 GPU 하나에서 차례로 돈다.
	record AvatarMessage(Long jobId, String kind, Long userId) {
	}

	// 작업을 큐에 넣고, 실패하면 로그만 남긴다.
	public void publishQuietly(Long jobId) {
		try {
			publish(jobId);
		}
		catch (RuntimeException e) {
			log.error("만화 작업을 큐에 넣지 못했습니다. jobId={}", jobId, e);
		}
	}

	// 아직 못 넣은 만화·캐릭터 작업을 다시 넣는다.
	@Scheduled(fixedDelayString = "${lolog.detect.republish-delay-ms:60000}")
	void republishPending() {
		jobs.findUnpublishedIds("COMIC", OffsetDateTime.now().minusMinutes(1)).forEach(this::publishQuietly);
		jobs.findUnpublishedIds("AVATAR", OffsetDateTime.now().minusMinutes(1)).forEach(this::publishQuietly);
	}

	// 작업 하나를 메시지로 만들어 스트림에 넣고 넣었다고 표시한다.
	private void publish(Long jobId) {
		tx.executeWithoutResult(status -> {
			Job job = jobs.findById(jobId).orElseThrow();
			if (job.getAttempts() > 0) {
				return;
			}
			Object message;
			if ("AVATAR".equals(job.getJobType())) {
				message = new AvatarMessage(job.getId(), "avatar", job.getUserId());
			}
			else {
				Comic comic = comics.findById(job.getComicId()).orElseThrow();
				message = new ComicMessage(job.getId(), comic.getId(), comic.getCardId());
			}
			redis.opsForStream().add(StreamRecords.string(
					Map.of("payload", objectMapper.writeValueAsString(message))).withStreamKey(STREAM));
			job.markPublished();
		});
	}

}
