package com.lolog.api.domain.recording.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.recording.dto.RecordingDtos.RecordingListItem;
import com.lolog.api.domain.recording.dto.RecordingDtos.RecordingPage;
import com.lolog.api.domain.recording.dto.RecordingDtos.Sentence;
import com.lolog.api.domain.recording.dto.RecordingDtos.StatusResponse;
import com.lolog.api.domain.recording.dto.RecordingDtos.UploadRequest;
import com.lolog.api.domain.recording.dto.RecordingDtos.UploadResponse;
import com.lolog.api.domain.recording.entity.Recording;
import com.lolog.api.domain.recording.entity.RecordingPart;
import com.lolog.api.domain.recording.entity.RecordingStatus;
import com.lolog.api.domain.recording.repository.RecordingPartRepository;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// RecordingServiceTest가 parseStatuses·validate를 보므로, 여기서는 upload·list·dispatch의 흐름과 협력 객체 호출을 본다.
@ExtendWith(MockitoExtension.class)
@DisplayName("RecordingServiceMock")
class RecordingServiceMockTest {

	private static final long USER_ID = 1L;
	private static final long RECORDING_ID = 5L;
	private static final long JOB_ID = 500L;
	private static final String CLIENT_ID = "11111111-1111-1111-1111-111111111111";
	private static final OffsetDateTime STARTED_AT = OffsetDateTime.parse("2026-11-01T10:00:00+09:00");
	private static final List<Sentence> SENTENCES = List.of(new Sentence(0L, 1000L, "안녕"));

	@Mock
	private RecordingRepository recordings;

	@Mock
	private RecordingPartRepository parts;

	@Mock
	private JobRepository jobs;

	@Mock
	private DetectPublisher publisher;

	@Mock
	private TransactionTemplate tx;

	private RecordingService service;

	private Recording recording;

	@BeforeEach
	void setUp() {
		// 트랜잭션 템플릿은 받은 콜백을 바로 실행한다.
		lenient().when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
		service = new RecordingService(recordings, parts, jobs, publisher, new ObjectMapper(), tx);
		recording = new Recording(USER_ID, CLIENT_ID, STARTED_AT, false);
		ReflectionTestUtils.setField(recording, "id", RECORDING_ID);
		lenient().when(jobs.save(any())).thenAnswer(inv -> {
			Job j = inv.getArgument(0);
			ReflectionTestUtils.setField(j, "id", JOB_ID);
			return j;
		});
	}

	// ---- upload ----

