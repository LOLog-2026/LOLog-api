package com.lolog.api.domain.comic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.comic.entity.Comic;
import com.lolog.api.domain.comic.entity.ComicStatus;
import com.lolog.api.domain.comic.repository.ComicRepository;
import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("ComicService")
class ComicServiceTest {

	private static final long USER_ID = 1L;
	private static final long CARD_ID = 10L;
	private static final long COMIC_ID = 100L;
	private static final long JOB_ID = 1000L;

	@Mock
	private CardRepository cards;

	@Mock
	private ComicRepository comics;

	@Mock
	private JobRepository jobs;

	@Mock
	private ComicPublisher publisher;

	@Mock
	private TransactionTemplate tx;

	@Mock
	private PushService push;

	private ComicService service;

	@BeforeEach
	void setUp() {
		// 트랜잭션 템플릿은 받은 콜백을 바로 실행한다.
		lenient().when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
		lenient().doAnswer(inv -> {
			inv.<Consumer<TransactionStatus>>getArgument(0).accept(null);
			return null;
		}).when(tx).executeWithoutResult(any());
		service = new ComicService(cards, comics, jobs, publisher, tx, push);
	}

	// ---- request ----

	@Test
	@DisplayName("내 카드가 아니면 CARD_NOT_FOUND이고 큐에 넣지 않는다")
	void 카드가_없으면_CARD_NOT_FOUND() {
		// given
		given(cards.findByIdAndUserIdForUpdate(CARD_ID, USER_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.request(USER_ID, CARD_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CARD_NOT_FOUND);
		verify(cards, never()).incrementComicCount(anyLong());
		verifyNoInteractions(publisher);
	}

	@Test
	@DisplayName("만드는 중인 만화가 있으면 횟수를 보기 전에 COMIC_IN_PROGRESS다")
	void 만드는_중이면_횟수보다_먼저_COMIC_IN_PROGRESS() {
		// given
		given(cards.findByIdAndUserIdForUpdate(CARD_ID, USER_ID)).willReturn(Optional.of(card()));
		given(comics.existsByCardIdAndStatusIn(CARD_ID, ComicStatus.IN_PROGRESS)).willReturn(true);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.request(USER_ID, CARD_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_IN_PROGRESS);
		verify(cards, never()).incrementComicCount(anyLong());
		verify(comics, never()).saveAndFlush(any());
		verifyNoInteractions(publisher);
	}

	@Test
	@DisplayName("횟수를 올리지 못하면(0행) COMIC_LIMIT이고 만화를 만들지 않는다")
	void 횟수가_다_찼으면_COMIC_LIMIT() {
		// given
		given(cards.findByIdAndUserIdForUpdate(CARD_ID, USER_ID)).willReturn(Optional.of(card()));
		given(cards.incrementComicCount(CARD_ID)).willReturn(0);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.request(USER_ID, CARD_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_LIMIT);
		verify(comics, never()).saveAndFlush(any());
		verify(jobs, never()).save(any());
		verifyNoInteractions(publisher);
	}

	@Test
	@DisplayName("부분 유니크에 걸려 저장이 막히면 COMIC_IN_PROGRESS이고 작업을 만들지 않는다")
	void 부분_유니크에_걸리면_COMIC_IN_PROGRESS() {
		// given
		given(cards.findByIdAndUserIdForUpdate(CARD_ID, USER_ID)).willReturn(Optional.of(card()));
		given(cards.incrementComicCount(CARD_ID)).willReturn(1);
		given(comics.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("uk_comics_card_id_in_progress"));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.request(USER_ID, CARD_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_IN_PROGRESS);
		verify(jobs, never()).save(any());
		verifyNoInteractions(publisher);
	}

	@Test
	@DisplayName("요청하면 카드 요약을 복사한 QUEUED 만화와 COMIC 작업을 만들고 커밋 뒤 큐에 넣는다")
	void 요청하면_만화와_작업을_만들고_큐에_넣는다() {
		// given
		given(cards.findByIdAndUserIdForUpdate(CARD_ID, USER_ID)).willReturn(Optional.of(card()));
		given(cards.incrementComicCount(CARD_ID)).willReturn(1);
		given(comics.saveAndFlush(any())).willAnswer(inv -> withId(inv.getArgument(0), COMIC_ID));
		given(jobs.save(any())).willAnswer(inv -> withId(inv.getArgument(0), JOB_ID));

		// when
		ComicService.Created created = service.request(USER_ID, CARD_ID);

		// then
		assertThat(created.comicId()).isEqualTo(COMIC_ID);
		assertThat(created.status()).isEqualTo(ComicStatus.QUEUED);
		ArgumentCaptor<Comic> comic = ArgumentCaptor.forClass(Comic.class);
		verify(comics).saveAndFlush(comic.capture());
		assertThat(comic.getValue().getCardId()).isEqualTo(CARD_ID);
		assertThat(comic.getValue().getUserId()).isEqualTo(USER_ID);
		assertThat(comic.getValue().getCardSummary()).isEqualTo("요약");
		ArgumentCaptor<Job> job = ArgumentCaptor.forClass(Job.class);
		verify(jobs).save(job.capture());
		assertThat(job.getValue().getJobType()).isEqualTo("COMIC");
		assertThat(job.getValue().getComicId()).isEqualTo(COMIC_ID);
		assertThat(job.getValue().getUserId()).isEqualTo(USER_ID);
		verify(publisher).publishQuietly(JOB_ID);
	}

	// ---- fail ----

	@Test
	@DisplayName("만화 작업이 아니면 field 없는 INVALID_REQUEST다")
	void 만화_작업이_아니면_400() {
		// given
		given(jobs.findComicIdById(JOB_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.fail(JOB_ID, "이유"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isNull();
		verifyNoInteractions(push);
	}

	@Test
	@DisplayName("이미 끝난 작업이면 아무것도 바꾸지 않아 횟수를 두 번 돌려주지 않는다")
	void 끝난_작업이면_무시한다() {
		// given
		Comic comic = comic(CARD_ID);
		Job job = comicJob();
		job.fail("처음 실패");
		given(jobs.findComicIdById(JOB_ID)).willReturn(Optional.of(COMIC_ID));
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.of(comic));
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));

		// when
		service.fail(JOB_ID, "다시 온 실패");

		// then
		assertThat(job.getFailReason()).isEqualTo("처음 실패");
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.QUEUED);
		verify(cards, never()).decrementComicCount(anyLong());
		verifyNoInteractions(push);
	}

	@Test
	@DisplayName("실패하면 작업·만화를 FAILED로 바꾸고 횟수를 돌려준 뒤 실패 푸시를 보낸다")
	void 실패하면_횟수를_돌려주고_푸시한다() {
		// given
		Comic comic = comic(CARD_ID);
		Job job = comicJob();
		given(jobs.findComicIdById(JOB_ID)).willReturn(Optional.of(COMIC_ID));
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.of(comic));
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));

		// when
		service.fail(JOB_ID, "그리기 실패");

		// then
		assertThat(job.getStatus()).isEqualTo("FAILED");
		assertThat(job.getFailReason()).isEqualTo("그리기 실패");
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.FAILED);
		assertThat(comic.getFailReason()).isEqualTo("그리기 실패");
		verify(cards).decrementComicCount(CARD_ID);
		verify(push).notify(USER_ID, PushService.comicFailed(COMIC_ID, CARD_ID));
	}

	@Test
	@DisplayName("카드를 지운 만화가 실패하면 돌려줄 횟수 없이 푸시만 보낸다")
	void 카드를_지운_만화는_횟수를_돌려주지_않는다() {
		// given
		Comic comic = comic(null);
		given(jobs.findComicIdById(JOB_ID)).willReturn(Optional.of(COMIC_ID));
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.of(comic));
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(comicJob()));

		// when
		service.fail(JOB_ID, "실패");

		// then
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.FAILED);
		verify(cards, never()).decrementComicCount(anyLong());
		verify(push).notify(USER_ID, PushService.comicFailed(COMIC_ID, null));
	}

	private static Card card() {
		Card card = new Card(5L, USER_ID, "웃김", "요약", 0, 1000, OffsetDateTime.now());
		ReflectionTestUtils.setField(card, "id", CARD_ID);
		return card;
	}

	private static Comic comic(Long cardId) {
		return withId(new Comic(cardId, USER_ID, "요약"), COMIC_ID);
	}

	private static Job comicJob() {
		return withId(Job.comic(COMIC_ID, USER_ID), JOB_ID);
	}

	private static <T> T withId(T entity, long id) {
		ReflectionTestUtils.setField(entity, "id", id);
		return entity;
	}

}
