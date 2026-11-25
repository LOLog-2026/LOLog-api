package com.lolog.api.domain.recording.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.recording.dto.RecordingDtos.Sentence;
import com.lolog.api.domain.recording.dto.RecordingDtos.UploadRequest;
import com.lolog.api.domain.recording.entity.RecordingStatus;
import com.lolog.api.domain.recording.repository.RecordingPartRepository;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.function.Executable;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("RecordingService")
class RecordingServiceTest {

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

	@BeforeEach
	void setUp() {
		service = new RecordingService(recordings, parts, jobs, publisher, new ObjectMapper(), tx);
	}

	@Test
	@DisplayName("상태가 null이면 전부다")
	void 상태가_null이면_전부다() {
		// when & then
		assertThat(RecordingService.parseStatuses(null)).containsExactlyInAnyOrder(RecordingStatus.values());
	}

	@Test
	@DisplayName("상태가 공백뿐이면 전부다")
	void 상태가_공백뿐이면_전부다() {
		// when & then
		assertThat(RecordingService.parseStatuses("  ")).containsExactlyInAnyOrder(RecordingStatus.values());
	}

	@Test
	@DisplayName("쉼표 뒤 공백은 무시하고 읽는다")
	void 쉼표_뒤_공백은_무시하고_읽는다() {
		// when & then
		assertThat(RecordingService.parseStatuses("RECEIVED, DONE"))
			.containsExactlyInAnyOrder(RecordingStatus.RECEIVED, RecordingStatus.DONE);
	}

	@Test
	@DisplayName("같은 상태가 두 번 와도 하나로 본다")
	void 같은_상태가_두_번_와도_하나로_본다() {
		// when & then
		assertThat(RecordingService.parseStatuses("FAILED,FAILED")).containsExactly(RecordingStatus.FAILED);
	}

	@Test
	@DisplayName("가운데 빈 칸이 있으면 400이다")
	void 가운데_빈_칸이_있으면_400이다() {
		// when & then
		assertInvalidStatus(() -> RecordingService.parseStatuses("RECEIVED,,DONE"));
	}

	@Test
	@DisplayName("앞에 빈 칸이 있으면 400이다")
	void 앞에_빈_칸이_있으면_400이다() {
		// when & then
		assertInvalidStatus(() -> RecordingService.parseStatuses(",RECEIVED"));
	}

	@Test
	@DisplayName("모르는 상태가 섞이면 400이다")
	void 모르는_상태가_섞이면_400이다() {
		// when & then
		assertInvalidStatus(() -> RecordingService.parseStatuses("RECEIVED,FOO"));
	}

	@Test
	@DisplayName("문장이 5000개면 통과한다")
	void 문장이_5000개면_통과한다() {
		// when & then
		assertThatCode(() -> service.validate(request(Collections.nCopies(5000, sentence(0, 1)))))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("문장이 5001개면 413이다")
	void 문장이_5001개면_413이다() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.validate(request(Collections.nCopies(5001, sentence(0, 1)))));

		// then
		assertThat(e).isNotNull();
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
	}

	@Test
	@DisplayName("문장이 없으면 통과한다")
	void 문장이_없으면_통과한다() {
		// when & then
		assertThatCode(() -> service.validate(request(List.of()))).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("끝이 시작보다 1ms 뒤면 통과한다")
	void 끝이_시작보다_1ms_뒤면_통과한다() {
		// when & then
		assertThatCode(() -> service.validate(request(List.of(sentence(1000, 1001))))).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("끝이 시작과 같으면 그 문장의 endMs가 틀린 칸이다")
	void 끝이_시작과_같으면_그_문장의_endMs가_틀린_칸이다() {
		// when & then
		assertInvalid(List.of(sentence(0, 500), sentence(1000, 1000)), "sentences[1].endMs");
	}

	@Test
	@DisplayName("끝이 시작보다 앞이면 그 문장의 endMs가 틀린 칸이다")
	void 끝이_시작보다_앞이면_그_문장의_endMs가_틀린_칸이다() {
		// when & then
		assertInvalid(List.of(sentence(1000, 999)), "sentences[0].endMs");
	}

	private void assertInvalid(List<Sentence> sentences, String field) {
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.validate(request(sentences)));
		assertThat(e).isNotNull();
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo(field);
	}

	private static void assertInvalidStatus(Executable call) {
		ApiException e = catchThrowableOfType(ApiException.class, call::execute);
		assertThat(e).isNotNull();
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("status");
	}

	private static UploadRequest request(List<Sentence> sentences) {
		return new UploadRequest("00000000-0000-0000-0000-000000000000", 1, false, OffsetDateTime.now(), 60,
				sentences, null);
	}

	private static Sentence sentence(long startMs, long endMs) {
		return new Sentence(startMs, endMs, "글");
	}

}
