package com.lolog.api.domain.comic.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.comic.entity.ComicStatus;
import com.lolog.api.domain.comic.service.ComicQueryService;
import com.lolog.api.domain.comic.service.ComicService;
import com.lolog.api.domain.comic.service.ComicService.Created;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.support.AppControllerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("ComicController")
@WebMvcTest(ComicController.class)
class ComicControllerTest extends AppControllerTestSupport {

	@MockitoBean
	ComicService comics;

	@MockitoBean
	ComicQueryService queries;

	@Test
	@DisplayName("토큰 없이 만화를 요청하면 401 UNAUTHORIZED다")
	void 토큰_없이_요청하면_401이다() throws Exception {
		// when & then
		mvc.perform(post("/api/cards/3/comics")).andExpect(status().isUnauthorized());

		verifyNoInteractions(comics);
	}

	@Test
	@DisplayName("만화를 요청하면 202이고 comicId와 status를 답한다")
	void 요청하면_202다() throws Exception {
		// given
		when(comics.request(USER_ID, 3L)).thenReturn(new Created(10L, ComicStatus.QUEUED));

		// when & then
		mvc.perform(post("/api/cards/3/comics").header(AUTH, user()))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.comicId").value(10))
			.andExpect(jsonPath("$.status").value("QUEUED"));
	}

	@Test
	@DisplayName("서비스가 COMIC_IN_PROGRESS를 던지면 409로 답한다")
	void 만드는_중이면_409다() throws Exception {
		// given
		when(comics.request(USER_ID, 3L)).thenThrow(new ApiException(ErrorCode.COMIC_IN_PROGRESS));

		// when & then
		mvc.perform(post("/api/cards/3/comics").header(AUTH, user()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("COMIC_IN_PROGRESS"));
	}

	@Test
	@DisplayName("카드 ID가 숫자가 아니면 400 INVALID_REQUEST이고 field는 id다")
	void 카드_ID가_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(post("/api/cards/abc/comics").header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("id"));
	}

	@Test
	@DisplayName("목록은 cursor·size·sort를 그대로 넘긴다")
	void 목록은_쿼리를_넘긴다() throws Exception {
		// when & then
		mvc.perform(get("/api/comics").param("cursor", "5").param("size", "3").param("sort", "oldest")
			.header(AUTH, user()))
			.andExpect(status().isOk());

		verify(queries).list(USER_ID, "5", 3, "oldest");
	}

	@Test
	@DisplayName("자세히는 경로의 만화 ID를 넘긴다")
	void 자세히는_ID를_넘긴다() throws Exception {
		// when & then
		mvc.perform(get("/api/comics/4").header(AUTH, user())).andExpect(status().isOk());

		verify(queries).detail(USER_ID, 4L);
	}

	@Test
	@DisplayName("만화를 지우면 204다")
	void 지우면_204다() throws Exception {
		// when & then
		mvc.perform(delete("/api/comics/4").header(AUTH, user())).andExpect(status().isNoContent());

		verify(queries).delete(USER_ID, 4L);
	}

}
