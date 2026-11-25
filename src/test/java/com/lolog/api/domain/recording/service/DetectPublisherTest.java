package com.lolog.api.domain.recording.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.recording.dto.RecordingDtos.Sentence;
import com.lolog.api.domain.recording.entity.RecordingPart;
import com.lolog.api.domain.recording.entity.RecordingStatus;
import com.lolog.api.domain.recording.repository.RecordingPartRepository;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("DetectPublisher")
class DetectPublisherTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private JobRepository jobs;

	@Mock
	private RecordingPartRepository parts;

	@Mock
	private StringRedisTemplate redis;

	@Mock
	private PlatformTransactionManager transactionManager;

	private DetectPublisher publisher;

	@BeforeEach
	void setUp() {
		publisher = new DetectPublisher(jobs, parts, redis, objectMapper, transactionManager);
	}

	@Test
	@DisplayName("앞 묶음이 DONE이고 넘긴 시각이 있으면 넘긴 글을 앞에 붙인다")
	void 앞_묶음이_DONE이고_넘긴_시각이_있으면_넘긴_글을_앞에_붙인다() {
		// given
		RecordingPart first = part(1, RecordingStatus.DONE, 1500L, s(1000), s(1500), s(1800));
		RecordingPart second = part(2, RecordingStatus.RECEIVED, null, s(2000), s(2500));

		// when & then
		assertThat(starts(publisher.sentencesFor(second, List.of(first, second))))
			.containsExactly(1500L, 1800L, 2000L, 2500L);
	}

	@Test
	@DisplayName("앞 묶음이 DONE이 아니면 넘긴 시각이 있어도 붙이지 않는다")
	void 앞_묶음이_DONE이_아니면_넘긴_시각이_있어도_붙이지_않는다() {
		// given
		RecordingPart first = part(1, RecordingStatus.FAILED, 1500L, s(1500));
		RecordingPart second = part(2, RecordingStatus.RECEIVED, null, s(2000));

		// when & then
		assertThat(starts(publisher.sentencesFor(second, List.of(first, second)))).containsExactly(2000L);
	}

	@Test
	@DisplayName("앞 묶음이 DONE이어도 넘긴 시각이 없으면 붙이지 않는다")
	void 앞_묶음이_DONE이어도_넘긴_시각이_없으면_붙이지_않는다() {
		// given
		RecordingPart first = part(1, RecordingStatus.DONE, null, s(1500));
		RecordingPart second = part(2, RecordingStatus.RECEIVED, null, s(2000));

		// when & then
		assertThat(starts(publisher.sentencesFor(second, List.of(first, second)))).containsExactly(2000L);
	}

	@Test
	@DisplayName("바로 앞 묶음만 보고 두 칸 앞 묶음의 넘긴 시각은 보지 않는다")
	void 바로_앞_묶음만_보고_두_칸_앞_묶음의_넘긴_시각은_보지_않는다() {
		// given
		RecordingPart first = part(1, RecordingStatus.DONE, 500L, s(500));
		RecordingPart second = part(2, RecordingStatus.DONE, null, (String) null);
		RecordingPart third = part(3, RecordingStatus.RECEIVED, null, s(3000));

		// when & then
		assertThat(starts(publisher.sentencesFor(third, List.of(first, second, third)))).containsExactly(3000L);
	}

	@Test
	@DisplayName("첫 묶음은 자기 글만 돌려준다")
	void 첫_묶음은_자기_글만_돌려준다() {
		// given
		RecordingPart first = part(1, RecordingStatus.RECEIVED, null, s(0), s(100));

		// when & then
		assertThat(starts(publisher.sentencesFor(first, List.of(first)))).containsExactly(0L, 100L);
	}

	@Test
	@DisplayName("넘긴 글도 자기 글도 없으면 빈 목록이다")
	void 넘긴_글도_자기_글도_없으면_빈_목록이다() {
		// given
		RecordingPart first = part(1, RecordingStatus.DONE, null, (String) null);
		RecordingPart second = part(2, RecordingStatus.RECEIVED, null, (String) null);

		// when & then
		assertThat(publisher.sentencesFor(second, List.of(first, second))).isEmpty();
	}

	@Test
	@DisplayName("넘긴 시각이 없으면 빈 목록이다")
	void 넘긴_시각이_없으면_빈_목록이다() {
		// given
		RecordingPart first = part(1, RecordingStatus.DONE, null, s(1000));

		// when & then
		assertThat(publisher.carriedFrom(first, List.of(first))).isEmpty();
	}

	@Test
	@DisplayName("넘긴 시각과 같은 글부터 넣고 1ms 앞선 글은 뺀다")
	void 넘긴_시각과_같은_글부터_넣고_1ms_앞선_글은_뺀다() {
		// given
		RecordingPart first = part(1, RecordingStatus.DONE, 1500L, s(1499), s(1500), s(1501));

		// when & then
		assertThat(starts(publisher.carriedFrom(first, List.of(first)))).containsExactly(1500L, 1501L);
	}

	@Test
	@DisplayName("그 묶음까지의 앞 묶음 글도 모아 시각 순으로 돌려준다")
	void 그_묶음까지의_앞_묶음_글도_모아_시각_순으로_돌려준다() {
		// given
		// 넘겨 둔 이야기는 앞 묶음에서 시작했을 수 있다.
		RecordingPart first = part(1, RecordingStatus.DONE, null, s(900), s(1200));
		RecordingPart second = part(2, RecordingStatus.DONE, 1000L, s(2500), s(2000));
		RecordingPart third = part(3, RecordingStatus.RECEIVED, null, s(3000));

		// when & then
		assertThat(starts(publisher.carriedFrom(second, List.of(third, second, first))))
			.containsExactly(1200L, 2000L, 2500L);
	}

	@Test
	@DisplayName("글이 null인 묶음은 건너뛴다")
	void 글이_null인_묶음은_건너뛴다() {
		// given
		RecordingPart first = part(1, RecordingStatus.DONE, null, (String) null);
		RecordingPart second = part(2, RecordingStatus.DONE, 0L, s(2000));

		// when & then
		assertThat(starts(publisher.carriedFrom(second, List.of(first, second)))).containsExactly(2000L);
	}

	@Test
	@DisplayName("글이 null이면 빈 목록으로 읽는다")
	void 글이_null이면_빈_목록으로_읽는다() {
		// when & then
		assertThat(publisher.read(part(1, RecordingStatus.DONE, null, (String) null))).isEmpty();
	}

	@Test
	@DisplayName("저장된 JSON을 문장 목록으로 읽는다")
	void 저장된_JSON을_문장_목록으로_읽는다() {
		// given
		RecordingPart part = part(1, RecordingStatus.RECEIVED, null, s(0), s(100));

		// when & then
		assertThat(publisher.read(part)).containsExactly(new Sentence(0L, 1L, "글0"), new Sentence(100L, 101L, "글100"));
	}

	private RecordingPart part(int partNo, RecordingStatus status, Long carryFromMs, Sentence... sentences) {
		return part(partNo, status, carryFromMs, objectMapper.writeValueAsString(Arrays.asList(sentences)));
	}

	private static RecordingPart part(int partNo, RecordingStatus status, Long carryFromMs, String json) {
		RecordingPart part = new RecordingPart(null, partNo, false, 60, json);
		part.setStatus(status);
		part.setCarryFromMs(carryFromMs);
		return part;
	}

	private static Sentence s(long startMs) {
		return new Sentence(startMs, startMs + 1, "글" + startMs);
	}

	private static List<Long> starts(List<Sentence> sentences) {
		return sentences.stream().map(Sentence::startMs).toList();
	}

}
