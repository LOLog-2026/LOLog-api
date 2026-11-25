package com.lolog.api.domain.card.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.card.dto.CardDtos.CardUpdateRequest;
import com.lolog.api.domain.card.dto.CardDtos.SpeakerUpdate;
import com.lolog.api.domain.card.service.CardService;
import com.lolog.api.global.auth.TestTokens;
import com.lolog.api.support.AppControllerTestSupport;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("CardController")
@WebMvcTest(CardController.class)
class CardControllerTest extends AppControllerTestSupport {

	@MockitoBean
	CardService cards;

	// ---- 인증 ----

	@Test
	@DisplayName("토큰 없이 부르면 401 UNAUTHORIZED다")
	void 토큰_없이_부르면_401이다() throws Exception {
		// when & then
		mvc.perform(get("/api/cards"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		verifyNoInteractions(cards);
	}

	@Test
	@DisplayName("만료된 토큰은 401 TOKEN_EXPIRED다")
	void 만료된_토큰은_TOKEN_EXPIRED다() throws Exception {
		// given
		String expired = "Bearer " + TestTokens.token("7", "access", Duration.ofMinutes(-5), TestTokens.SECRET);

		// when & then
		mvc.perform(get("/api/cards").header(AUTH, expired))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
	}

	@Test
	@DisplayName("리프레시 토큰으로 부르면 401 UNAUTHORIZED다")
	void 리프레시_토큰은_401이다() throws Exception {
		// given
		String refresh = "Bearer " + TestTokens.token("7", "refresh", Duration.ofMinutes(30), TestTokens.SECRET);

		// when & then
		mvc.perform(get("/api/cards").header(AUTH, refresh))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	@DisplayName("탈퇴·정지 키가 Redis에 있으면 401 UNAUTHORIZED다")
	void 탈퇴한_회원은_401이다() throws Exception {
		// given
		when(redis.countExistingKeys(anyCollection())).thenReturn(1L);

		// when & then
		mvc.perform(get("/api/cards").header(AUTH, user()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		verifyNoInteractions(cards);
	}

	@Test
	@DisplayName("Redis에 못 붙으면 500 INTERNAL_ERROR다")
	void Redis에_못_붙으면_500이다() throws Exception {
		// given
		when(redis.countExistingKeys(anyCollection())).thenThrow(new RedisConnectionFailureException("down"));

		// when & then
		mvc.perform(get("/api/cards").header(AUTH, user()))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
	}

	// ---- 목록·상세·지우기 ----

	@Test
	@DisplayName("목록은 cursor·size·sort를 그대로 넘긴다")
	void 목록은_쿼리를_그대로_넘긴다() throws Exception {
		// when & then
		mvc.perform(get("/api/cards").param("cursor", "10").param("size", "5").param("sort", "oldest")
			.header(AUTH, user()))
			.andExpect(status().isOk());

		verify(cards).list(USER_ID, "10", 5, "oldest");
	}

	@Test
	@DisplayName("목록에 쿼리가 없으면 모두 null로 넘긴다")
	void 목록에_쿼리가_없으면_null이다() throws Exception {
		// when & then
		mvc.perform(get("/api/cards").header(AUTH, user())).andExpect(status().isOk());

		verify(cards).list(USER_ID, null, null, null);
	}

	@Test
	@DisplayName("상세의 경로 ID가 숫자가 아니면 400 INVALID_REQUEST이고 field는 id다")
	void 상세_ID가_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(get("/api/cards/abc").header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("id"));
	}

	@Test
	@DisplayName("상세는 경로의 카드 ID를 넘긴다")
	void 상세는_ID를_넘긴다() throws Exception {
		// when & then
		mvc.perform(get("/api/cards/3").header(AUTH, user())).andExpect(status().isOk());

		verify(cards).detail(USER_ID, 3L);
	}

	@Test
	@DisplayName("카드를 지우면 204다")
	void 지우면_204다() throws Exception {
		// when & then
		mvc.perform(delete("/api/cards/3").header(AUTH, user())).andExpect(status().isNoContent());

		verify(cards).delete(USER_ID, 3L);
	}

	// ---- PATCH: 뺀 것과 null 구분 ----

	@Test
	@DisplayName("place를 빼면 placeSent가 false다")
	void place를_빼면_안_보낸_것이다() throws Exception {
		// when
		CardUpdateRequest request = patchAndCapture("{}");

		// then
		assertThat(request.isPlaceSent()).isFalse();
		assertThat(request.getPlace()).isNull();
		assertThat(request.getSpeakers()).isEmpty();
		assertThat(request.getLines()).isEmpty();
		assertThat(request.getRemoveSpeakers()).isEmpty();
	}

	@Test
	@DisplayName("place에 null을 보내면 placeSent가 true이고 값은 null이다")
	void place에_null을_보내면_지우기다() throws Exception {
		// when
		CardUpdateRequest request = patchAndCapture("{\"place\":null}");

		// then
		assertThat(request.isPlaceSent()).isTrue();
		assertThat(request.getPlace()).isNull();
	}

	@Test
	@DisplayName("place에 값을 보내면 placeSent가 true이고 그 값이다")
	void place에_값을_보내면_바꾸기다() throws Exception {
		// when
		CardUpdateRequest request = patchAndCapture("{\"place\":\"집\"}");

		// then
		assertThat(request.isPlaceSent()).isTrue();
		assertThat(request.getPlace()).isEqualTo("집");
	}

	@Test
	@DisplayName("화자의 name과 character를 빼면 둘 다 보내지 않은 것으로 본다")
	void 화자의_name과_character를_빼면_안_보낸_것이다() throws Exception {
		// when
		SpeakerUpdate speaker = patchAndCapture("{\"speakers\":[{\"key\":\"A\"}]}").getSpeakers().get(0);

		// then
		assertThat(speaker.getKey()).isEqualTo("A");
		assertThat(speaker.isNameSent()).isFalse();
		assertThat(speaker.isCharacterSent()).isFalse();
	}

	@Test
	@DisplayName("화자의 name과 character에 null을 보내면 둘 다 보낸 것이고 값은 null이다")
	void 화자의_name과_character에_null을_보내면_보낸_것이다() throws Exception {
		// when
		SpeakerUpdate speaker = patchAndCapture("{\"speakers\":[{\"key\":\"A\",\"name\":null,\"character\":null}]}")
			.getSpeakers()
			.get(0);

		// then
		assertThat(speaker.isNameSent()).isTrue();
		assertThat(speaker.getName()).isNull();
		assertThat(speaker.isCharacterSent()).isTrue();
		assertThat(speaker.getCharacter()).isNull();
	}

	@Test
	@DisplayName("화자의 character를 보내면 type과 id를 읽는다")
	void 화자의_character를_읽는다() throws Exception {
		// when
		SpeakerUpdate speaker = patchAndCapture(
				"{\"speakers\":[{\"key\":\"A\",\"character\":{\"type\":\"default\",\"id\":\"boy\"}}]}")
			.getSpeakers()
			.get(0);

		// then
		assertThat(speaker.isCharacterSent()).isTrue();
		assertThat(speaker.getCharacter().type()).isEqualTo("default");
		assertThat(speaker.getCharacter().id()).isEqualTo("boy");
	}

	// ---- PATCH: 검증 ----

	@Test
	@DisplayName("화자의 key가 없으면 400 INVALID_REQUEST이고 field는 speakers[0].key다")
	void 화자의_key가_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(patch("/api/cards/3").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"speakers\":[{\"name\":\"엄마\"}]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("speakers[0].key"));

		verifyNoInteractions(cards);
	}

	@Test
	@DisplayName("대사의 id가 없으면 400 INVALID_REQUEST이고 field는 lines[1].id다")
	void 대사의_id가_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(patch("/api/cards/3").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"lines\":[{\"id\":1,\"text\":\"a\"},{\"text\":\"b\"}]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("lines[1].id"));

		verifyNoInteractions(cards);
	}

	@Test
	@DisplayName("대사의 id가 숫자가 아니면 400 INVALID_REQUEST이고 field는 lines[0].id다")
	void 대사의_id가_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(patch("/api/cards/3").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"lines\":[{\"id\":\"abc\"}]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("lines[0].id"));
	}

	@Test
	@DisplayName("서비스에는 내 회원 ID와 경로의 카드 ID를 넘긴다")
	void 서비스에_회원_ID와_카드_ID를_넘긴다() throws Exception {
		// when & then
		mvc.perform(patch("/api/cards/3").header(AUTH, user()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk());

		verify(cards).update(eq(USER_ID), eq(3L), org.mockito.ArgumentMatchers.any(CardUpdateRequest.class));
	}

	// PATCH를 보내고 서비스가 받은 요청을 돌려준다.
	private CardUpdateRequest patchAndCapture(String body) throws Exception {
		mvc.perform(patch("/api/cards/3").header(AUTH, user()).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isOk());
		ArgumentCaptor<CardUpdateRequest> captor = ArgumentCaptor.forClass(CardUpdateRequest.class);
		verify(cards).update(eq(USER_ID), eq(3L), captor.capture());
		return captor.getValue();
	}

}
