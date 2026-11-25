package com.lolog.api.domain.comic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

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
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("ComicService 만화 요청")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComicServiceRequestTest {

	private static final long USER_ID = 7L;
	private static final long CARD_ID = 11L;

	@Mock
	CardRepository cards;

	@Mock
	ComicRepository comics;

	@Mock
	JobRepository jobs;

	@Mock
	ComicPublisher publisher;

	@Mock
	TransactionTemplate tx;

	@Mock
	PushService push;

	@InjectMocks
	ComicService service;

	@BeforeEach
	void setUp() {
		// 트랜잭션 콜백은 바로 실행한다
		given(tx.execute(any())).willAnswer(i -> i.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
		Card card = mock(Card.class);
		given(card.getId()).willReturn(CARD_ID);
		given(card.getSummary()).willReturn("치킨 주문");
		given(cards.findByIdAndUserIdForUpdate(CARD_ID, USER_ID)).willReturn(Optional.of(card));
	}

	@Test
	@DisplayName("카드가 없으면 CARD_NOT_FOUND이고 횟수를 올리지 않는다")
	void 카드가_없으면_CARD_NOT_FOUND이고_횟수를_올리지_않는다() {
		// given
		given(cards.findByIdAndUserIdForUpdate(CARD_ID, USER_ID)).willReturn(Optional.empty());

		// when & then
		assertThatThrownBy(() -> service.request(USER_ID, CARD_ID))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CARD_NOT_FOUND));
		then(cards).should(never()).incrementComicCount(any());
		then(publisher).should(never()).publishQuietly(any());
	}

	@Test
	@DisplayName("만드는 중인 만화가 있으면 횟수보다 먼저 COMIC_IN_PROGRESS다")
	void 만드는_중인_만화가_있으면_횟수보다_먼저_COMIC_IN_PROGRESS다() {
		// given
		given(comics.existsByCardIdAndStatusIn(any(), any())).willReturn(true);

		// when & then
		assertThatThrownBy(() -> service.request(USER_ID, CARD_ID))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_IN_PROGRESS));
		then(cards).should(never()).incrementComicCount(any());
	}

	@Test
	@DisplayName("횟수를 올리지 못하면 COMIC_LIMIT이고 만화를 만들지 않는다")
	void 횟수를_올리지_못하면_COMIC_LIMIT이고_만화를_만들지_않는다() {
		// given
		given(cards.incrementComicCount(CARD_ID)).willReturn(0);

		// when & then
		assertThatThrownBy(() -> service.request(USER_ID, CARD_ID))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_LIMIT));
		then(comics).should(never()).saveAndFlush(any());
	}

	@Test
	@DisplayName("동시 요청이 부분 유니크에 걸리면 COMIC_IN_PROGRESS다")
	void 동시_요청이_부분_유니크에_걸리면_COMIC_IN_PROGRESS다() {
		// given
		given(cards.incrementComicCount(CARD_ID)).willReturn(1);
		given(comics.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("uk_comics_card_id_in_progress"));

		// when & then
		assertThatThrownBy(() -> service.request(USER_ID, CARD_ID))
			.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_IN_PROGRESS));
		then(publisher).should(never()).publishQuietly(any());
	}

	@Test
	@DisplayName("성공하면 QUEUED 만화를 돌려주고 작업을 큐에 넣는다")
	void 성공하면_QUEUED_만화를_돌려주고_작업을_큐에_넣는다() {
		// given
		given(cards.incrementComicCount(CARD_ID)).willReturn(1);
		given(comics.saveAndFlush(any())).willAnswer(i -> {
			Comic comic = i.getArgument(0);
			ReflectionTestUtils.setField(comic, "id", 21L);
			return comic;
		});
		given(jobs.save(any())).willAnswer(i -> {
			Job job = i.getArgument(0);
			ReflectionTestUtils.setField(job, "id", 31L);
			return job;
		});

		// when
		ComicService.Created created = service.request(USER_ID, CARD_ID);

		// then
		assertThat(created.comicId()).isEqualTo(21L);
		assertThat(created.status()).isEqualTo(ComicStatus.QUEUED);
		then(publisher).should().publishQuietly(31L);
	}

}
