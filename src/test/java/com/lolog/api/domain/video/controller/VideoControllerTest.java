package com.lolog.api.domain.video.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.video.dto.VideoDtos.VideoAccepted;
import com.lolog.api.domain.video.service.VideoService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.support.AppControllerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("VideoController")
@WebMvcTest(VideoController.class)
class VideoControllerTest extends AppControllerTestSupport {

	@MockitoBean
	VideoService videos;

	@Test
	@DisplayName("토큰 없이 영상을 요청하면 401 UNAUTHORIZED다")
	void 토큰_없이_요청하면_401이다() throws Exception {
		// when & then
		mvc.perform(post("/api/cards/3/videos")).andExpect(status().isUnauthorized());

		verifyNoInteractions(videos);
	}

	@Test
	@DisplayName("영상을 요청하면 202이고 videoId와 status를 답한다")
	void 요청하면_202다() throws Exception {
		// given
		when(videos.request(USER_ID, 3L)).thenReturn(new VideoAccepted(20L, "QUEUED"));

		// when & then
		mvc.perform(post("/api/cards/3/videos").header(AUTH, user()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.videoId").value(20))
			.andExpect(jsonPath("$.status").value("QUEUED"));
	}

	@Test
	@DisplayName("서비스가 VIDEO_LIMIT을 던지면 409로 답한다")
	void 한도를_넘으면_409다() throws Exception {
		// given
		when(videos.request(USER_ID, 3L)).thenThrow(new ApiException(ErrorCode.VIDEO_LIMIT));

		// when & then
		mvc.perform(post("/api/cards/3/videos").header(AUTH, user()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VIDEO_LIMIT"));
	}

	@Test
	@DisplayName("목록은 cursor·size·sort를 그대로 넘긴다")
	void 목록은_쿼리를_넘긴다() throws Exception {
		// when & then
		mvc.perform(get("/api/videos").param("cursor", "5").param("size", "3").param("sort", "oldest")
			.header(AUTH, user()))
			.andExpect(status().isOk());

		verify(videos).list(USER_ID, "5", 3, "oldest");
	}

	@Test
	@DisplayName("영상 조회와 지우기는 경로의 영상 ID를 넘긴다")
	void 조회와_지우기() throws Exception {
		// when & then
		mvc.perform(get("/api/videos/20").header(AUTH, user())).andExpect(status().isOk());
		mvc.perform(delete("/api/videos/20").header(AUTH, user())).andExpect(status().isNoContent());

		verify(videos).get(USER_ID, 20L);
		verify(videos).delete(USER_ID, 20L);
	}

	@Test
	@DisplayName("영상 ID가 숫자가 아니면 400 INVALID_REQUEST이고 field는 id다")
	void 영상_ID가_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(get("/api/videos/abc").header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("id"));

		verifyNoInteractions(videos);
	}

}
