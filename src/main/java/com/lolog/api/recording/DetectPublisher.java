package com.lolog.api.recording;

import com.lolog.api.recording.RecordingDtos.DetectMessage;
import com.lolog.api.recording.RecordingDtos.Sentence;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// 감지 작업을 Redis Streams에 넣는다. DB 커밋 뒤에 넣으므로, 넣지 못한 작업은 주기적으로 다시 넣는다.
@Component
public class DetectPublisher {

	static final String STREAM = "lolog:detect";
	private static final Logger log = LoggerFactory.getLogger(DetectPublisher.class);

	private final DetectJobRepository jobs;
	private final RecordingPartRepository parts;
	private final StringRedisTemplate redis;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;

	public DetectPublisher(DetectJobRepository jobs, RecordingPartRepository parts, StringRedisTemplate redis,
			ObjectMapper objectMapper, PlatformTransactionManager transactionManager) {
		this.jobs = jobs;
		this.parts = parts;
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.tx = new TransactionTemplate(transactionManager);
	}

	// 실패해도 앱에는 이미 저장된 것이므로 오류를 넘기지 않는다. 대화 글은 로그에 남기지 않는다.
	void publishQuietly(Long jobId) {
		try {
			publish(jobId);
		}
		catch (RuntimeException e) {
			log.error("감지 작업을 큐에 넣지 못했습니다. jobId={}", jobId, e);
		}
	}

	@Scheduled(fixedDelayString = "${lolog.detect.republish-delay-ms:60000}")
	void republishPending() {
		jobs.findUnpublishedIds(OffsetDateTime.now().minusMinutes(1)).forEach(this::publishQuietly);
	}

	private void publish(Long jobId) {
		tx.executeWithoutResult(status -> {
			DetectJob job = jobs.findById(jobId).orElseThrow();
			if (job.getAttempts() > 0) {
				return;
			}
			RecordingPart part = parts.findById(job.getRecordingPartId()).orElseThrow();
			Long recordingId = part.getRecording().getId();
			List<RecordingPart> all = parts.findByRecordingIdOrderByPartNo(recordingId);
			DetectMessage message = new DetectMessage(job.getId(), recordingId, part.getPartNo(), part.isLast(),
					sentencesFor(part, all));
			redis.opsForStream().add(StreamRecords.string(
					Map.of("payload", objectMapper.writeValueAsString(message))).withStreamKey(STREAM));
			job.markPublished();
		});
	}

	// 앞 묶음에서 넘겨받은 글(carryFromMs 이후)을 앞에 붙인 구간 목록.
	List<Sentence> sentencesFor(RecordingPart part, List<RecordingPart> all) {
		List<Sentence> result = new ArrayList<>();
		RecordingPart previous = all.stream()
			.filter(p -> p.getPartNo() == part.getPartNo() - 1)
			.findFirst()
			.orElse(null);
		if (previous != null && previous.getStatus() == RecordingStatus.DONE && previous.getCarryFromMs() != null) {
			long from = previous.getCarryFromMs();
			all.stream()
				.filter(p -> p.getPartNo() < part.getPartNo())
				.flatMap(p -> read(p).stream())
				.filter(s -> s.startMs() >= from)
				.sorted(Comparator.comparing(Sentence::startMs))
				.forEach(result::add);
		}
		result.addAll(read(part));
		return result;
	}

	private List<Sentence> read(RecordingPart part) {
		if (part.getSentences() == null) {
			return List.of();
		}
		return objectMapper.readValue(part.getSentences(), new TypeReference<List<Sentence>>() {
		});
	}

}
