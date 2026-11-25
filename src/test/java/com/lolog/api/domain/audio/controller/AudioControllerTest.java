package com.lolog.api.domain.audio.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.audio.service.AudioUploadService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.support.AppControllerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("AudioController")
@WebMvcTest(AudioController.class)
class AudioControllerTest extends AppControllerTestSupport {

	private static final byte[] AAC = { 1, 2, 3 };

	@MockitoBean
	AudioUploadService audio;

	private static MockMultipartFile file() {
		return new MockMultipartFile("file", "a.aac", "audio/aac", AAC);
	}

	@Test
	@DisplayName("토큰 없이 올리면 401 UNAUTHORIZED다")
	void 토큰_없이_올리면_401이다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/api/recordings/1/audio/2").file(file()))
			.andExpect(status().isUnauthorized());

		verifyNoInteractions(audio);
	}

	@Test
	@DisplayName("음성을 올리면 204이고 경로의 녹음 ID·순번과 파일 바이트, 구간을 넘긴다")
	void 올리면_204이고_인자를_넘긴다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/api/recordings/1/audio/2").file(file())
			.param("startMs", "100")
			.param("endMs", "200")
			.header(AUTH, user()))
			.andExpect(status().isNoContent());

		verify(audio).upload(USER_ID, 1L, 2, AAC, 100L, 200L);
	}

	@Test
	@DisplayName("startMs와 endMs가 없으면 null로 넘긴다")
	void 구간이_없으면_null로_넘긴다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/api/recordings/1/audio/2").file(file()).header(AUTH, user()))
			.andExpect(status().isNoContent());

		verify(audio).upload(USER_ID, 1L, 2, AAC, null, null);
	}

	@Test
	@DisplayName("file 파트가 없으면 400 INVALID_REQUEST이고 field는 file이다")
	void file_파트가_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/api/recordings/1/audio/2").param("startMs", "1").header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("file"));

		verifyNoInteractions(audio);
	}

	@Test
	@DisplayName("순번이 숫자가 아니면 400 INVALID_REQUEST이고 field는 seq다")
	void 순번이_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/api/recordings/1/audio/x").file(file()).header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("seq"));
	}

	@Test
	@DisplayName("startMs가 숫자가 아니면 400 INVALID_REQUEST이고 field는 startMs다")
	void startMs가_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/api/recordings/1/audio/2").file(file())
			.param("startMs", "abc")
			.header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("startMs"));
	}

	@Test
	@DisplayName("multipart가 아닌 본문은 415 UNSUPPORTED_MEDIA_TYPE이다")
	void multipart가_아니면_415다() throws Exception {
		// when & then
		mvc.perform(put("/api/recordings/1/audio/2").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	@DisplayName("서비스가 RECORDING_NOT_FOUND를 던지면 404로 답한다")
	void 없는_녹음은_404다() throws Exception {
		// given
		doThrow(new ApiException(ErrorCode.RECORDING_NOT_FOUND)).when(audio)
			.upload(USER_ID, 1L, 2, AAC, null, null);

		// when & then
		mvc.perform(multipart(HttpMethod.PUT, "/api/recordings/1/audio/2").file(file()).header(AUTH, user()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
	}

}
