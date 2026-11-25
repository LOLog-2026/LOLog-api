package com.lolog.api.internal.comic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.character.service.CharacterViews;
import com.lolog.api.domain.character.service.CharacterViews.Ref;
import com.lolog.api.domain.character.service.CharacterViews.SourceCharacter;
import com.lolog.api.domain.comic.entity.Comic;
import com.lolog.api.domain.comic.entity.ComicStatus;
import com.lolog.api.domain.comic.repository.ComicRepository;
import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.storage.StorageDeletions;
import com.lolog.api.internal.comic.ComicWorkerDtos.ComicSource;
import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("ComicWorkerService")
class ComicWorkerServiceTest {

	private static final long USER_ID = 1L;
	private static final long CARD_ID = 10L;
	private static final long COMIC_ID = 100L;
	private static final String KEY = ImageStorage.comicKey(USER_ID, COMIC_ID);

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private ComicRepository comics;

	@Mock
	private CardRepository cards;

	@Mock
	private JobRepository jobs;

	@Mock
	private ImageStorage storage;

	@Mock
	private StorageDeletions deletions;

	@Mock
	private TransactionTemplate tx;

	@Mock
	private PushService push;

	@Mock
	private CharacterViews characters;

	private ComicWorkerService service;

	@BeforeEach
	void setUp() {
		// 트랜잭션 템플릿은 받은 콜백을 바로 실행한다.
		lenient().when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
		lenient().doAnswer(inv -> {
			inv.<Consumer<TransactionStatus>>getArgument(0).accept(null);
			return null;
		}).when(tx).executeWithoutResult(any());
		service = new ComicWorkerService(comics, cards, jobs, storage, deletions, objectMapper, tx, push, characters);
	}

	// ---- source ----

	@Test
	@DisplayName("만화가 없으면 COMIC_NOT_FOUND다")
	void 만화가_없으면_COMIC_NOT_FOUND() {
		// given
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.source(COMIC_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_NOT_FOUND);
		verifyNoInteractions(cards, characters);
	}

