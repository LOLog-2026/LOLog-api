package com.lolog.api.domain.comic.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.comic.entity.ComicStatus;
import com.lolog.api.domain.comic.service.ComicQueryService;
import com.lolog.api.domain.comic.service.ComicService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.support.AppControllerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("ComicController 만화 요청")
@WebMvcTest(ComicController.class)
class ComicControllerRequestTest extends AppControllerTestSupport {

	@MockitoBean
	ComicService comicService;

	@MockitoBean
	ComicQueryService comicQueries;

	@Test
	@DisplayName("만화를 요청하면 202와 만화 ID를 돌려준다")
	void 만화를_요청하면_202와_만화_ID를_돌려준다() throws Exception {
		// given
		given(comicService.request(USER_ID, 11L)).willReturn(new ComicService.Created(21L, ComicStatus.QUEUED));

		// when & then
		mvc.perform(post("/api/cards/11/comics").header(AUTH, user()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.comicId").value(21))
			.andExpect(jsonPath("$.status").value("QUEUED"));
	}

	@Test
	@DisplayName("토큰이 없으면 401이고 서비스를 부르지 않는다")
	void 토큰이_없으면_401이고_서비스를_부르지_않는다() throws Exception {
		// when & then
		mvc.perform(post("/api/cards/11/comics"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		then(comicService).should(never()).request(anyLong(), anyLong());
	}

	@Test
	@DisplayName("카드 ID가 숫자가 아니면 400이고 field는 id다")
	void 카드_ID가_숫자가_아니면_400이고_field는_id다() throws Exception {
		// when & then
		mvc.perform(post("/api/cards/abc/comics").header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("id"));
		then(comicService).should(never()).request(anyLong(), anyLong());
	}

	@Test
	@DisplayName("횟수가 다 찼으면 409 COMIC_LIMIT 응답이다")
	void 횟수가_다_찼으면_409_COMIC_LIMIT_응답이다() throws Exception {
		// given
		given(comicService.request(USER_ID, 11L)).willThrow(new ApiException(ErrorCode.COMIC_LIMIT));

		// when & then
		mvc.perform(post("/api/cards/11/comics").header(AUTH, user()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("COMIC_LIMIT"));
	}

}
