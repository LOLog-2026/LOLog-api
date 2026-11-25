package com.lolog.api.internal.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.audio.service.CardAudioClipper;
import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.recording.dto.RecordingDtos.Sentence;
import com.lolog.api.domain.recording.entity.Recording;
import com.lolog.api.domain.recording.entity.RecordingPart;
import com.lolog.api.domain.recording.entity.RecordingStatus;
import com.lolog.api.domain.recording.repository.RecordingPartRepository;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.domain.recording.service.DetectPublisher;
import com.lolog.api.domain.recording.service.RecordingService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.internal.recording.WorkerDtos.DetectResult;
import com.lolog.api.internal.recording.WorkerDtos.DetectedCard;
import com.lolog.api.internal.recording.WorkerDtos.DetectedLine;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// DetectResultServiceTest가 validate·fresh 경계를 보므로, 여기서는 receive·fail·늦은 묶음의 흐름과 협력 객체 호출을 본다.
@ExtendWith(MockitoExtension.class)
@DisplayName("DetectResultServiceMock")
class DetectResultServiceMockTest {

	private static final long USER_ID = 1L;
	private static final long RECORDING_ID = 5L;
	private static final long PART_ID = 50L;
	private static final long JOB_ID = 500L;
	private static final long NEXT_JOB_ID = 501L;
	private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-11-01T10:00:00+09:00");

	private final ObjectMapper objectMapper = new ObjectMapper();

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

	private Recording recording;

	private RecordingPart part;

	private Job job;

	@BeforeEach
	void setUp() {
		// 트랜잭션 템플릿은 받은 콜백을 바로 실행한다.
		lenient().when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
		service = new DetectResultService(recordings, parts, jobs, cards, recordingService, publisher, objectMapper, tx,
				push, audioClipper, 70);
		recording = recording(true);
		part = part(recording, PART_ID, 1, false);
		job = new Job(PART_ID, USER_ID);
		ReflectionTestUtils.setField(job, "id", JOB_ID);
	}

	// ---- receive: 거절 ----

	@Test
	@DisplayName("녹음이 없으면 RECORDING_NOT_FOUND이고 작업을 읽지 않는다")
	void 녹음이_없으면_RECORDING_NOT_FOUND() {
		// given
		given(recordings.findByIdForUpdate(RECORDING_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.receive(RECORDING_ID, result(1, List.of(), null)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECORDING_NOT_FOUND);
		verifyNoInteractions(jobs, publisher);
	}

	@Test
	@DisplayName("작업이 없으면 field가 jobId다")
	void 작업이_없으면_jobId() {
		// given
		given(recordings.findByIdForUpdate(RECORDING_ID)).willReturn(Optional.of(recording));
		given(jobs.findById(JOB_ID)).willReturn(Optional.empty());

		// when & then
		assertInvalid(() -> service.receive(RECORDING_ID, result(1, List.of(), null)), "jobId");
	}

	@Test
	@DisplayName("감지 작업이 아니면 field가 jobId다")
	void 감지_작업이_아니면_jobId() {
		// given
		Job comic = Job.comic(9L, USER_ID);
		ReflectionTestUtils.setField(comic, "id", JOB_ID);
		given(recordings.findByIdForUpdate(RECORDING_ID)).willReturn(Optional.of(recording));
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(comic));

		// when & then
		assertInvalid(() -> service.receive(RECORDING_ID, result(1, List.of(), null)), "jobId");
		verifyNoInteractions(parts);
	}

	@Test
	@DisplayName("다른 녹음의 묶음 작업이면 field가 jobId다")
	void 다른_녹음의_작업이면_jobId() {
		// given
		Recording other = new Recording(USER_ID, "o", STARTED_AT, false);
		ReflectionTestUtils.setField(other, "id", 6L);
		given(recordings.findByIdForUpdate(RECORDING_ID)).willReturn(Optional.of(recording));
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(job));
		given(parts.findById(PART_ID)).willReturn(Optional.of(part(other, PART_ID, 1, false)));

		// when & then
		assertInvalid(() -> service.receive(RECORDING_ID, result(1, List.of(), null)), "jobId");
	}