	@Test
	@DisplayName("끝난 만화의 source는 COMIC_NOT_FOUND이고 상태를 바꾸지 않는다")
	void 끝난_만화의_source는_404() {
		// given
		Comic comic = comic(ComicStatus.DONE);
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.of(comic));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.source(COMIC_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_NOT_FOUND);
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.DONE);
		verifyNoInteractions(cards);
	}

	@Test
	@DisplayName("카드가 지워진 만화면 COMIC_NOT_FOUND이고 콘티 단계로 바꾸지 않는다")
	void 카드가_지워졌으면_404() {
		// given
		Comic comic = new Comic(null, USER_ID, "요약");
		ReflectionTestUtils.setField(comic, "id", COMIC_ID);
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.of(comic));
		given(cards.findById(-1L)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.source(COMIC_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_NOT_FOUND);
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.QUEUED);
	}

	@Test
	@DisplayName("캐릭터가 붙은 화자가 없으면 캐릭터를 찾지 않고 콘티 단계로 바꾼다")
	void 캐릭터가_없으면_찾지_않는다() {
		// given
		Comic comic = comic(ComicStatus.QUEUED);
		Card card = card();
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.of(comic));
		given(cards.findById(CARD_ID)).willReturn(Optional.of(card));

		// when
		ComicSource source = service.source(COMIC_ID);

		// then
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.STORYBOARD);
		assertThat(source.comicId()).isEqualTo(COMIC_ID);
		assertThat(source.cardId()).isEqualTo(CARD_ID);
		assertThat(source.speakers()).hasSize(2).allSatisfy(s -> assertThat(s.character()).isNull());
		assertThat(source.lines()).hasSize(1);
		verifyNoInteractions(characters);
	}

	@Test
	@DisplayName("캐릭터가 붙은 화자만 모아 한 번에 찾고, 못 찾은 캐릭터는 그림 없이 둔다")
	void 붙은_캐릭터만_모아_찾는다() {
		// given
		Comic comic = comic(ComicStatus.QUEUED);
		Card card = card();
		card.getSpeakers().get(0).setCharacter("DEFAULT", "d1");
		card.getSpeakers().get(1).setCharacter("PROFILE", "7");
		SourceCharacter found = new SourceCharacter("DEFAULT:d1", "모습", "https://img");
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.of(comic));
		given(cards.findById(CARD_ID)).willReturn(Optional.of(card));
		given(characters.sourceCharacters(USER_ID, List.of(new Ref("DEFAULT", "d1"), new Ref("PROFILE", "7"))))
			.willReturn(Map.of(new Ref("DEFAULT", "d1"), found));

		// when
		ComicSource source = service.source(COMIC_ID);

		// then
		assertThat(source.speakers().get(0).character()).isEqualTo(found);
		assertThat(source.speakers().get(1).character()).isNull();
	}

	// ---- storyboard ----

	@Test
	@DisplayName("콘티를 받을 만화가 없으면 COMIC_NOT_FOUND다")
	void 콘티_만화가_없으면_404() throws Exception {
		// given
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.storyboard(COMIC_ID, board()));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_NOT_FOUND);
	}

	@Test
	@DisplayName("끝난 만화에 온 콘티는 검사도 저장도 하지 않는다")
	void 끝난_만화의_콘티는_무시한다() {
		// given
		Comic comic = comic(ComicStatus.FAILED);
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.of(comic));

		// when
		service.storyboard(COMIC_ID, objectMapper.readTree("[]"));

		// then
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.FAILED);
		assertThat(comic.getStoryboard()).isNull();
	}

	@Test
	@DisplayName("맞는 콘티는 저장하고 그리기 단계로 바꾼다")
	void 맞는_콘티는_저장한다() {
		// given
		Comic comic = comic(ComicStatus.STORYBOARD);
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.of(comic));
		JsonNode body = board();

		// when
		service.storyboard(COMIC_ID, body);

		// then
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.DRAWING);
		assertThat(objectMapper.readTree(comic.getStoryboard())).isEqualTo(body);
	}

	@Test
	@DisplayName("콘티가 객체가 아니면 field 없는 INVALID_REQUEST다")
	void 객체가_아니면_field_없음() {
		assertStoryboardInvalid(comic(ComicStatus.STORYBOARD), "[1]", null);
	}

	@Test
	@DisplayName("version이 1이 아니면 field가 version이다")
	void version이_틀리면_version() {
		assertStoryboardInvalid(comic(ComicStatus.STORYBOARD),
				"{\"version\":2,\"comicId\":100,\"cardId\":10,\"panels\":[1,2,3,4]}", "version");
	}

	@Test
	@DisplayName("version이 글자면 field가 version이다")
	void version이_글자면_version() {
		assertStoryboardInvalid(comic(ComicStatus.STORYBOARD),
				"{\"version\":\"1\",\"comicId\":100,\"cardId\":10,\"panels\":[1,2,3,4]}", "version");
	}

	@Test
	@DisplayName("comicId가 다르면 field가 comicId다")
	void comicId가_다르면_comicId() {
		assertStoryboardInvalid(comic(ComicStatus.STORYBOARD),
				"{\"version\":1,\"comicId\":101,\"cardId\":10,\"panels\":[1,2,3,4]}", "comicId");
	}

	@Test
	@DisplayName("cardId가 다르면 field가 cardId다")
	void cardId가_다르면_cardId() {
		assertStoryboardInvalid(comic(ComicStatus.STORYBOARD),
				"{\"version\":1,\"comicId\":100,\"cardId\":11,\"panels\":[1,2,3,4]}", "cardId");
	}

	@Test
	@DisplayName("카드가 지워진 만화면 cardId가 무엇이든 field가 cardId다")
	void 카드가_지워졌으면_cardId() {
		Comic comic = new Comic(null, USER_ID, "요약");
		ReflectionTestUtils.setField(comic, "id", COMIC_ID);
		assertStoryboardInvalid(comic, "{\"version\":1,\"comicId\":100,\"cardId\":10,\"panels\":[1,2,3,4]}", "cardId");
	}

	@Test
	@DisplayName("panels가 4개가 아니면 field가 panels다")
	void panels가_4개가_아니면_panels() {
		assertStoryboardInvalid(comic(ComicStatus.STORYBOARD),
				"{\"version\":1,\"comicId\":100,\"cardId\":10,\"panels\":[1,2,3]}", "panels");
	}

	// ---- image ----

	@Test
	@DisplayName("이미지를 받을 만화가 없으면 COMIC_NOT_FOUND이고 올리지 않는다")
	void 이미지_만화가_없으면_올리지_않는다() {
		// given
		given(comics.findById(COMIC_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.image(COMIC_ID, png(800, 3152)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_NOT_FOUND);
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("끝난 만화의 이미지는 검사도 업로드도 하지 않는다")
	void 끝난_만화의_이미지는_무시한다() {
		// given
		given(comics.findById(COMIC_ID)).willReturn(Optional.of(comic(ComicStatus.DONE)));

		// when
		service.image(COMIC_ID, new byte[3]);

		// then
		verifyNoInteractions(storage, push);
		verify(comics, never()).findByIdForUpdate(anyLong());
	}

	@Test
	@DisplayName("20MB를 넘으면 PAYLOAD_TOO_LARGE이고 올리지 않는다")
	void 크기를_넘으면_PAYLOAD_TOO_LARGE() {
		// given
		given(comics.findById(COMIC_ID)).willReturn(Optional.of(comic(ComicStatus.DRAWING)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.image(COMIC_ID, new byte[(int) ComicWorkerService.MAX_BYTES + 1]));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("PNG 서명이 아니면 field가 file이고 올리지 않는다")
	void PNG가_아니면_file() {
		// given
		given(comics.findById(COMIC_ID)).willReturn(Optional.of(comic(ComicStatus.DRAWING)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.image(COMIC_ID, new byte[24]));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("file");
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("크기가 800×3152가 아니면 field가 file이고 올리지 않는다")
	void 크기가_다르면_file() {
		// given
		given(comics.findById(COMIC_ID)).willReturn(Optional.of(comic(ComicStatus.DRAWING)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.image(COMIC_ID, png(800, 3151)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("file");
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("맞는 이미지는 올린 뒤 만화를 끝내고 작업을 완료하고 푸시를 보낸다")
	void 맞는_이미지는_만화를_끝낸다() {
		// given
		Comic comic = comic(ComicStatus.DRAWING);
		Job job = Job.comic(COMIC_ID, USER_ID);
		byte[] png = png(800, 3152);
		given(comics.findById(COMIC_ID)).willReturn(Optional.of(comic));
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.of(comic));
		given(jobs.findByComicId(COMIC_ID)).willReturn(Optional.of(job));

		// when
		service.image(COMIC_ID, png);

		// then
		verify(storage).putPng(KEY, png);
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.DONE);
		assertThat(comic.getImageKey()).isEqualTo(KEY);
		assertThat(job.getStatus()).isEqualTo("DONE");
		verify(push).notify(USER_ID, PushService.comicDone(COMIC_ID, CARD_ID));
		verifyNoInteractions(deletions);
	}

	@Test
	@DisplayName("올리는 사이 만화가 지워졌으면 올린 그림을 지울 줄에 넣고 COMIC_NOT_FOUND다")
	void 올리는_사이_지워지면_그림을_지운다() {
		// given
		given(comics.findById(COMIC_ID)).willReturn(Optional.of(comic(ComicStatus.DRAWING)));
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.image(COMIC_ID, png(800, 3152)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_NOT_FOUND);
		verify(deletions).schedule(KEY);
		verifyNoInteractions(push);
	}

	@Test
	@DisplayName("올리는 사이 만화가 끝났으면 오류 없이 상태·작업·푸시를 건드리지 않는다")
	void 올리는_사이_끝났으면_그대로_둔다() {
		// given
		Comic locked = comic(ComicStatus.FAILED);
		given(comics.findById(COMIC_ID)).willReturn(Optional.of(comic(ComicStatus.DRAWING)));
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.of(locked));

		// when
		service.image(COMIC_ID, png(800, 3152));

		// then
		assertThat(locked.getStatus()).isEqualTo(ComicStatus.FAILED);
		assertThat(locked.getImageKey()).isNull();
		verify(jobs, never()).findByComicId(any());
		verifyNoInteractions(push, deletions);
	}

	private void assertStoryboardInvalid(Comic comic, String json, String field) {
		// given
		given(comics.findByIdForUpdate(COMIC_ID)).willReturn(Optional.of(comic));
		ComicStatus before = comic.getStatus();

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.storyboard(COMIC_ID, objectMapper.readTree(json)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo(field);
		assertThat(comic.getStatus()).isEqualTo(before);
		assertThat(comic.getStoryboard()).isNull();
	}

	private JsonNode board() {
		return objectMapper.readTree("{\"version\":1,\"comicId\":100,\"cardId\":10,\"panels\":[{},{},{},{}]}");
	}

	private static Comic comic(ComicStatus status) {
		Comic comic = new Comic(CARD_ID, USER_ID, "요약");
		ReflectionTestUtils.setField(comic, "id", COMIC_ID);
		ReflectionTestUtils.setField(comic, "status", status);
		return comic;
	}

	private static Card card() {
		Card card = new Card(5L, USER_ID, "웃김", "요약", 0, 1000, OffsetDateTime.now());
		ReflectionTestUtils.setField(card, "id", CARD_ID);
		card.addSpeaker("화자1");
		card.addSpeaker("화자2");
		card.addLine(1, "화자1", "안녕", 0L, 500L);
		return card;
	}

	// PNG 서명과 IHDR 가로·세로만 채운 24바이트
	private static byte[] png(int width, int height) {
		ByteBuffer b = ByteBuffer.allocate(24);
		b.put(new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' });
		b.putInt(13);
		b.put(new byte[] { 'I', 'H', 'D', 'R' });
		b.putInt(width);
		b.putInt(height);
		return b.array();
	}

}
