package com.lolog.api.domain.character.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.character.service.DefaultCharacterService;
import com.lolog.api.domain.character.service.ProfileCharacterService;
import com.lolog.api.domain.character.service.VoiceCloneService;
import com.lolog.api.support.AppControllerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("CharacterController")
@WebMvcTest(CharacterController.class)
class CharacterControllerTest extends AppControllerTestSupport {

	private static final byte[] BYTES = { 9, 8, 7 };

	@MockitoBean
	DefaultCharacterService defaults;

	@MockitoBean
	ProfileCharacterService profileCharacters;

	@MockitoBean
	VoiceCloneService voiceClones;

	@Test
	@DisplayName("토큰 없이 기본 화자 목록을 부르면 401 UNAUTHORIZED다")
	void 토큰_없이_부르면_401이다() throws Exception {
		// when & then
		mvc.perform(get("/api/characters/defaults")).andExpect(status().isUnauthorized());

		verifyNoInteractions(defaults);
	}

	@Test
	@DisplayName("기본 화자 목록은 200이다")
	void 기본_화자_목록은_200이다() throws Exception {
		// when & then
		mvc.perform(get("/api/characters/defaults").header(AUTH, user())).andExpect(status().isOk());

		verify(defaults).list();
	}

	@Test
	@DisplayName("사진을 올리면 202이고 사진 바이트를 넘긴다")
	void 사진을_올리면_202다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/api/me/profile/character")
			.file(new MockMultipartFile("photo", "p.jpg", "image/jpeg", BYTES))
			.header(AUTH, user()))
			.andExpect(status().isAccepted());

		verify(profileCharacters).upload(USER_ID, BYTES);
	}

	@Test
	@DisplayName("photo 파트가 없으면 400 INVALID_REQUEST이고 field는 photo다")
	void photo_파트가_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/api/me/profile/character")
			.file(new MockMultipartFile("image", "p.jpg", "image/jpeg", BYTES))
			.header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("photo"));

		verifyNoInteractions(profileCharacters);
	}

	@Test
	@DisplayName("목소리를 고르면 204이고 본문의 voice를 넘긴다")
	void 목소리를_고르면_204다() throws Exception {
		// when & then
		mvc.perform(patch("/api/me/profile/character/voice").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"voice\":\"self\"}"))
			.andExpect(status().isNoContent());

		verify(profileCharacters).chooseVoice(USER_ID, "self");
	}

	@Test
	@DisplayName("voice에 null을 보내면 null로 넘긴다")
	void voice에_null을_보내면_null로_넘긴다() throws Exception {
		// when & then
		mvc.perform(patch("/api/me/profile/character/voice").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"voice\":null}"))
			.andExpect(status().isNoContent());

		verify(profileCharacters).chooseVoice(USER_ID, null);
	}

	@Test
	@DisplayName("내 목소리 녹음을 올리면 202이고 음성 바이트와 consent를 넘긴다")
	void 내_목소리를_올리면_202다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/api/me/profile/character/voice-clone")
			.file(new MockMultipartFile("audio", "v.aac", "audio/aac", BYTES))
			.param("consent", "true")
			.header(AUTH, user()))
			.andExpect(status().isAccepted());

		verify(voiceClones).upload(USER_ID, BYTES, true);
	}

	@Test
	@DisplayName("내 목소리 audio 파트와 consent가 없으면 둘 다 null로 넘긴다")
	void 내_목소리_파트가_없으면_null로_넘긴다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/api/me/profile/character/voice-clone").header(AUTH, user()))
			.andExpect(status().isAccepted());

		verify(voiceClones).upload(USER_ID, null, null);
	}

	@Test
	@DisplayName("consent가 true·false가 아니면 400 INVALID_REQUEST이고 field는 consent다")
	void consent가_틀리면_400이다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/api/me/profile/character/voice-clone")
			.param("consent", "maybe")
			.header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("consent"));

		verifyNoInteractions(voiceClones);
	}

	@Test
	@DisplayName("내 목소리를 지우면 204다")
	void 내_목소리를_지우면_204다() throws Exception {
		// when & then
		mvc.perform(delete("/api/me/profile/character/voice-clone").header(AUTH, user()))
			.andExpect(status().isNoContent());

		verify(voiceClones).delete(USER_ID);
	}

	@Test
	@DisplayName("내 목소리 공유를 바꾸면 204이고 shareWithFriends를 넘긴다")
	void 공유를_바꾸면_204다() throws Exception {
		// when & then
		mvc.perform(patch("/api/me/profile/character/voice-clone").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"shareWithFriends\":true}"))
			.andExpect(status().isNoContent());

		verify(voiceClones).share(USER_ID, true);
	}

	@Test
	@DisplayName("shareWithFriends를 빼면 null로 넘긴다")
	void 공유_값을_빼면_null로_넘긴다() throws Exception {
		// when & then
		mvc.perform(patch("/api/me/profile/character/voice-clone").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isNoContent());

		verify(voiceClones).share(USER_ID, null);
	}

	@Test
	@DisplayName("프로필 캐릭터를 지우면 204다")
	void 프로필_캐릭터를_지우면_204다() throws Exception {
		// when & then
		mvc.perform(delete("/api/me/profile/character").header(AUTH, user())).andExpect(status().isNoContent());

		verify(profileCharacters).delete(USER_ID);
	}

}
