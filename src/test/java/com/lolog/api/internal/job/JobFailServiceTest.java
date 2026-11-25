package com.lolog.api.internal.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.character.service.ProfileCharacterService;
import com.lolog.api.domain.character.service.VoiceCloneService;
import com.lolog.api.domain.comic.service.ComicService;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.text.service.TextService;
import com.lolog.api.domain.video.service.VideoService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.internal.recording.DetectResultService;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("JobFailService")
class JobFailServiceTest {

	private static final long JOB_ID = 7L;
	private static final String REASON = "워커 실패";

	@Mock
	private JobRepository jobs;

	@Mock
	private DetectResultService detectResults;

	@Mock
	private ComicService comics;

	@Mock
	private ProfileCharacterService profileCharacters;

	@Mock
	private VideoService videos;

	@Mock
	private TextService texts;

	@Mock
	private VoiceCloneService voiceClones;

	@InjectMocks
	private JobFailService service;

	@Test
	@DisplayName("만화 작업 실패는 만화 서비스에만 넘긴다")
	void 만화_작업은_만화_서비스에만_넘긴다() {
		// given
		given(jobs.findTypeById(JOB_ID)).willReturn(Optional.of("COMIC"));

		// when
		service.fail(JOB_ID, REASON);

		// then
		verify(comics).fail(JOB_ID, REASON);
		verifyNoInteractions(detectResults, profileCharacters, videos, texts, voiceClones);
	}

	@Test
	@DisplayName("프로필 캐릭터 작업 실패는 프로필 캐릭터 서비스에만 넘긴다")
	void 프로필_캐릭터_작업은_프로필_캐릭터_서비스에만_넘긴다() {
		// given
		given(jobs.findTypeById(JOB_ID)).willReturn(Optional.of("AVATAR"));

		// when
		service.fail(JOB_ID, REASON);

		// then
		verify(profileCharacters).fail(JOB_ID, REASON);
		verifyNoInteractions(detectResults, comics, videos, texts, voiceClones);
	}

	@Test
	@DisplayName("내 목소리 작업 실패는 내 목소리 서비스에만 넘긴다")
	void 내_목소리_작업은_내_목소리_서비스에만_넘긴다() {
		// given
		given(jobs.findTypeById(JOB_ID)).willReturn(Optional.of("VOICE_CLONE"));

		// when
		service.fail(JOB_ID, REASON);

		// then
		verify(voiceClones).fail(JOB_ID, REASON);
		verifyNoInteractions(detectResults, comics, profileCharacters, videos, texts);
	}

	@Test
	@DisplayName("글 작업 실패는 글 서비스에만 넘긴다")
	void 글_작업은_글_서비스에만_넘긴다() {
		// given
		given(jobs.findTypeById(JOB_ID)).willReturn(Optional.of("TEXT"));

		// when
		service.fail(JOB_ID, REASON);

		// then
		verify(texts).fail(JOB_ID, REASON);
		verifyNoInteractions(detectResults, comics, profileCharacters, videos, voiceClones);
	}

	@ParameterizedTest(name = "영상 작업 {0}의 실패는 영상 서비스에만 넘긴다")
	@ValueSource(strings = { "VIDEO_VOICE", "VIDEO_DRAW" })
	@DisplayName("영상 목소리·그림 작업 실패는 영상 서비스에만 넘긴다")
	void 영상_두_단계_작업은_영상_서비스에만_넘긴다(String type) {
		// given
		given(jobs.findTypeById(JOB_ID)).willReturn(Optional.of(type));

		// when
		service.fail(JOB_ID, REASON);

		// then
		verify(videos).fail(JOB_ID, REASON);
		verifyNoInteractions(detectResults, comics, profileCharacters, texts, voiceClones);
	}

	@Test
	@DisplayName("감지 작업 실패는 감지 결과 서비스에만 넘긴다")
	void 감지_작업은_감지_결과_서비스에만_넘긴다() {
		// given
		given(jobs.findTypeById(JOB_ID)).willReturn(Optional.of("DETECT"));

		// when
		service.fail(JOB_ID, REASON);

		// then
		verify(detectResults).fail(JOB_ID, REASON);
		verifyNoInteractions(comics, profileCharacters, videos, texts, voiceClones);
	}

	@Test
	@DisplayName("없는 작업이면 field 없는 INVALID_REQUEST이고 어느 서비스도 부르지 않는다")
	void 없는_작업이면_400이고_아무_서비스도_부르지_않는다() {
		// given
		given(jobs.findTypeById(JOB_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.fail(JOB_ID, REASON));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isNull();
		verifyNoInteractions(detectResults, comics, profileCharacters, videos, texts, voiceClones);
	}

}
