package com.lolog.api.domain.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lolog.api.domain.card.dto.CardDtos.CharacterRef;
import com.lolog.api.domain.card.dto.CardDtos.SpeakerUpdate;
import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CardService")
class CardServiceTest {

	private static final CharacterRef A = new CharacterRef("DEFAULT", "a");
	private static final CharacterRef B = new CharacterRef("DEFAULT", "b");

	// ---- checkNoDuplicateCharacter ----

	@Test
	@DisplayName("두 화자의 그림을 한 요청에서 맞바꾸면 통과한다")
	void 두_화자의_그림을_한_요청에서_맞바꾸면_통과한다() {
		// given
		Card card = card(A, B);

		// when & then
		assertThatCode(() -> CardService.checkNoDuplicateCharacter(card,
				List.of(character("화자1", B), character("화자2", A)), Set.of()))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("그림 가진 화자를 빼면서 그 그림을 다른 화자에 주면 통과한다")
	void 그림_가진_화자를_빼면서_그_그림을_다른_화자에_주면_통과한다() {
		// given
		Card card = card(A, null);

		// when & then
		assertThatCode(() -> CardService.checkNoDuplicateCharacter(card, List.of(character("화자2", A)),
				Set.of("화자1")))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("한 화자만 바꿔 기존 화자와 겹치면 DUPLICATE CHARACTER다")
	void 한_화자만_바꿔_기존_화자와_겹치면_DUPLICATE_CHARACTER다() {
		// given
		Card card = card(A, B);

		// when & then
		// 첫 변경은 이름만 바꿔서 겹친 변경의 순번이 1이 되게 한다.
		assertThatThrownBy(() -> CardService.checkNoDuplicateCharacter(card,
				List.of(name("화자1", "엄마"), character("화자2", A)), Set.of()))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_CHARACTER);
				assertThat(e.getField()).isEqualTo("speakers[1].character");
			});
	}

	@Test
	@DisplayName("한 화자의 그림을 떼면서 그 그림을 다른 화자에 주면 통과한다")
	void 한_화자의_그림을_떼면서_그_그림을_다른_화자에_주면_통과한다() {
		// given
		Card card = card(A, B);

		// when & then
		assertThatCode(() -> CardService.checkNoDuplicateCharacter(card,
				List.of(character("화자1", null), character("화자2", A)), Set.of()))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("그림 없는 두 화자에 같은 그림을 한꺼번에 붙이면 DUPLICATE CHARACTER다")
	void 그림_없는_두_화자에_같은_그림을_한꺼번에_붙이면_DUPLICATE_CHARACTER다() {
		// given
		Card card = card(null, null);

		// when & then
		assertThatThrownBy(() -> CardService.checkNoDuplicateCharacter(card,
				List.of(character("화자1", A), character("화자2", A)), Set.of()))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.DUPLICATE_CHARACTER);
				assertThat(e.getField()).isEqualTo("speakers[0].character");
			});
	}

	@Test
	@DisplayName("type이나 id가 다르면 같은 그림이 아니다")
	void type이나_id가_다르면_같은_그림이_아니다() {
		// given
		Card card = card(new CharacterRef("DEFAULT", "1"), null);

		// when & then
		assertThatCode(() -> CardService.checkNoDuplicateCharacter(card,
				List.of(character("화자2", new CharacterRef("PROFILE", "1"))), Set.of()))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("두 화자가 그림을 떼면 겹치지 않는다")
	void 두_화자가_그림을_떼면_겹치지_않는다() {
		// given
		Card card = card(A, B);

		// when & then
		assertThatCode(() -> CardService.checkNoDuplicateCharacter(card,
				List.of(character("화자1", null), character("화자2", null)), Set.of()))
			.doesNotThrowAnyException();
	}

	// ---- speakerNumber ----

	@Test
	@DisplayName("화자10은 화자2보다 뒤에 온다")
	void 화자10은_화자2보다_뒤에_온다() {
		// when & then
		assertThat(CardService.speakerNumber("화자10")).isEqualTo(10);
		assertThat(CardService.speakerNumber("화자2")).isEqualTo(2);
		assertThat(List.of("화자10", "화자2", "화자1").stream()
			.sorted(Comparator.comparingInt(CardService::speakerNumber))
			.toList()).containsExactly("화자1", "화자2", "화자10");
	}

	// 화자1·화자2를 둔 카드. null이면 그림 없음
	private static Card card(CharacterRef first, CharacterRef second) {
		Card card = new Card(1L, 1L, "일상", "요약", 0, 1000, OffsetDateTime.now());
		card.addSpeaker("화자1");
		card.addSpeaker("화자2");
		if (first != null) {
			card.getSpeakers().get(0).setCharacter(first.type(), first.id());
		}
		if (second != null) {
			card.getSpeakers().get(1).setCharacter(second.type(), second.id());
		}
		return card;
	}

	private static SpeakerUpdate character(String key, CharacterRef ref) {
		SpeakerUpdate update = new SpeakerUpdate();
		update.setKey(key);
		update.setCharacter(ref);
		return update;
	}

	private static SpeakerUpdate name(String key, String name) {
		SpeakerUpdate update = new SpeakerUpdate();
		update.setKey(key);
		update.setName(name);
		return update;
	}

}
