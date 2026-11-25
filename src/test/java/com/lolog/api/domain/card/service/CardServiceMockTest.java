package com.lolog.api.domain.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lolog.api.domain.audio.service.CardAudioViews;
import com.lolog.api.domain.card.dto.CardDtos.CardDetail;
import com.lolog.api.domain.card.dto.CardDtos.CardUpdateRequest;
import com.lolog.api.domain.card.dto.CardDtos.CharacterRef;
import com.lolog.api.domain.card.dto.CardDtos.LineUpdate;
import com.lolog.api.domain.card.dto.CardDtos.SpeakerUpdate;
import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.character.service.CharacterViews;
import com.lolog.api.domain.character.service.CharacterViews.Ref;
import com.lolog.api.domain.comic.entity.ComicStatus;
import com.lolog.api.domain.comic.repository.ComicRepository;
import com.lolog.api.domain.text.service.TextService;
import com.lolog.api.domain.video.service.VideoService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.StorageDeletions;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("CardService")
@ExtendWith(MockitoExtension.class)
class CardServiceMockTest {

	private static final long USER = 7L;
	private static final long CARD = 100L;

	@Mock
	CardRepository cards;
	@Mock
	ComicRepository comics;
	@Mock
	TextService texts;
	@Mock
	StorageDeletions deletions;
	@Mock
	CharacterViews characters;
	@Mock
	CardAudioViews audio;
	@Mock
	EntityManager entityManager;
	@Mock
	VideoService videos;

	@InjectMocks
	CardService service;

	// ---- update: 요청 전체 ----

	@Test
	@DisplayName("바꿀 것이 하나도 없으면 카드를 찾지 않고 field 없는 INVALID_REQUEST다")
	void 빈_요청은_카드를_찾지_않는다() {
		// when & then
		assertError(() -> service.update(USER, CARD, new CardUpdateRequest()), ErrorCode.INVALID_REQUEST, null);
		verify(cards, never()).findByIdAndUserIdForUpdate(anyLong(), anyLong());
	}

	// ---- findLocked ----

	@Test
	@DisplayName("내 카드가 아니면 CARD_NOT_FOUND다")
	void 없는_카드는_CARD_NOT_FOUND다() {
		// given
		when(cards.findByIdAndUserIdForUpdate(CARD, USER)).thenReturn(Optional.empty());

		// when & then
		assertError(() -> service.update(USER, CARD, placeRequest("집")), ErrorCode.CARD_NOT_FOUND, null);
	}

	@Test
	@DisplayName("만화를 만드는 중이면 영상은 보지 않고 CARD_LOCKED다")
	void 만화_진행_중이면_CARD_LOCKED다() {
		// given
		Card card = stored(cardWithSpeakers(2));
		when(comics.existsByCardIdAndStatusIn(CARD, ComicStatus.IN_PROGRESS)).thenReturn(true);

		// when & then
		assertError(() -> service.update(USER, CARD, placeRequest("집")), ErrorCode.CARD_LOCKED, null);
		verify(videos, never()).hasRunningBeforeDrawing(anyLong());
		assertThat(card.getPlace()).isNull();
	}

	@Test
	@DisplayName("영상이 목소리를 만드는 중이면 CARD_LOCKED다")
	void 영상_진행_중이면_CARD_LOCKED다() {
		// given
		stored(cardWithSpeakers(2));
		when(videos.hasRunningBeforeDrawing(CARD)).thenReturn(true);

		// when & then
		assertError(() -> service.update(USER, CARD, placeRequest("집")), ErrorCode.CARD_LOCKED, null);
	}

	// ---- update: 화자 ----

