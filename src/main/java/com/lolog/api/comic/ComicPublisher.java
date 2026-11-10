package com.lolog.api.comic;

import com.lolog.api.job.Job;
import com.lolog.api.job.JobRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// 만화 작업을 lolog:comic에 넣는다. 감지 작업(DetectPublisher)과 같은 방식: DB 커밋 뒤에 넣고, 못 넣은 작업은 1분마다 다시 넣는다.
@Component
public class ComicPublisher {

	static final String STREAM = "lolog:comic";
	private static final Logger log = LoggerFactory.getLogger(ComicPublisher.class);

	private final JobRepository jobs;
	private final ComicRepository comics;
	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;

	public ComicPublisher(JobRepository jobs, ComicRepository comics, StringRedisTemplate redis,
			ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
		this.jobs = jobs;
		this.comics = comics;
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.tx = new TransactionTemplate(transactionManager);
	}

	// lolog:comic의 payload 모양. API DTO 명세 4장 ComicMessage와 같다. 카드 내용은 워커가 /internal에서 받아 간다.
	record ComicMessage(Long jobId, Long comicId, Long cardId) {
	}

	// 프로필 캐릭터 작업(characters.md 큐). 만화와 같은 스트림을 써서 2호 젯슨 GPU 하나에서 차례로 돈다.
	record AvatarMessage(Long jobId, String kind, Long userId) {
	}

	public void publishQuietly(Long jobId) {
		try {
			publish(jobId);
		}
		catch (RuntimeException e) {
			log.error("만화 작업을 큐에 넣지 못했습니다. jobId={}", jobId, e);
		}
	}

	@Scheduled(fixedDelayString = "${lolog.detect.republish-delay-ms:60000}")
	void republishPending() {
		jobs.findUnpublishedIds("COMIC", OffsetDateTime.now().minusMinutes(1)).forEach(this::publishQuietly);
		jobs.findUnpublishedIds("AVATAR", OffsetDateTime.now().minusMinutes(1)).forEach(this::publishQuietly);
	}

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
