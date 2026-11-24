package com.lolog.api.domain.text.service;

import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.text.entity.Text;
import com.lolog.api.domain.text.repository.TextRepository;
import com.lolog.api.global.util.SafeLog;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
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

// 글 작업을 Jetson 1호 스트림 lolog:detect에 kind "text"로 넣는다(texts.md 5장).
// 감지(DetectPublisher)와 같은 방식: DB 커밋 뒤에 넣고, 넣지 못한 작업은 1분마다 다시 넣는다.
@Component
public class TextPublisher {

	static final String STREAM = "lolog:detect";
	private static final Logger log = LoggerFactory.getLogger(TextPublisher.class);

	private final JobRepository jobs;
	private final TextRepository texts;
	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;

	public TextPublisher(JobRepository jobs, TextRepository texts, StringRedisTemplate redis, ObjectMapper objectMapper,
			PlatformTransactionManager transactionManager) {
		this.jobs = jobs;
		this.texts = texts;
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.tx = new TransactionTemplate(transactionManager);
		// 커밋 뒤에 불려도 끝난 트랜잭션에 끼지 않게 새 트랜잭션으로 넣음 표시를 저장한다.
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	// 실패해도 글은 이미 저장됐으므로 오류를 넘기지 않는다. 메시지에 글이 들어 있어 예외 메시지는 남기지 않는다(TR-12).
	public void publishQuietly(Long jobId) {
		try {
			publish(jobId);
		}
		catch (RuntimeException e) {
			log.error("글 작업을 큐에 넣지 못했습니다. jobId={}", jobId, SafeLog.withoutMessages(e));
		}
	}

	// 큐에 넣지 못한 글 작업을 다시 넣는다.
	@Scheduled(fixedDelayString = "${lolog.detect.republish-delay-ms:60000}")
	void republishPending() {
		jobs.findUnpublishedIds("TEXT", OffsetDateTime.now().minusMinutes(1)).forEach(this::publishQuietly);
	}

	// 작업 하나를 메시지로 만들어 스트림에 넣는다.
	private void publish(Long jobId) {
		tx.executeWithoutResult(status -> {
			Job job = jobs.findById(jobId).orElse(null);
			// 그사이 카드·글이 지워져 작업도 지워졌으면 넣지 않는다.
			if (job == null || job.getAttempts() > 0) {
				return;
			}
			Text text = texts.findById(job.getTextId()).orElse(null);
			if (text == null) {
				return;
			}
			// place·situation은 비면 메시지에서 뺀다.
			Map<String, Object> message = new LinkedHashMap<>();
			message.put("jobId", job.getId());
			message.put("kind", "text");
			message.put("textId", job.getTextId());
			message.put("title", text.getTitle());
			message.put("body", text.getBody());
			if (text.getPlace() != null) {
				message.put("place", text.getPlace());
			}
			if (text.getSituation() != null) {
				message.put("situation", text.getSituation());
			}
			redis.opsForStream().add(StreamRecords.string(
					Map.of("payload", objectMapper.writeValueAsString(message))).withStreamKey(STREAM));
			job.markPublished();
		});
	}

}