	@Test
	@DisplayName("같은 화자 key를 두 번 보내면 두 번째 key가 틀린 것이다")
	void 같은_화자_key를_두_번_보내면_INVALID다() {
		// given
		stored(cardWithSpeakers(2));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(name("화자1", "엄마"), name("화자1", "아빠")));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST, "speakers[1].key");
	}

	@Test
	@DisplayName("새 화자 번호를 건너뛰면 UNKNOWN_SPEAKER다")
	void 새_화자_번호를_건너뛰면_UNKNOWN_SPEAKER다() {
		// given
		stored(cardWithSpeakers(2));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(name("화자4", "삼촌")));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.UNKNOWN_SPEAKER, "speakers[0].key");
	}

	@Test
	@DisplayName("기존 화자에 이름도 그림도 보내지 않으면 그 화자 항목이 틀린 것이다")
	void 기존_화자에_바꿀_것이_없으면_INVALID다() {
		// given
		stored(cardWithSpeakers(2));
		SpeakerUpdate update = new SpeakerUpdate();
		update.setKey("화자1");
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(update));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST, "speakers[0]");
	}

	@Test
	@DisplayName("화자 이름이 공백을 빼고 비어 있으면 name이 틀린 것이다")
	void 공백뿐인_이름은_INVALID다() {
		// given
		stored(cardWithSpeakers(2));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(name("화자1", "   ")));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST, "speakers[0].name");
	}

	@Test
	@DisplayName("화자 이름이 10자를 넘으면 name이 틀린 것이다")
	void 열한_자_이름은_INVALID다() {
		// given
		stored(cardWithSpeakers(2));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(name("화자1", "가".repeat(11))));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST, "speakers[0].name");
	}

	@Test
	@DisplayName("그림 type이 DEFAULT·PROFILE이 아니면 붙일 수 있는지 묻지 않고 type이 틀린 것이다")
	void 모르는_그림_type은_INVALID다() {
		// given
		stored(cardWithSpeakers(2));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(character("화자1", new CharacterRef("OTHER", "a"))));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST,
				"speakers[0].character.type");
		verify(characters, never()).checkAttachable(anyLong(), any(), anyString());
	}

	@Test
	@DisplayName("그림 id가 비어 있으면 id가 틀린 것이다")
	void 빈_그림_id는_INVALID다() {
		// given
		stored(cardWithSpeakers(2));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(character("화자1", new CharacterRef("DEFAULT", " "))));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST,
				"speakers[0].character.id");
	}

	@Test
	@DisplayName("그림을 붙일 수 있는지 회원 ID·그림·field 경로를 넘겨 묻는다")
	void 그림을_붙일_수_있는지_묻는다() {
		// given
		stored(cardWithSpeakers(2));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(character("화자2", new CharacterRef("PROFILE", "9"))));
		doThrow(new ApiException(ErrorCode.CHARACTER_NOT_FOUND, "speakers[0].character")).when(characters)
			.checkAttachable(USER, new Ref("PROFILE", "9"), "speakers[0].character");

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.CHARACTER_NOT_FOUND,
				"speakers[0].character");
	}

	@Test
	@DisplayName("그림을 떼는 것(null)은 붙일 수 있는지 묻지 않는다")
	void 그림을_떼면_묻지_않는다() {
		// given
		Card card = stored(cardWithSpeakers(2));
		card.getSpeakers().get(0).setCharacter("DEFAULT", "a");
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(character("화자1", null)));

		// when
		service.update(USER, CARD, request);

		// then
		verify(characters, never()).checkAttachable(anyLong(), any(), anyString());
		assertThat(card.getSpeakers().get(0).getCharacterType()).isNull();
		assertThat(card.getSpeakers().get(0).getCharacterId()).isNull();
	}

	// ---- update: 화자 빼기 ----

	@Test
	@DisplayName("뺄 화자를 두 번 보내면 두 번째 항목이 틀린 것이다")
	void 뺄_화자를_두_번_보내면_INVALID다() {
		// given
		stored(cardWithSpeakers(3));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setRemoveSpeakers(List.of("화자3", "화자3"));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST, "removeSpeakers[1]");
	}

	@Test
	@DisplayName("카드에 없는 화자를 빼면 UNKNOWN_SPEAKER다")
	void 없는_화자를_빼면_UNKNOWN_SPEAKER다() {
		// given
		stored(cardWithSpeakers(2));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setRemoveSpeakers(List.of("화자3"));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.UNKNOWN_SPEAKER, "removeSpeakers[0]");
	}

	@Test
	@DisplayName("같은 요청에서 고치는 화자를 빼면 INVALID_REQUEST다")
	void 고치는_화자를_빼면_INVALID다() {
		// given
		stored(cardWithSpeakers(3));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(name("화자3", "엄마")));
		request.setRemoveSpeakers(List.of("화자3"));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST, "removeSpeakers[0]");
	}

	@Test
	@DisplayName("화자를 모두 빼면 removeSpeakers가 틀린 것이다")
	void 화자를_모두_빼면_INVALID다() {
		// given
		stored(cardWithSpeakers(1));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setRemoveSpeakers(List.of("화자1"));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST, "removeSpeakers");
	}

	@Test
	@DisplayName("대사가 남은 화자를 빼면 SPEAKER_HAS_LINES다")
	void 대사가_남은_화자를_빼면_SPEAKER_HAS_LINES다() {
		// given
		Card card = cardWithSpeakers(2);
		line(card, 11L, "화자2", "안녕");
		stored(card);
		CardUpdateRequest request = new CardUpdateRequest();
		request.setRemoveSpeakers(List.of("화자2"));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.SPEAKER_HAS_LINES, null);
	}

	@Test
	@DisplayName("빼는 화자의 대사를 같은 요청에서 다른 화자로 옮기면 뺄 수 있다")
	void 대사를_옮기면_화자를_뺄_수_있다() {
		// given
		Card card = cardWithSpeakers(2);
		line(card, 11L, "화자2", "안녕");
		stored(card);
		when(cards.findById(CARD)).thenReturn(Optional.of(card));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setLines(List.of(new LineUpdate(11L, "화자1", null)));
		request.setRemoveSpeakers(List.of("화자2"));

		// when
		service.update(USER, CARD, request);

		// then
		assertThat(card.getLines().get(0).getSpeakerKey()).isEqualTo("화자1");
		assertThat(card.getSpeakers()).extracting("speakerKey").containsExactly("화자1");
	}

	@Test
	@DisplayName("가운데 화자를 빼면 지운 줄을 먼저 flush한 뒤 뒤 화자의 key를 당기고 다시 읽는다")
	void 가운데_화자를_빼면_key를_다시_매긴다() {
		// given
		Card card = stored(cardWithSpeakers(3));
		when(cards.findSpeakerKeys(CARD)).thenReturn(List.of("화자3", "화자1"));
		when(cards.findById(CARD)).thenReturn(Optional.of(card));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setRemoveSpeakers(List.of("화자2"));

		// when
		service.update(USER, CARD, request);

		// then
		InOrder order = inOrder(entityManager, cards);
		order.verify(entityManager).flush();
		order.verify(cards).renameSpeaker(CARD, "화자3", "화자2");
		order.verify(cards).renameLineSpeaker(CARD, "화자3", "화자2");
		order.verify(entityManager).clear();
		order.verify(cards).findById(CARD);
		verify(cards, never()).renameSpeaker(CARD, "화자1", "화자1");
	}

	@Test
	@DisplayName("화자를 빼지 않으면 flush하지 않고 key도 다시 매기지 않는다")
	void 화자를_빼지_않으면_다시_매기지_않는다() {
		// given
		stored(cardWithSpeakers(2));

		// when
		service.update(USER, CARD, placeRequest("집"));

		// then
		verify(entityManager, never()).flush();
		verify(cards, never()).findSpeakerKeys(anyLong());
	}

	// ---- update: 새 화자 ----

	@Test
	@DisplayName("새 화자에게 같은 요청에서 옮긴 대사가 없으면 그 화자 항목이 틀린 것이다")
	void 대사_없는_새_화자는_INVALID다() {
		// given
		stored(cardWithSpeakers(2));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(name("화자3", "삼촌")));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST, "speakers[0]");
	}

	@Test
	@DisplayName("새 화자를 더해 5명이 되면 SPEAKER_LIMIT다")
	void 다섯_번째_화자는_SPEAKER_LIMIT다() {
		// given
		Card card = cardWithSpeakers(4);
		line(card, 11L, "화자1", "안녕");
		stored(card);
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(name("화자5", "삼촌")));
		request.setLines(List.of(new LineUpdate(11L, "화자5", null)));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.SPEAKER_LIMIT, null);
	}

	@Test
	@DisplayName("새 화자는 이름 없이도 만들고, 대사를 그 화자로 옮긴다")
	void 새_화자를_만들고_대사를_옮긴다() {
		// given
		Card card = cardWithSpeakers(2);
		line(card, 11L, "화자1", "안녕");
		stored(card);
		SpeakerUpdate added = new SpeakerUpdate();
		added.setKey("화자3");
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(added));
		request.setLines(List.of(new LineUpdate(11L, "화자3", null)));

		// when
		CardDetail detail = service.update(USER, CARD, request);

		// then
		assertThat(detail.speakers()).extracting("key").containsExactly("화자1", "화자2", "화자3");
		assertThat(detail.speakers().get(2).name()).isNull();
		assertThat(detail.lines().get(0).speaker()).isEqualTo("화자3");
	}

	// ---- update: 대사·장소 ----

	@Test
	@DisplayName("같은 대사 id를 두 번 보내면 두 번째 id가 틀린 것이다")
	void 같은_대사_id를_두_번_보내면_INVALID다() {
		// given
		Card card = cardWithSpeakers(2);
		line(card, 11L, "화자1", "안녕");
		stored(card);
		CardUpdateRequest request = new CardUpdateRequest();
		request.setLines(List.of(new LineUpdate(11L, null, "가"), new LineUpdate(11L, null, "나")));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST, "lines[1].id");
	}

	@Test
	@DisplayName("카드에 없는 대사 id면 id가 틀린 것이다")
	void 없는_대사_id는_INVALID다() {
		// given
		stored(cardWithSpeakers(2));
		CardUpdateRequest request = new CardUpdateRequest();
		request.setLines(List.of(new LineUpdate(99L, null, "가")));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST, "lines[0].id");
	}

	@Test
	@DisplayName("대사를 카드에도 요청에도 없는 화자로 옮기면 UNKNOWN_SPEAKER다")
	void 없는_화자로_대사를_옮기면_UNKNOWN_SPEAKER다() {
		// given
		Card card = cardWithSpeakers(2);
		line(card, 11L, "화자1", "안녕");
		stored(card);
		CardUpdateRequest request = new CardUpdateRequest();
		request.setLines(List.of(new LineUpdate(11L, "화자3", null)));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.UNKNOWN_SPEAKER, "lines[0].speaker");
	}

	@Test
	@DisplayName("대사가 200자를 넘으면 text가 틀린 것이다")
	void 이백일_자_대사는_INVALID다() {
		// given
		Card card = cardWithSpeakers(2);
		line(card, 11L, "화자1", "안녕");
		stored(card);
		CardUpdateRequest request = new CardUpdateRequest();
		request.setLines(List.of(new LineUpdate(11L, null, "가".repeat(201))));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST, "lines[0].text");
	}

	@Test
	@DisplayName("장소가 20자를 넘으면 place가 틀린 것이다")
	void 스물한_자_장소는_INVALID다() {
		// given
		stored(cardWithSpeakers(2));

		// when & then
		assertError(() -> service.update(USER, CARD, placeRequest("가".repeat(21))), ErrorCode.INVALID_REQUEST,
				"place");
	}

	@Test
	@DisplayName("뒤 항목이 틀리면 앞 항목도 바꾸지 않는다")
	void 하나라도_틀리면_아무것도_바꾸지_않는다() {
		// given
		Card card = cardWithSpeakers(2);
		line(card, 11L, "화자1", "안녕");
		stored(card);
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(name("화자1", "엄마")));
		request.setLines(List.of(new LineUpdate(11L, null, "새 대사")));
		request.setPlace("가".repeat(21));

		// when & then
		assertError(() -> service.update(USER, CARD, request), ErrorCode.INVALID_REQUEST, "place");
		assertThat(card.getSpeakers().get(0).getName()).isNull();
		assertThat(card.getLines().get(0).getText()).isEqualTo("안녕");
	}

	@Test
	@DisplayName("이름·대사·장소는 앞뒤 공백을 빼고 저장하고, 장소 null은 지운다")
	void 공백을_빼고_저장하고_장소_null은_지운다() {
		// given
		Card card = cardWithSpeakers(2);
		card.setPlace("학교");
		line(card, 11L, "화자1", "안녕");
		stored(card);
		CardUpdateRequest request = new CardUpdateRequest();
		request.setSpeakers(List.of(name("화자1", "  엄마 ")));
		request.setLines(List.of(new LineUpdate(11L, null, " 새 대사 ")));
		request.setPlace(null);

		// when
		service.update(USER, CARD, request);

		// then
		assertThat(card.getSpeakers().get(0).getName()).isEqualTo("엄마");
		assertThat(card.getLines().get(0).getText()).isEqualTo("새 대사");
		assertThat(card.getPlace()).isNull();
	}

	// ---- delete ----

	@Test
	@DisplayName("잠긴 카드는 지우지 않는다")
	void 잠긴_카드는_지우지_않는다() {
		// given
		stored(cardWithSpeakers(1));
		when(comics.existsByCardIdAndStatusIn(CARD, ComicStatus.IN_PROGRESS)).thenReturn(true);

		// when & then
		assertError(() -> service.delete(USER, CARD), ErrorCode.CARD_LOCKED, null);
		verify(cards, never()).delete(any());
	}

	@Test
	@DisplayName("녹음 카드는 글을 지우지 않고, 음성이 없으면 지울 파일도 없다")
	void 녹음_카드는_카드만_지운다() {
		// given
		Card card = stored(cardWithSpeakers(1));

		// when
		service.delete(USER, CARD);

		// then
		verify(cards).delete(card);
		verify(entityManager, never()).flush();
		verify(texts, never()).delete(anyLong());
		verify(deletions, never()).schedule(anyString());
	}

	@Test
	@DisplayName("글 카드는 카드를 지우고 flush한 뒤 글을 지우고, 구간 음성은 지울 목록에 넣는다")
	void 글_카드는_글과_음성도_지운다() {
		// given
		Card card = Card.fromText(55L, USER, "일상", "요약", null, OffsetDateTime.now());
		card.addSpeaker("화자1");
		ReflectionTestUtils.setField(card, "audioKey", "audio/100.aac");
		stored(card);

		// when
		service.delete(USER, CARD);

		// then
		InOrder order = inOrder(cards, entityManager, texts, deletions);
		order.verify(cards).delete(card);
		order.verify(entityManager).flush();
		order.verify(texts).delete(55L);
		order.verify(deletions).schedule(eq("audio/100.aac"));
	}

	// ---- 도움 ----

	private Card stored(Card card) {
		ReflectionTestUtils.setField(card, "id", CARD);
		when(cards.findByIdAndUserIdForUpdate(CARD, USER)).thenReturn(Optional.of(card));
		return card;
	}

	private static Card cardWithSpeakers(int count) {
		Card card = new Card(1L, USER, "일상", "요약", 0, 1000, OffsetDateTime.now());
		for (int i = 1; i <= count; i++) {
			card.addSpeaker("화자" + i);
		}
		return card;
	}

	private static void line(Card card, long id, String speaker, String text) {
		card.addLine(card.getLines().size() + 1, speaker, text, null, null);
		ReflectionTestUtils.setField(card.getLines().getLast(), "id", id);
	}

	private static CardUpdateRequest placeRequest(String place) {
		CardUpdateRequest request = new CardUpdateRequest();
		request.setPlace(place);
		return request;
	}

	private static SpeakerUpdate name(String key, String name) {
		SpeakerUpdate update = new SpeakerUpdate();
		update.setKey(key);
		update.setName(name);
		return update;
	}

	private static SpeakerUpdate character(String key, CharacterRef ref) {
		SpeakerUpdate update = new SpeakerUpdate();
		update.setKey(key);
		update.setCharacter(ref);
		return update;
	}

	private static void assertError(ThrowingCallable call, ErrorCode code, String field) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
			assertThat(e.getErrorCode()).isEqualTo(code);
			assertThat(e.getField()).isEqualTo(field);
		});
	}

}
