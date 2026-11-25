package com.lolog.api.internal.video;

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

import com.lolog.api.support.InternalWebMvcTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("VideoWorkerController")
@InternalWebMvcTest(VideoWorkerController.class)
class VideoWorkerControllerTest {

	private static final byte[] MP4 = { 1, 2 };
	private static final byte[] JPG = { 3, 4 };

	@Autowired
	MockMvc mvc;

	@MockitoBean
	VideoWorkerService service;

	@Test
	@DisplayName("영상 파일·썸네일·길이를 서비스에 넘기고 204로 답한다")
	void 영상을_넘긴다() throws Exception {
		// when
		mvc.perform(multipart("/internal/videos/6/video").file(new MockMultipartFile("file", MP4))
			.file(new MockMultipartFile("thumbnail", JPG))
			.param("durationMs", "15000")
			.header(HEADER, TOKEN)).andExpect(status().isNoContent());

		// then
		verify(service).saveVideo(6L, MP4, JPG, 15000L);
	}

	@Test
	@DisplayName("길이가 없으면 null로 넘긴다")
	void 길이가_없으면_null이다() throws Exception {
		// when
		mvc.perform(multipart("/internal/videos/6/video").file(new MockMultipartFile("file", MP4))
			.file(new MockMultipartFile("thumbnail", JPG))
			.header(HEADER, TOKEN)).andExpect(status().isNoContent());

		// then
		verify(service).saveVideo(6L, MP4, JPG, null);
	}

	@Test
	@DisplayName("thumbnail 파트가 없으면 400, field는 thumbnail이다")
	void 썸네일이_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(multipart("/internal/videos/6/video").file(new MockMultipartFile("file", MP4)).header(HEADER, TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("thumbnail"));

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("file 파트가 없으면 400, field는 file이다")
	void 영상_파일이_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(multipart("/internal/videos/6/video").file(new MockMultipartFile("thumbnail", JPG))
			.header(HEADER, TOKEN)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file"));

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("음성 파일 파트의 바이트를 넘기고 204로 답한다")
	void 음성을_넘긴다() throws Exception {
		// when
		mvc.perform(multipart("/internal/videos/6/audio").file(new MockMultipartFile("file", MP4)).header(HEADER, TOKEN))
			.andExpect(status().isNoContent());

		// then
		verify(service).saveAudio(6L, MP4);
	}

	@Test
	@DisplayName("음성 file 파트가 없으면 400, field는 file이다")
	void 음성_파일이_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(multipart("/internal/videos/6/audio").header(HEADER, TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("file"));

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("음성을 audio/wav로 돌려준다")
	void 음성을_wav로_돌려준다() throws Exception {
		// given
		when(service.audio(6L)).thenReturn(new byte[] { 7 });

		// when & then
		mvc.perform(get("/internal/videos/6/audio").header(HEADER, TOKEN))
			.andExpect(status().isOk())
			.andExpect(content().contentType("audio/wav"))
			.andExpect(content().bytes(new byte[] { 7 }));
	}

	@Test
	@DisplayName("저장된 스토리보드를 JSON으로 돌려준다")
	void 스토리보드를_돌려준다() throws Exception {
		// given
		when(service.storyboard(6L)).thenReturn(JsonMapper.builder().build().readTree("{\"scenes\":[1]}"));

		// when & then
		mvc.perform(get("/internal/videos/6/storyboard").header(HEADER, TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.scenes[0]").value(1));
	}

	@Test
	@DisplayName("내부 토큰이 없으면 401이다")
	void 내부_토큰이_없으면_401이다() throws Exception {
		// when & then
		mvc.perform(get("/internal/videos/6/source")).andExpect(status().isUnauthorized());

		verifyNoInteractions(service);
	}

}