	@Test
	@DisplayName("묶음 번호가 작업의 묶음과 다르면 field가 partNo다")
	void 묶음_번호가_다르면_partNo() {
		// given
		givenReceive();

		// when & then
		assertInvalid(() -> service.receive(RECORDING_ID, result(2, List.of(), null)), "partNo");
	}

	@Test
	@DisplayName("이미 끝난 작업의 결과가 다시 오면 저장·푸시·다음 묶음 넣기를 하지 않는다")
	void 끝난_작업이면_아무것도_하지_않는다() {
		// given
		job.complete();
		givenReceive();

		// when
		service.receive(RECORDING_ID, result(1, List.of(detected(0, 1000)), null));

		// then
		verifyNoInteractions(cards, push, audioClipper, recordingService, publisher);
		assertThat(part.getStatus()).isEqualTo(RecordingStatus.RECEIVED);
	}

	// ---- receive: 저장 ----

	@Test
	@DisplayName("새 카드를 저장하고 묶음마다 한 번 푸시를 보내고, 음성 저장 녹음이면 구간 음성을 자르게 한다")
	void 새_카드를_저장하고_알린다() {
		// given
		givenReceive();
		givenAfterSave(List.of(NEXT_JOB_ID));
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of());

		// when
		service.receive(RECORDING_ID, result(1, List.of(detected(0, 1000), detected(2000, 3000)), null));

