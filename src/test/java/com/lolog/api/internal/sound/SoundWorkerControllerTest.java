package com.lolog.api.internal.sound;

import static com.lolog.api.support.InternalWebMvcTest.HEADER;
import static com.lolog.api.support.InternalWebMvcTest.TOKEN;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.sound.service.SoundService;
import com.lolog.api.domain.sound.service.SoundService.SoundMatchItem;
import com.lolog.api.domain.sound.service.SoundService.SoundMatches;
import com.lolog.api.domain.sound.service.SoundService.SoundSaved;
import com.lolog.api.domain.sound.service.SoundService.SoundSearch;
import com.lolog.api.support.InternalWebMvcTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("SoundWorkerController")
@InternalWebMvcTest(SoundWorkerController.class)
class SoundWorkerControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	SoundService sounds;

	@Test
	@DisplayName("파일과 값들을 받아 그대로 서비스에 넘기고 저장된 ID를 돌려준다")
	void 효과음을_저장한다() throws Exception {
		// given
		byte[] ogg = { 9, 8 };
		when(sounds.upsert(42L, ogg, "sfx", "door", "문", 1200, "http://src", "CC0", "[0.1]", "clap"))
			.thenReturn(new SoundSaved(3L));

		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/internal/sounds/42").file(new MockMultipartFile("file", ogg))
			.param("kind", "sfx")
			.param("query", "door")
			.param("title", "문")
			.param("durationMs", "1200")
			.param("sourceUrl", "http://src")
			.param("license", "CC0")
			.param("embedding", "[0.1]")
			.param("embeddingModel", "clap")
			.header(HEADER, TOKEN)).andExpect(status().isOk()).andExpect(jsonPath("$.id").value(3));
	}

	@Test
	@DisplayName("파일과 값이 없으면 모두 null로 넘긴다")
	void 빠진_값은_null로_넘긴다() throws Exception {
		// when
		mvc.perform(multipart(HttpMethod.PUT, "/internal/sounds/42").header(HEADER, TOKEN)).andExpect(status().isOk());

		// then
		verify(sounds).upsert(42L, null, null, null, null, null, null, null, null, null);
	}

	@Test
	@DisplayName("durationMs가 숫자가 아니면 400, field는 durationMs다")
	void durationMs가_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/internal/sounds/42").param("durationMs", "abc").header(HEADER, TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("durationMs"));

		verifyNoInteractions(sounds);
	}

	@Test
	@DisplayName("지우기는 freesound ID를 넘기고 204로 답한다")
	void 효과음을_지운다() throws Exception {
		// when
		mvc.perform(delete("/internal/sounds/42").header(HEADER, TOKEN)).andExpect(status().isNoContent());

		// then
		verify(sounds).delete(42L);
	}

	@Test
	@DisplayName("검색 본문을 서비스에 넘기고 결과를 돌려준다")
	void 검색_결과를_돌려준다() throws Exception {
		// given
		when(sounds.search(new SoundSearch("bgm", "잔잔한", 3)))
			.thenReturn(new SoundMatches(List.of(new SoundMatchItem(1L, "bgm", 30000, 0.12, "http://s/1"))));

		// when & then
		mvc.perform(post("/internal/sounds/search").header(HEADER, TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\":\"bgm\",\"text\":\"잔잔한\",\"limit\":3}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sounds[0].id").value(1))
			.andExpect(jsonPath("$.sounds[0].distance").value(0.12));
	}

	@Test
	@DisplayName("검색 본문이 없으면 null을 서비스에 넘긴다(판단은 서비스가 한다)")
	void 본문_없는_검색은_null로_넘긴다() throws Exception {
		// when
		mvc.perform(post("/internal/sounds/search").header(HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON));

		// then
		verify(sounds).search(isNull());
	}

	@Test
	@DisplayName("내부 토큰이 없으면 401이다")
	void 내부_토큰이_없으면_401이다() throws Exception {
		// when & then
		mvc.perform(delete("/internal/sounds/42")).andExpect(status().isUnauthorized());

		verifyNoInteractions(sounds);
	}

}
