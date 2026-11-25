package com.lolog.api.domain.recording.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.recording.entity.Recording;
import com.lolog.api.domain.recording.entity.RecordingPart;
import com.lolog.api.domain.recording.repository.RecordingPartRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("DetectPublisher")
class DetectPublisherMockTest {

	private static final long JOB_ID = 9L;

	@Mock
	private JobRepository jobs;

	@Mock
	private RecordingPartRepository parts;

	@Mock
	private StringRedisTemplate redis;

	@Mock
	private StreamOperations<String, Object, Object> stream;

	@Mock
	private PlatformTransactionManager transactionManager;

	private DetectPublisher publisher;

	@BeforeEach
	void setUp() {
		publisher = new DetectPublisher(jobs, parts, redis, new ObjectMapper(), transactionManager);
	}

	@Test
	@DisplayName("이미 큐에 넣은 작업(attempts > 0)이면 Redis를 부르지 않는다")
	void 이미_넣은_작업은_다시_넣지_않는다() {
		// given
		Job job = job();
		job.markPublished();
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(job));

		// when
		publisher.publishQuietly(JOB_ID);

		// then
		assertThat(job.getAttempts()).isEqualTo(1);
		verifyNoInteractions(redis, parts);
	}

	@Test
	@DisplayName("처음 넣는 작업이면 스트림에 넣고 넣은 횟수를 1로 올린다")
	void 처음_넣으면_스트림에_넣고_표시한다() {
		// given
		Job job = job();
		RecordingPart part = part();
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(job));
		given(parts.findById(5L)).willReturn(Optional.of(part));
		given(parts.findByRecordingIdOrderByPartNo(3L)).willReturn(List.of(part));
		given(redis.opsForStream()).willReturn(stream);

		// when
		publisher.publishQuietly(JOB_ID);

		// then
		verify(stream).add(any(MapRecord.class));
		assertThat(job.getAttempts()).isEqualTo(1);
	}

	@Test
	@DisplayName("Redis가 실패해도 예외를 넘기지 않고 넣은 횟수도 올리지 않는다")
	void Redis가_실패해도_예외를_넘기지_않는다() {
		// given
		Job job = job();
		RecordingPart part = part();
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(job));
		given(parts.findById(5L)).willReturn(Optional.of(part));
		given(parts.findByRecordingIdOrderByPartNo(3L)).willReturn(List.of(part));
		given(redis.opsForStream()).willThrow(new IllegalStateException("Redis 연결 끊김"));

		// when & then
		assertThatCode(() -> publisher.publishQuietly(JOB_ID)).doesNotThrowAnyException();
		assertThat(job.getAttempts()).isZero();
	}

	private static Job job() {
		Job job = new Job(5L, 1L);
		ReflectionTestUtils.setField(job, "id", JOB_ID);
		return job;
	}

	private static RecordingPart part() {
		Recording recording = new Recording(1L, "c", OffsetDateTime.now(), false);
		ReflectionTestUtils.setField(recording, "id", 3L);
		RecordingPart part = new RecordingPart(recording, 1, true, 60, "[{\"startMs\":0,\"endMs\":1,\"text\":\"글\"}]");
		ReflectionTestUtils.setField(part, "id", 5L);
		return part;
	}

}