		// then
		ArgumentCaptor<Card> saved = ArgumentCaptor.forClass(Card.class);
		verify(cards, times(2)).save(saved.capture());
		Card first = saved.getAllValues().getFirst();
		assertThat(first.getRecordingId()).isEqualTo(RECORDING_ID);
		assertThat(first.getUserId()).isEqualTo(USER_ID);
		assertThat(first.getRecordedAt()).isEqualTo(STARTED_AT);
		assertThat(saved.getAllValues().get(1).getRecordedAt()).isEqualTo(STARTED_AT.plusSeconds(2));
		assertThat(first.getSpeakers()).extracting("speakerKey").containsExactly("화자1");
		assertThat(first.getLines()).extracting("text").containsExactly("하하");
		verify(push).notify(USER_ID, PushService.cardsCreated(RECORDING_ID, 2));
		verify(audioClipper).clipRecordingLater(RECORDING_ID);
		assertThat(job.getStatus()).isEqualTo("DONE");
		assertThat(part.getStatus()).isEqualTo(RecordingStatus.DONE);
	}

	@Test
	@DisplayName("음성 저장을 끈 녹음이면 푸시는 보내도 구간 음성은 자르지 않는다")
	void 음성_저장을_껐으면_자르지_않는다() {
		// given
		recording = recording(false);
		part = part(recording, PART_ID, 1, false);
		givenReceive();
		givenAfterSave(List.of());
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of());

		// when
		service.receive(RECORDING_ID, result(1, List.of(detected(0, 1000)), null));

		// then
		verify(push).notify(USER_ID, PushService.cardsCreated(RECORDING_ID, 1));
		verifyNoInteractions(audioClipper);
	}

	@Test
	@DisplayName("감지한 카드가 모두 앞 카드와 겹쳐 버려지면 저장·푸시·자르기를 하지 않고 작업은 끝낸다")
	void 모두_버려지면_알리지_않는다() {
		// given
		givenReceive();
		givenAfterSave(List.of());
		Card existing = new Card(RECORDING_ID, USER_ID, "웃김", "앞", 0, 5000, STARTED_AT);
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of(existing));

		// when
		service.receive(RECORDING_ID, result(1, List.of(detected(1000, 2000)), null));

		// then
		verify(cards, never()).save(any());
		verifyNoInteractions(push, audioClipper);
		assertThat(job.getStatus()).isEqualTo("DONE");
	}

	@Test
	@DisplayName("carryFromMs가 오면 묶음에 넘김 시각을 적고 그 뒤 문장만 남긴다")
	void 넘김_시각_뒤_문장만_남긴다() {
		// given
		givenReceive();
		givenAfterSave(List.of());
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of());
		given(publisher.read(part)).willReturn(List.of(sentence(0, 1000, "앞"), sentence(4000, 5000, "뒤")));

		// when
		service.receive(RECORDING_ID, result(1, List.of(), 4000L));

		// then
		assertThat(part.getCarryFromMs()).isEqualTo(4000L);
		assertThat(objectMapper.readValue(part.getSentences(), Sentence[].class))
			.containsExactly(sentence(4000, 5000, "뒤"));
	}

	@Test
	@DisplayName("넘김 시각 뒤에 남는 문장이 없으면 묶음의 문장을 비운다")
	void 남는_문장이_없으면_비운다() {
		// given
		givenReceive();
		givenAfterSave(List.of());
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of());
		given(publisher.read(part)).willReturn(List.of(sentence(0, 1000, "앞")));

		// when
		service.receive(RECORDING_ID, result(1, List.of(), 4000L));

		// then
		assertThat(part.getCarryFromMs()).isEqualTo(4000L);
		assertThat(part.getSentences()).isNull();
	}

	@Test
	@DisplayName("마지막 묶음이면 carryFromMs가 와도 넘기지 않고 문장을 모두 지운다")
	void 마지막_묶음이면_넘기지_않는다() {
		// given
		part = part(recording, PART_ID, 1, true);
		givenReceive();
		givenAfterSave(List.of());
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of());

		// when
		service.receive(RECORDING_ID, result(1, List.of(), 4000L));

		// then
		assertThat(part.getCarryFromMs()).isNull();
		assertThat(part.getSentences()).isNull();
		verify(publisher, never()).read(any());
	}

	@Test
	@DisplayName("넘겨 둔 부분만 처리한 작업이면 carryFromMs가 와도 넘기지 않는다")
	void 넘긴_부분만_처리했으면_넘기지_않는다() {
		// given
		givenReceive();
		given(jobs.existsByRecordingPartIdAndStatusAndIdNot(PART_ID, "DONE", JOB_ID)).willReturn(true);
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(List.of(part));
		given(recordingService.dispatch(recording)).willReturn(List.of());
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of());

		// when
		service.receive(RECORDING_ID, result(1, List.of(), 4000L));

		// then
		assertThat(part.getCarryFromMs()).isNull();
		assertThat(part.getSentences()).isNull();
	}

	@Test
	@DisplayName("뒤 묶음과 이미 비운 묶음의 문장은 건드리지 않는다")
	void 뒤_묶음은_건드리지_않는다() {
		// given
		RecordingPart emptied = part(recording, 49L, 0, false);
		emptied.setSentences(null);
		part = part(recording, PART_ID, 1, false);
		RecordingPart later = part(recording, 51L, 2, false);
		givenReceive();
		given(jobs.existsByRecordingPartIdAndStatusAndIdNot(PART_ID, "DONE", JOB_ID)).willReturn(false);
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(List.of(emptied, part, later));
		given(recordingService.dispatch(recording)).willReturn(List.of());
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of());

		// when
		service.receive(RECORDING_ID, result(1, List.of(), null));

		// then
		assertThat(part.getSentences()).isNull();
		assertThat(later.getSentences()).isEqualTo("[]");
		assertThat(emptied.getSentences()).isNull();
	}

	@Test
	@DisplayName("결과를 저장한 뒤 녹음 서비스가 고른 다음 작업을 큐에 넣는다")
	void 저장한_뒤_다음_작업을_넣는다() {
		// given
		givenReceive();
		givenAfterSave(List.of(NEXT_JOB_ID));
		given(cards.findByRecordingIdOrderById(RECORDING_ID)).willReturn(List.of());

		// when
		service.receive(RECORDING_ID, result(1, List.of(), null));

		// then
		InOrder order = inOrder(recordingService, publisher);
		order.verify(recordingService).dispatch(recording);
		order.verify(publisher).publishQuietly(NEXT_JOB_ID);
	}

	// ---- fail ----

	@Test
	@DisplayName("감지 작업이 아닌 jobId의 실패면 field 없는 INVALID_REQUEST다")
	void 감지_작업이_아니면_field_없음() {
		// given
		given(jobs.findRecordingIdByJobId(JOB_ID)).willReturn(Optional.empty());

		// when & then
		assertInvalid(() -> service.fail(JOB_ID, "x"), null);
		verifyNoInteractions(recordings, recordingService, publisher);
	}

	@Test
	@DisplayName("이미 끝난 작업의 실패가 다시 오면 아무것도 바꾸지 않는다")
	void 끝난_작업의_실패는_무시한다() {
		// given
		job.complete();
		givenFail();

		// when
		service.fail(JOB_ID, "x");

		// then
		assertThat(job.getStatus()).isEqualTo("DONE");
		verify(parts, never()).findById(anyLong());
		verifyNoInteractions(recordingService, publisher);
	}

	@Test
	@DisplayName("실패면 작업·묶음을 FAILED로 바꾸고 문장과 넘김 시각을 지운 뒤 다음 작업을 넣는다")
	void 실패면_묶음을_FAILED로_바꾼다() {
		// given
		part.setCarryFromMs(3000L);
		givenFail();
		given(jobs.existsByRecordingPartIdAndStatusAndIdNot(PART_ID, "DONE", JOB_ID)).willReturn(false);
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(List.of(part));
		given(recordingService.dispatch(recording)).willReturn(List.of(NEXT_JOB_ID));

		// when
		service.fail(JOB_ID, "모델 오류");

		// then
		assertThat(job.getStatus()).isEqualTo("FAILED");
		assertThat(job.getFailReason()).isEqualTo("모델 오류");
		assertThat(part.getStatus()).isEqualTo(RecordingStatus.FAILED);
		assertThat(part.getCarryFromMs()).isNull();
		assertThat(part.getSentences()).isNull();
		verify(publisher).publishQuietly(NEXT_JOB_ID);
		verifyNoInteractions(push, cards);
	}

	@Test
	@DisplayName("넘겨 둔 부분만 처리하던 작업이 실패하면 묶음은 DONE으로 둔다")
	void 넘긴_부분만_실패하면_묶음은_DONE() {
		// given
		givenFail();
		given(jobs.existsByRecordingPartIdAndStatusAndIdNot(PART_ID, "DONE", JOB_ID)).willReturn(true);
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(List.of(part));
		given(recordingService.dispatch(recording)).willReturn(List.of());

		// when
		service.fail(JOB_ID, "x");

		// then
		assertThat(job.getStatus()).isEqualTo("FAILED");
		assertThat(part.getStatus()).isEqualTo(RecordingStatus.DONE);
	}

	// ---- dispatchLateCarries ----

	@Test
	@DisplayName("다음 묶음을 오래 기다린 묶음은 넘겨 둔 부분만 감지하도록 새 작업을 넣는다")
	void 오래_기다린_묶음은_새_작업을_넣는다() {
		// given
		OffsetDateTime before = OffsetDateTime.now();
		givenWaiting(before.minusMinutes(1));
		given(publisher.carriedFrom(part, List.of(part))).willReturn(List.of(sentence(4000, 5000, "뒤")));
		given(jobs.save(any())).willAnswer(inv -> {
			Job j = inv.getArgument(0);
			ReflectionTestUtils.setField(j, "id", NEXT_JOB_ID);
			return j;
		});

		// when
		service.dispatchLateCarries(before);

		// then
		assertThat(part.getStatus()).isEqualTo(RecordingStatus.DETECTING);
		assertThat(recording.getStatus()).isEqualTo(RecordingStatus.DETECTING);
		ArgumentCaptor<Job> saved = ArgumentCaptor.forClass(Job.class);
		verify(jobs).save(saved.capture());
		assertThat(saved.getValue().getRecordingPartId()).isEqualTo(PART_ID);
		assertThat(saved.getValue().getUserId()).isEqualTo(USER_ID);
		verify(publisher).publishQuietly(NEXT_JOB_ID);
	}

	@Test
	@DisplayName("넘겨 둔 시각 뒤에 남은 문장이 없으면 넘김 표시만 지우고 작업을 넣지 않는다")
	void 남은_문장이_없으면_넘김만_지운다() {
		// given
		OffsetDateTime before = OffsetDateTime.now();
		givenWaiting(before.minusMinutes(1));
		given(publisher.carriedFrom(part, List.of(part))).willReturn(List.of());

		// when
		service.dispatchLateCarries(before);

		// then
		assertThat(part.getCarryFromMs()).isNull();
		assertThat(part.getStatus()).isEqualTo(RecordingStatus.DONE);
		verify(jobs, never()).save(any());
		verify(publisher, never()).publishQuietly(any());
	}

	@Test
	@DisplayName("마지막 묶음이 기준 시각 뒤에 만들어졌으면 기다리는 중으로 보지 않는다")
	void 기준_뒤에_만든_묶음은_그대로_둔다() {
		// given
		OffsetDateTime before = OffsetDateTime.now();
		givenWaiting(before.plusMinutes(1));

		// when
		service.dispatchLateCarries(before);

		// then
		assertThat(part.getCarryFromMs()).isEqualTo(4000L);
		verify(publisher, never()).carriedFrom(any(), any());
		verify(jobs, never()).save(any());
	}

	@Test
	@DisplayName("마지막 묶음에 넘김 시각이 없으면 아무것도 하지 않는다")
	void 넘김_시각이_없으면_그대로_둔다() {
		// given
		OffsetDateTime before = OffsetDateTime.now();
		givenWaiting(before.minusMinutes(1));
		part.setCarryFromMs(null);

		// when
		service.dispatchLateCarries(before);

		// then
		verify(publisher, never()).carriedFrom(any(), any());
		verify(jobs, never()).save(any());
	}

	@Test
	@DisplayName("같은 녹음 ID가 여러 번 와도 한 번만 처리한다")
	void 같은_녹음은_한_번만_처리한다() {
		// given
		OffsetDateTime before = OffsetDateTime.now();
		givenWaiting(before.minusMinutes(1));
		given(parts.findRecordingIdsWaitingTooLong(before)).willReturn(List.of(RECORDING_ID, RECORDING_ID));
		given(publisher.carriedFrom(part, List.of(part))).willReturn(List.of());

		// when
		service.dispatchLateCarries(before);

		// then
		verify(recordings, times(1)).findByIdForUpdate(RECORDING_ID);
	}

	private void givenReceive() {
		given(recordings.findByIdForUpdate(RECORDING_ID)).willReturn(Optional.of(recording));
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(job));
		given(parts.findById(PART_ID)).willReturn(Optional.of(part));
	}

	private void givenAfterSave(List<Long> next) {
		given(jobs.existsByRecordingPartIdAndStatusAndIdNot(PART_ID, "DONE", JOB_ID)).willReturn(false);
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(List.of(part));
		given(recordingService.dispatch(recording)).willReturn(next);
	}

	private void givenFail() {
		given(jobs.findRecordingIdByJobId(JOB_ID)).willReturn(Optional.of(RECORDING_ID));
		given(recordings.findByIdForUpdate(RECORDING_ID)).willReturn(Optional.of(recording));
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(job));
		lenient().when(parts.findById(PART_ID)).thenReturn(Optional.of(part));
	}

	// 마지막 묶음이 DONE이고 4000ms부터 넘겨 둔 채 createdAt에 만들어진 상태
	private void givenWaiting(OffsetDateTime createdAt) {
		part.setStatus(RecordingStatus.DONE);
		part.setCarryFromMs(4000L);
		ReflectionTestUtils.setField(part, "createdAt", createdAt);
		lenient().when(parts.findRecordingIdsWaitingTooLong(any())).thenReturn(List.of(RECORDING_ID));
		given(recordings.findByIdForUpdate(RECORDING_ID)).willReturn(Optional.of(recording));
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(List.of(part));
	}

	private void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String field) {
		ApiException e = catchThrowableOfType(ApiException.class, call);
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo(field);
	}

	private static Recording recording(boolean audioSaved) {
		Recording recording = new Recording(USER_ID, "c", STARTED_AT, audioSaved);
		ReflectionTestUtils.setField(recording, "id", RECORDING_ID);
		return recording;
	}

	private static RecordingPart part(Recording recording, long id, int partNo, boolean last) {
		RecordingPart part = new RecordingPart(recording, partNo, last, 60, "[]");
		ReflectionTestUtils.setField(part, "id", id);
		return part;
	}

	private static DetectResult result(int partNo, List<DetectedCard> cards, Long carryFromMs) {
		return new DetectResult(JOB_ID, partNo, cards, carryFromMs);
	}

	private static DetectedCard detected(long startMs, long endMs) {
		return new DetectedCard("웃김", "요약", startMs, endMs, List.of("화자1"),
				List.of(new DetectedLine(1, "화자1", "하하", startMs, endMs)));
	}

	private static Sentence sentence(long startMs, long endMs, String text) {
		return new Sentence(startMs, endMs, text);
	}

}
