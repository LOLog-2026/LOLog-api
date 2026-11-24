package com.lolog.api.domain.character.service;

import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import java.time.OffsetDateTime;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// 내 목소리 작업을 TTS가 있는 3호 큐 lolog:video에 { jobId, kind: "voice_clone", userId, text }로 넣는다(characters.md "내 목소리" 워커, VoiceCloneMessage).
// text는 사용자가 읽은 문장이고 워커가 ref_text로 쓴다.
// 지금 ai 워커는 lolog:video 메시지를 kind와 상관없이 쇼츠로 처리해서, ai 쪽 kind 분기가 생기기 전에는 넣으면 안 된다.
// 그래서 lolog.voice-clone.queue-enabled(기본 false)로 켤 때만 넣고, 꺼져 있으면 만들기 요청을 503으로 거절한다.
// 넣는 방식은 다른 작업과 같다: DB 커밋 뒤에 넣고, 못 넣은 작업은 1분마다 다시 넣는다.
@Component
public class VoiceClonePublisher {

	static final String STREAM = "lolog:video";
	private static final Logger log = LoggerFactory.getLogger(VoiceClonePublisher.class);

	private final boolean enabled;
	private final JobRepository jobs;
	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;
	private final UserProfileRepository profiles;

	public VoiceClonePublisher(@Value("${lolog.voice-clone.queue-enabled:false}") boolean enabled, JobRepository jobs,
			StringRedisTemplate redis, ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
			UserProfileRepository profiles) {
		this.profiles = profiles;
		this.enabled = enabled;
		this.jobs = jobs;
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.tx = new TransactionTemplate(transactionManager);
		// 커밋 뒤에 불려도 끝난 트랜잭션에 끼지 않게 새 트랜잭션으로 넣음 표시를 저장한다.
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	// 큐에 넣는 내 목소리 작업 메시지.
	record VoiceCloneMessage(Long jobId, String kind, Long userId, String text) {
	}

	// 큐에 넣기를 켰는지 알려 준다.
	public boolean enabled() {
		return enabled;
	}

	// 작업을 큐에 넣는다. 실패하면 로그만 남기고 다음에 다시 넣는다.
	public void publishQuietly(Long jobId) {
		if (!enabled) {
			return;
		}
		try {
			publish(jobId);
		}
		catch (RuntimeException e) {
			log.error("내 목소리 작업을 큐에 넣지 못했습니다. jobId={}", jobId, e);
		}
	}

	// 아직 큐에 못 넣은 작업을 1분마다 다시 넣는다.
	@Scheduled(fixedDelayString = "${lolog.detect.republish-delay-ms:60000}")
	void republishPending() {
		if (enabled) {
			jobs.findUnpublishedIds("VOICE_CLONE", OffsetDateTime.now().minusMinutes(1)).forEach(this::publishQuietly);
		}
	}

	// 작업을 한 번만 큐에 넣고 넣었다고 표시한다.
	private void publish(Long jobId) {
		tx.executeWithoutResult(status -> {
			Job job = jobs.findById(jobId).orElseThrow();
			if (job.getAttempts() > 0 || job.isFinished()) {
				return;
			}
			String text = profiles.findById(job.getUserId()).map(UserProfile::getVoiceCloneText).orElse(null);
			VoiceCloneMessage message = new VoiceCloneMessage(job.getId(), "voice_clone", job.getUserId(), text);
			redis.opsForStream().add(StreamRecords.string(
					Map.of("payload", objectMapper.writeValueAsString(message))).withStreamKey(STREAM));
			job.markPublished();
		});
	}

}