	@Test
	@DisplayName("검사에 걸리면 트랜잭션을 열지 않는다")
	void 검사에_걸리면_저장하지_않는다() {
		// given
		UploadRequest request = request(1, false, 60, List.of(new Sentence(1000L, 1000L, "x")), null);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, request));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		verifyNoInteractions(tx, recordings, parts, publisher);
	}

	@Test
	@DisplayName("첫 묶음이면 녹음을 만들고 묶음을 저장한 뒤 감지 작업을 커밋 뒤 큐에 넣는다")
	void 첫_묶음이면_녹음을_만들고_작업을_넣는다() {
		// given
		given(recordings.findByUserIdAndClientRecordingId(USER_ID, CLIENT_ID)).willReturn(Optional.empty());
		given(recordings.saveAndFlush(any())).willAnswer(inv -> {
			Recording r = inv.getArgument(0);
			ReflectionTestUtils.setField(r, "id", RECORDING_ID);
			return r;
		});
		List<RecordingPart> stored = new ArrayList<>();
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willAnswer(inv -> List.copyOf(stored));
		given(parts.saveAndFlush(any())).willAnswer(inv -> {
			RecordingPart p = inv.getArgument(0);
			ReflectionTestUtils.setField(p, "id", 50L);
			stored.add(p);
			return p;
		});
		given(publisher.sentencesFor(any(), any())).willReturn(SENTENCES);

		// when
		UploadResponse response = service.upload(USER_ID, request(1, false, 90, SENTENCES, true));

		// then
		assertThat(response).isEqualTo(new UploadResponse(RECORDING_ID, RecordingStatus.DETECTING));
		ArgumentCaptor<Recording> created = ArgumentCaptor.forClass(Recording.class);
		verify(recordings).saveAndFlush(created.capture());
		assertThat(created.getValue().isAudioSaved()).isTrue();
		assertThat(created.getValue().getEndedAt()).isEqualTo(STARTED_AT.plusSeconds(90));
		RecordingPart part = stored.getFirst();
		assertThat(part.getPartNo()).isEqualTo(1);
		assertThat(part.getStatus()).isEqualTo(RecordingStatus.DETECTING);
		assertThat(part.getSentences()).contains("안녕");
		verify(publisher).publishQuietly(JOB_ID);
	}

	@Test
	@DisplayName("audioSaved가 없으면 음성 저장을 끈 녹음으로 만든다")
	void audioSaved가_없으면_끈_녹음() {
		// given
		given(recordings.findByUserIdAndClientRecordingId(USER_ID, CLIENT_ID)).willReturn(Optional.empty());
		given(recordings.saveAndFlush(any())).willAnswer(inv -> inv.getArgument(0));
		given(parts.findByRecordingIdOrderByPartNo(any())).willReturn(List.of());
		given(parts.saveAndFlush(any())).willAnswer(inv -> inv.getArgument(0));

		// when
		service.upload(USER_ID, request(1, false, 60, List.of(), null));

		// then
		ArgumentCaptor<Recording> created = ArgumentCaptor.forClass(Recording.class);
		verify(recordings).saveAndFlush(created.capture());
		assertThat(created.getValue().isAudioSaved()).isFalse();
	}

	@Test
	@DisplayName("녹음 끝 시각은 앞 묶음 길이와 이번 묶음 길이를 더해 적는다")
	void 끝_시각은_묶음_길이의_합이다() {
		// given
		RecordingPart first = part(50L, 1, false, RecordingStatus.DETECTING);
		given(recordings.findByUserIdAndClientRecordingId(USER_ID, CLIENT_ID)).willReturn(Optional.of(recording));
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(List.of(first));
		given(parts.saveAndFlush(any())).willAnswer(inv -> inv.getArgument(0));

		// when
		service.upload(USER_ID, request(2, false, 30, SENTENCES, null));

		// then
		assertThat(recording.getEndedAt()).isEqualTo(STARTED_AT.plusSeconds(60 + 30));
		verify(recordings, never()).saveAndFlush(any());
		// 앞 묶음이 감지 중이라 이번 묶음은 넣지 않는다.
		verify(jobs, never()).save(any());
		verify(publisher, never()).publishQuietly(any());
	}

	@Test
	@DisplayName("이미 받은 묶음 번호면 저장하지 않고 지금 상태를 돌려준다")
	void 이미_받은_묶음이면_저장하지_않는다() {
		// given
		recording.setStatus(RecordingStatus.DETECTING);
		given(recordings.findByUserIdAndClientRecordingId(USER_ID, CLIENT_ID)).willReturn(Optional.of(recording));
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID))
			.willReturn(List.of(part(50L, 1, false, RecordingStatus.DETECTING)));

		// when
		UploadResponse response = service.upload(USER_ID, request(1, false, 60, SENTENCES, null));

		// then
		assertThat(response).isEqualTo(new UploadResponse(RECORDING_ID, RecordingStatus.DETECTING));
		verify(parts, never()).saveAndFlush(any());
		verifyNoInteractions(publisher);
	}

	@Test
	@DisplayName("마지막 묶음을 받은 뒤 새 묶음이 오면 RECORDING_CLOSED다")
	void 끝난_녹음에_오면_RECORDING_CLOSED() {
		// given
		given(recordings.findByUserIdAndClientRecordingId(USER_ID, CLIENT_ID)).willReturn(Optional.of(recording));
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID))
			.willReturn(List.of(part(50L, 1, true, RecordingStatus.DONE)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.upload(USER_ID, request(2, false, 60, SENTENCES, null)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECORDING_CLOSED);
		verify(parts, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("묶음 번호를 건너뛰면 PART_OUT_OF_ORDER다")
	void 번호를_건너뛰면_PART_OUT_OF_ORDER() {
		// given
		given(recordings.findByUserIdAndClientRecordingId(USER_ID, CLIENT_ID)).willReturn(Optional.of(recording));
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(List.of());

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.upload(USER_ID, request(2, false, 60, SENTENCES, null)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PART_OUT_OF_ORDER);
		verify(parts, never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("첫 묶음이 동시에 와서 UNIQUE에 걸리면 한 번 더 시도한다")
	void UNIQUE에_걸리면_한_번_더_시도한다() {
		// given
		given(recordings.findByUserIdAndClientRecordingId(USER_ID, CLIENT_ID))
			.willReturn(Optional.empty())
			.willReturn(Optional.of(recording));
		given(recordings.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("uk"));
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID))
			.willReturn(List.of(part(50L, 1, false, RecordingStatus.DETECTING)));

		// when
		UploadResponse response = service.upload(USER_ID, request(1, false, 60, SENTENCES, null));

		// then
		assertThat(response.recordingId()).isEqualTo(RECORDING_ID);
		verify(tx, times(2)).execute(any());
	}

	// ---- status·list ----

	@Test
	@DisplayName("내 녹음이 없으면 RECORDING_NOT_FOUND다")
	void 내_녹음이_없으면_RECORDING_NOT_FOUND() {
		// given
		given(recordings.findByIdAndUserId(RECORDING_ID, USER_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.status(USER_ID, RECORDING_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECORDING_NOT_FOUND);
		verify(recordings, never()).countCards(anyLong());
	}

	@Test
	@DisplayName("녹음 상태와 카드 수를 돌려준다")
	void 상태와_카드_수를_준다() {
		// given
		given(recordings.findByIdAndUserId(RECORDING_ID, USER_ID)).willReturn(Optional.of(recording));
		given(recordings.countCards(RECORDING_ID)).willReturn(3L);

		// when & then
		assertThat(service.status(USER_ID, RECORDING_ID))
			.isEqualTo(new StatusResponse(RECORDING_ID, RecordingStatus.RECEIVED, 3));
	}

	@Test
	@DisplayName("목록은 size+1개를 읽어 더 있으면 마지막 녹음 ID를 nextCursor로 주고, 보이는 녹음의 카드 수만 센다")
	void 목록은_보이는_녹음의_카드_수만_센다() {
		// given
		Recording r3 = recording(3L);
		Recording r2 = recording(2L);
		Recording r1 = recording(1L);
		given(recordings.findPage(USER_ID, EnumSet.allOf(RecordingStatus.class), null, Limit.of(3)))
			.willReturn(List.of(r3, r2, r1));
		given(recordings.countCardsByRecordingIds(List.of(3L, 2L)))
			.willReturn(List.<Object[]>of(new Object[] { 3L, 4L }));

		// when
		RecordingPage page = service.list(USER_ID, null, 2, null);

		// then
		assertThat(page.recordings()).extracting(RecordingListItem::recordingId).containsExactly(3L, 2L);
		assertThat(page.recordings()).extracting(RecordingListItem::cardCount).containsExactly(4, 0);
		assertThat(page.nextCursor()).isEqualTo("2");
	}

	@Test
	@DisplayName("목록이 비면 카드 수를 세지 않고 nextCursor가 null이다")
	void 빈_목록은_카드_수를_세지_않는다() {
		// given
		given(recordings.findPage(USER_ID, EnumSet.of(RecordingStatus.DONE), 7L, Limit.of(21))).willReturn(List.of());

		// when
		RecordingPage page = service.list(USER_ID, "7", null, "DONE");

		// then
		assertThat(page.recordings()).isEmpty();
		assertThat(page.nextCursor()).isNull();
		verify(recordings, never()).countCardsByRecordingIds(any());
	}

	// ---- dispatch ----

	@Test
	@DisplayName("감지 중인 묶음이 있으면 아무것도 넣지 않는다")
	void 감지_중이면_넣지_않는다() {
		// given
		RecordingPart waiting = part(51L, 2, false, RecordingStatus.RECEIVED);
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID))
			.willReturn(List.of(part(50L, 1, false, RecordingStatus.DETECTING), waiting));

		// when
		List<Long> jobIds = service.dispatch(recording);

		// then
		assertThat(jobIds).isEmpty();
		assertThat(waiting.getStatus()).isEqualTo(RecordingStatus.RECEIVED);
		verify(jobs, never()).save(any());
	}

	@Test
	@DisplayName("감지할 글이 없는 묶음은 DONE으로 넘기고 다음 받은 묶음을 넣는다")
	void 빈_묶음은_넘기고_다음을_넣는다() {
		// given
		RecordingPart empty = part(50L, 1, false, RecordingStatus.RECEIVED);
		RecordingPart next = part(51L, 2, false, RecordingStatus.RECEIVED);
		List<RecordingPart> all = List.of(empty, next);
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(all);
		given(publisher.sentencesFor(empty, all)).willReturn(List.of());
		given(publisher.sentencesFor(next, all)).willReturn(SENTENCES);

		// when
		List<Long> jobIds = service.dispatch(recording);

		// then
		assertThat(jobIds).containsExactly(JOB_ID);
		assertThat(empty.getStatus()).isEqualTo(RecordingStatus.DONE);
		assertThat(next.getStatus()).isEqualTo(RecordingStatus.DETECTING);
		assertThat(recording.getStatus()).isEqualTo(RecordingStatus.DETECTING);
		ArgumentCaptor<Job> saved = ArgumentCaptor.forClass(Job.class);
		verify(jobs).save(saved.capture());
		assertThat(saved.getValue().getRecordingPartId()).isEqualTo(51L);
	}

	@Test
	@DisplayName("마지막 묶음을 아직 안 받았으면 넣을 것이 없어도 DETECTING이다")
	void 마지막_묶음_전이면_DETECTING() {
		// given
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID))
			.willReturn(List.of(part(50L, 1, false, RecordingStatus.DONE)));

		// when
		List<Long> jobIds = service.dispatch(recording);

		// then
		assertThat(jobIds).isEmpty();
		assertThat(recording.getStatus()).isEqualTo(RecordingStatus.DETECTING);
		verify(jobs, never()).countDoneByRecordingId(any());
	}

	@Test
	@DisplayName("묶음이 하나도 없으면 상태를 바꾸지 않는다")
	void 묶음이_없으면_그대로다() {
		// given
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(List.of());

		// when
		service.dispatch(recording);

		// then
		assertThat(recording.getStatus()).isEqualTo(RecordingStatus.RECEIVED);
		verifyNoInteractions(jobs);
	}

	@Test
	@DisplayName("마지막 묶음까지 끝났고 감지한 작업이 있으면 실패한 묶음이 있어도 DONE이다")
	void 하나라도_감지했으면_DONE() {
		// given
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(
				List.of(part(50L, 1, false, RecordingStatus.FAILED), part(51L, 2, true, RecordingStatus.DONE)));
		given(jobs.countDoneByRecordingId(RECORDING_ID)).willReturn(1L);

		// when
		service.dispatch(recording);

		// then
		assertThat(recording.getStatus()).isEqualTo(RecordingStatus.DONE);
		verify(jobs).flush();
	}

	@Test
	@DisplayName("실패한 묶음이 있고 감지에 성공한 작업이 없으면 FAILED다")
	void 감지에_모두_실패하면_FAILED() {
		// given
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(
				List.of(part(50L, 1, false, RecordingStatus.FAILED), part(51L, 2, true, RecordingStatus.DONE)));
		given(jobs.countDoneByRecordingId(RECORDING_ID)).willReturn(0L);

		// when
		service.dispatch(recording);

		// then
		assertThat(recording.getStatus()).isEqualTo(RecordingStatus.FAILED);
	}

	@Test
	@DisplayName("실패 없이 모두 빈 묶음이라 감지한 작업이 없어도 DONE이다")
	void 실패가_없으면_DONE() {
		// given
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID))
			.willReturn(List.of(part(50L, 1, true, RecordingStatus.DONE)));
		given(jobs.countDoneByRecordingId(RECORDING_ID)).willReturn(0L);

		// when
		service.dispatch(recording);

		// then
		assertThat(recording.getStatus()).isEqualTo(RecordingStatus.DONE);
	}

	@Test
	@DisplayName("dispatchNext는 녹음을 잠그고 고른 작업을 트랜잭션 뒤에 큐에 넣는다")
	void dispatchNext는_작업을_큐에_넣는다() {
		// given
		RecordingPart part = part(50L, 1, false, RecordingStatus.RECEIVED);
		given(recordings.findByIdForUpdate(RECORDING_ID)).willReturn(Optional.of(recording));
		given(parts.findByRecordingIdOrderByPartNo(RECORDING_ID)).willReturn(List.of(part));
		given(publisher.sentencesFor(any(), any())).willReturn(SENTENCES);

		// when
		service.dispatchNext(RECORDING_ID);

		// then
		verify(publisher).publishQuietly(JOB_ID);
	}

	private Recording recording(long id) {
		Recording r = new Recording(USER_ID, CLIENT_ID, STARTED_AT, false);
		ReflectionTestUtils.setField(r, "id", id);
		return r;
	}

	private RecordingPart part(long id, int partNo, boolean last, RecordingStatus status) {
		RecordingPart part = new RecordingPart(recording, partNo, last, 60, "[]");
		ReflectionTestUtils.setField(part, "id", id);
		part.setStatus(status);
		return part;
	}

	private static UploadRequest request(int partNo, boolean last, int durationSec, List<Sentence> sentences,
			Boolean audioSaved) {
		return new UploadRequest(CLIENT_ID, partNo, last, STARTED_AT, durationSec, sentences, audioSaved);
	}

}
