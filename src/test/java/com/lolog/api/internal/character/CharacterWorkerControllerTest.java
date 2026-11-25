package com.lolog.api.internal.character;

import static com.lolog.api.support.InternalWebMvcTest.HEADER;
import static com.lolog.api.support.InternalWebMvcTest.TOKEN;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.character.service.DefaultCharacterService;
import com.lolog.api.domain.character.service.ProfileCharacterService;
import com.lolog.api.domain.character.service.VoiceCloneService;
import com.lolog.api.global.storage.ImageStorage.StoredObject;
import com.lolog.api.support.InternalWebMvcTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("CharacterWorkerController")
@InternalWebMvcTest(CharacterWorkerController.class)
class CharacterWorkerControllerTest {

	private static final byte[] BYTES = { 1, 2, 3 };

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ProfileCharacterService profileCharacters;

	@MockitoBean
	DefaultCharacterService defaults;

	@MockitoBean
	VoiceCloneService voiceClones;

	@Test
	@DisplayName("사진을 저장된 콘텐츠 타입 그대로 돌려준다")
	void 사진을_돌려준다() throws Exception {
		// given
		when(profileCharacters.photo(5L)).thenReturn(new StoredObject(BYTES, "image/jpeg"));

		// when & then
		mvc.perform(get("/internal/users/5/character/photo").header(HEADER, TOKEN))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/jpeg"))
			.andExpect(content().bytes(BYTES));
	}

	@Test
	@DisplayName("아바타 파일과 생김새를 넘기고 204로 답한다")
	void 아바타를_넘긴다() throws Exception {
		// when
		mvc.perform(multipart("/internal/users/5/character/avatar").file(new MockMultipartFile("file", BYTES))
			.param("appearance", "단발")
			.header(HEADER, TOKEN)).andExpect(status().isNoContent());

		// then
		verify(profileCharacters).avatar(5L, BYTES, "단발");
	}

	@Test
	@DisplayName("아바타 file 파트가 없으면 400, field는 file이다")
	void 아바타_파일이_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(multipart("/internal/users/5/character/avatar").header(HEADER, TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("file"));

		verifyNoInteractions(profileCharacters);
	}

	@Test
	@DisplayName("목소리 샘플을 저장된 콘텐츠 타입 그대로 돌려준다")
	void 목소리_샘플을_돌려준다() throws Exception {
		// given
		when(voiceClones.sample(5L)).thenReturn(new StoredObject(BYTES, "audio/wav"));

		// when & then
		mvc.perform(get("/internal/users/5/voice-clone/sample").header(HEADER, TOKEN))
			.andExpect(status().isOk())
			.andExpect(content().contentType("audio/wav"));
	}

	@Test
	@DisplayName("미리 듣기 파일을 PUT multipart로 받아 넘기고 204로 답한다")
	void 미리_듣기를_넘긴다() throws Exception {
		// when
		mvc.perform(multipart(HttpMethod.PUT, "/internal/users/5/voice-clone/preview")
			.file(new MockMultipartFile("file", BYTES))
			.header(HEADER, TOKEN)).andExpect(status().isNoContent());

		// then
		verify(voiceClones).preview(5L, BYTES);
	}

	@Test
	@DisplayName("기본 캐릭터를 파트와 값으로 넘기고 204로 답한다")
	void 기본_캐릭터를_넘긴다() throws Exception {
		// when
		mvc.perform(multipart(HttpMethod.PUT, "/internal/characters/defaults/girl-1")
			.file(new MockMultipartFile("image", BYTES))
			.param("ageGroup", "20s")
			.param("gender", "F")
			.param("glasses", "true")
			.param("label", "여자1")
			.param("appearance", "긴 머리")
			.param("sortOrder", "2")
			.header(HEADER, TOKEN)).andExpect(status().isNoContent());

		// then
		verify(defaults).upsert("girl-1", BYTES, null, null, "20s", "F", true, "여자1", "긴 머리", 2);
	}

	@Test
	@DisplayName("기본 캐릭터의 sortOrder가 숫자가 아니면 400, field는 sortOrder다")
	void 기본_캐릭터_순서가_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/internal/characters/defaults/girl-1").param("sortOrder", "x")
			.header(HEADER, TOKEN)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sortOrder"));

		verifyNoInteractions(defaults);
	}

	@Test
	@DisplayName("내부 토큰이 없으면 401이다")
	void 내부_토큰이_없으면_401이다() throws Exception {
		// when & then
		mvc.perform(get("/internal/users/5/character/photo")).andExpect(status().isUnauthorized());

		verifyNoInteractions(profileCharacters);
	}

}
