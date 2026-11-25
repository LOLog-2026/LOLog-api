package com.lolog.api.domain.comic.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.comic.dto.ComicDtos.ComicPage;
import com.lolog.api.domain.comic.dto.ComicDtos.ComicResponse;
import com.lolog.api.domain.comic.entity.Comic;
import com.lolog.api.domain.comic.repository.ComicRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.storage.StorageDeletions;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("ComicQueryService")
class ComicQueryServiceTest {

	private static final long USER_ID = 1L;

	@Mock
	private ComicRepository comics;

	@Mock
	private ImageStorage storage;

	@Mock
	private StorageDeletions deletions;

	@Mock
	private TransactionTemplate tx;

	private ComicQueryService service;

	@BeforeEach
	void setUp() {
		// 트랜잭션 템플릿은 받은 콜백을 바로 실행한다.
		lenient().doAnswer(inv -> {
			inv.<Consumer<TransactionStatus>>getArgument(0).accept(null);
			return null;
		}).when(tx).executeWithoutResult(any());
		service = new ComicQueryService(comics, storage, deletions, tx);
	}

	// ---- list ----

	@Test
	@DisplayName("크기보다 하나 더 읽혀 오면 크기만큼만 주고 마지막으로 준 만화 ID를 다음 커서로 준다")
	void 더_있으면_마지막_ID가_다음_커서다() {
		// given
		given(comics.findPage(USER_ID, null, Limit.of(3))).willReturn(List.of(comic(30), comic(20), comic(10)));

		// when
		ComicPage page = service.list(USER_ID, null, 2, null);

		// then
		assertThat(page.comics()).extracting(ComicResponse::id).containsExactly(30L, 20L);
		assertThat(page.nextCursor()).isEqualTo("20");
	}

	@Test
	@DisplayName("크기만큼만 읽혀 오면 다음 커서는 null이다")
	void 딱_크기만큼이면_다음_커서가_없다() {
		// given
		given(comics.findPage(USER_ID, 50L, Limit.of(3))).willReturn(List.of(comic(30), comic(20)));

		// when
		ComicPage page = service.list(USER_ID, "50", 2, "latest");

		// then
		assertThat(page.comics()).hasSize(2);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	@DisplayName("oldest면 오래된 순 조회를 쓰고 최신순 조회는 부르지 않는다")
	void oldest면_오래된_순으로_읽는다() {
		// given
		given(comics.findPageOldest(USER_ID, 5L, Limit.of(21))).willReturn(List.of(comic(6)));

		// when
		ComicPage page = service.list(USER_ID, "5", null, "oldest");

		// then
		assertThat(page.comics()).extracting(ComicResponse::id).containsExactly(6L);
		verify(comics, never()).findPage(any(), any(), any());
	}

	@Test
	@DisplayName("커서가 숫자가 아니면 field가 cursor인 INVALID_REQUEST이고 조회하지 않는다")
	void 커서가_숫자가_아니면_400() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.list(USER_ID, "abc", null, null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("cursor");
		verifyNoInteractions(comics);
	}

	@Test
	@DisplayName("모르는 정렬이면 field가 sort인 INVALID_REQUEST이고 조회하지 않는다")
	void 모르는_정렬이면_400() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.list(USER_ID, null, null, "random"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("sort");
		verifyNoInteractions(comics);
	}

	// ---- detail ----

	@Test
	@DisplayName("다 만든 만화는 이미지 서명 주소를 준다")
	void DONE이면_이미지_주소를_준다() throws Exception {
		// given
		Comic comic = comic(7);
		comic.finish("comics/1/7.png");
		given(comics.findByIdAndUserId(7L, USER_ID)).willReturn(Optional.of(comic));
		given(storage.presignedUrl("comics/1/7.png")).willReturn(URI.create("https://s3.test/comics/1/7.png").toURL());

		// when
		ComicResponse response = service.detail(USER_ID, 7);

		// then
		assertThat(response.imageUrl()).isEqualTo("https://s3.test/comics/1/7.png");
		assertThat(response.cardSummary()).isEqualTo("요약");
	}

	@Test
	@DisplayName("만드는 중인 만화는 이미지 주소가 없고 서명하지 않는다")
	void 만드는_중이면_이미지_주소가_없다() {
		// given
		Comic comic = comic(7);
		comic.saveStoryboard("{}");
		given(comics.findByIdAndUserId(7L, USER_ID)).willReturn(Optional.of(comic));

		// when
		ComicResponse response = service.detail(USER_ID, 7);

		// then
		assertThat(response.imageUrl()).isNull();
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("카드 요약이 없던 예전 만화는 요약을 빈 글자로 준다")
	void 요약이_없으면_빈_글자다() {
		// given
		Comic comic = comic(7, null);
		given(comics.findByIdAndUserId(7L, USER_ID)).willReturn(Optional.of(comic));

		// when & then
		assertThat(service.detail(USER_ID, 7).cardSummary()).isEmpty();
	}

	@Test
	@DisplayName("내 만화가 아니면 COMIC_NOT_FOUND다")
	void 내_만화가_아니면_COMIC_NOT_FOUND() {
		// given
		given(comics.findByIdAndUserId(7L, USER_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.detail(USER_ID, 7));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_NOT_FOUND);
	}

	// ---- delete ----

	@Test
	@DisplayName("지우면 만화 행을 지우고 정해진 이미지 키를 지울 줄에 넣는다")
	void 지우면_이미지_키를_지울_줄에_넣는다() {
		// given
		Comic comic = comic(7);
		given(comics.findByIdAndUserIdForUpdate(7L, USER_ID)).willReturn(Optional.of(comic));

		// when
		service.delete(USER_ID, 7);

		// then
		verify(comics).delete(comic);
		verify(deletions).schedule(ImageStorage.comicKey(USER_ID, 7));
	}

	@Test
	@DisplayName("내 만화가 아니면 지우지 않고 COMIC_NOT_FOUND다")
	void 지울_만화가_없으면_COMIC_NOT_FOUND() {
		// given
		given(comics.findByIdAndUserIdForUpdate(7L, USER_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.delete(USER_ID, 7));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_NOT_FOUND);
		verify(comics, never()).delete(any());
		verifyNoInteractions(deletions);
	}

	private static Comic comic(long id) {
		return comic(id, "요약");
	}

	private static Comic comic(long id, String summary) {
		Comic comic = new Comic(10L, USER_ID, summary);
		ReflectionTestUtils.setField(comic, "id", id);
		return comic;
	}

}
