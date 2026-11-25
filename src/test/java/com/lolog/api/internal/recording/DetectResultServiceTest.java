package com.lolog.api.internal.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.BDDMockito.given;

import com.lolog.api.domain.audio.service.CardAudioClipper;
import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.recording.repository.RecordingPartRepository;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.domain.recording.service.DetectPublisher;
import com.lolog.api.domain.recording.service.RecordingService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.internal.recording.WorkerDtos.DetectedCard;
import com.lolog.api.internal.recording.WorkerDtos.DetectedLine;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("DetectResultService")
class DetectResultServiceTest {

	private static final long RECORDING_ID = 1L;

	@Mock
	private RecordingRepository recordings;

	@Mock
	private RecordingPartRepository parts;

	@Mock
	private JobRepository jobs;

	@Mock
	private CardRepository cards;

	@Mock
	private RecordingService recordingService;

	@Mock
	private DetectPublisher publisher;

	@Mock
	private TransactionTemplate tx;

	@Mock
	private PushService push;

	@Mock
	private CardAudioClipper audioClipper;

	private DetectResultService service;

	@BeforeEach
	void setUp() {
		service = new DetectResultService(recordings, parts, jobs, cards, recordingService, publisher,
				new ObjectMapper(), tx, push, audioClipper, 70);
	}

	@Test
	@DisplayName("정상 카드는 통과한다")
	void 정상_카드는_통과한다() {
		// given
		DetectedCard card = card(1000, 2000, List.of("화자1", "화자2"),
				List.of(line(1, "화자1"), line(2, "화자2"), line(3, "화자1")));

		// when & then
		assertThatCode(() -> service.validate(List.of(card))).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("끝이 시작보다 1ms 뒤면 통과한다")
	void 끝이_시작보다_1ms_뒤면_통과한다() {
		// when & then
		assertThatCode(() -> service.validate(List.of(card(1000, 1001)))).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("끝이 시작과 같으면 그 카드의 endMs가 틀린 칸이다")
	void 끝이_시작과_같으면_그_카드의_endMs가_틀린_칸이다() {
		// when & then
		assertInvalid(List.of(card(0, 1000), card(1000, 1000)), "cards[1].endMs");
	}

	@Test
	@DisplayName("끝이 시작보다 앞이면 endMs가 틀린 칸이다")
	void 끝이_시작보다_앞이면_endMs가_틀린_칸이다() {
		// when & then
		assertInvalid(List.of(card(1000, 999)), "cards[0].endMs");
	}

	@Test
	@DisplayName("대사 번호가 건너뛰면 그 대사의 lineNo가 틀린 칸이다")
	void 대사_번호가_건너뛰면_그_대사의_lineNo가_틀린_칸이다() {
		// given
		DetectedCard card = card(0, 1000, List.of("화자1"), List.of(line(1, "화자1"), line(3, "화자1")));

		// when & then
		assertInvalid(List.of(card), "cards[0].lines[1].lineNo");
	}

	@Test
	@DisplayName("대사 번호가 0부터면 첫 대사의 lineNo가 틀린 칸이다")
	void 대사_번호가_0부터면_첫_대사의_lineNo가_틀린_칸이다() {
		// given
		DetectedCard card = card(0, 1000, List.of("화자1"), List.of(line(0, "화자1")));

		// when & then
		assertInvalid(List.of(card), "cards[0].lines[0].lineNo");
	}

	@Test
	@DisplayName("두번째 화자가 화자3이면 speakers 1이 틀린 칸이다")
	void 두번째_화자가_화자3이면_speakers_1이_틀린_칸이다() {
		// given
		DetectedCard card = card(0, 1000, List.of("화자1", "화자3"), List.of(line(1, "화자1")));

		// when & then
		assertInvalid(List.of(card), "cards[0].speakers[1]");
	}

	@Test
	@DisplayName("대사의 화자가 카드 화자에 없으면 그 대사의 speaker가 틀린 칸이다")
	void 대사의_화자가_카드_화자에_없으면_그_대사의_speaker가_틀린_칸이다() {
		// given
		DetectedCard card = card(0, 1000, List.of("화자1"), List.of(line(1, "화자1"), line(2, "화자2")));

		// when & then
		assertInvalid(List.of(card), "cards[0].lines[1].speaker");
	}

	@Test
	@DisplayName("빈 카드 목록은 통과한다")
	void 빈_카드_목록은_통과한다() {
		// when & then
		assertThatCode(() -> service.validate(List.of())).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("앞 카드 끝과 새 카드 시작이 맞닿으면 겹치지 않아 남긴다")
	void 앞_카드_끝과_새_카드_시작이_맞닿으면_겹치지_않아_남긴다() {
		// given
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of(existing(1000, 2000)));
		DetectedCard touching = card(2000, 3000);

		// when & then
		assertThat(service.fresh(RECORDING_ID, List.of(touching))).containsExactly(touching);
	}

	@Test
	@DisplayName("새 카드가 앞 카드 끝보다 1ms 먼저 시작하면 겹쳐서 버린다")
	void 새_카드가_앞_카드_끝보다_1ms_먼저_시작하면_겹쳐서_버린다() {
		// given
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of(existing(1000, 2000)));

		// when & then
		assertThat(service.fresh(RECORDING_ID, List.of(card(1999, 3000)))).isEmpty();
	}

	@Test
	@DisplayName("새 카드 끝이 앞 카드 시작과 맞닿으면 남긴다")
	void 새_카드_끝이_앞_카드_시작과_맞닿으면_남긴다() {
		// given
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of(existing(1000, 2000)));
		DetectedCard before = card(0, 1000);

		// when & then
		assertThat(service.fresh(RECORDING_ID, List.of(before))).containsExactly(before);
	}

	@Test
	@DisplayName("앞 카드 안에 들어가거나 감싸면 버리고 나머지는 순서대로 남긴다")
	void 앞_카드_안에_들어가거나_감싸면_버리고_나머지는_순서대로_남긴다() {
		// given
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of(existing(1000, 2000)));
		DetectedCard inside = card(1200, 1800);
		DetectedCard covering = card(500, 2500);
		DetectedCard later = card(3000, 4000);
		DetectedCard earlier = card(0, 500);

		// when & then
		assertThat(service.fresh(RECORDING_ID, List.of(inside, later, covering, earlier)))
			.containsExactly(later, earlier);
	}

	@Test
	@DisplayName("기존 카드가 없으면 모두 남긴다")
	void 기존_카드가_없으면_모두_남긴다() {
		// given
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of());
		DetectedCard a = card(0, 1000);
		DetectedCard b = card(500, 1500);

		// when & then
		// 이번 결과 안의 카드끼리는 비교하지 않는다.
		assertThat(service.fresh(RECORDING_ID, List.of(a, b))).containsExactly(a, b);
	}

	private void assertInvalid(List<DetectedCard> detected, String field) {
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.validate(detected));
		assertThat(e).isNotNull();
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo(field);
	}

	private static Card existing(long startMs, long endMs) {
		return new Card(RECORDING_ID, 9L, "웃김", "요약", startMs, endMs, OffsetDateTime.now());
	}

	private static DetectedCard card(long startMs, long endMs) {
		return card(startMs, endMs, List.of("화자1"), List.of(line(1, "화자1")));
	}

	private static DetectedCard card(long startMs, long endMs, List<String> speakers, List<DetectedLine> lines) {
		return new DetectedCard("웃김", "요약", startMs, endMs, speakers, lines);
	}

	private static DetectedLine line(int lineNo, String speaker) {
		return new DetectedLine(lineNo, speaker, "대사", null, null);
	}

}
