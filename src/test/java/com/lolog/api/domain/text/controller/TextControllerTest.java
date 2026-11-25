package com.lolog.api.domain.text.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.text.dto.TextDtos.TextCreateRequest;
import com.lolog.api.domain.text.service.TextService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.support.AppControllerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("TextController")
@WebMvcTest(TextController.class)
class TextControllerTest extends AppControllerTestSupport {

	@MockitoBean
	TextService texts;

	@Test
	@DisplayName("토큰 없이 글을 보내면 401 UNAUTHORIZED다")
	void 토큰_없이_보내면_401이다() throws Exception {
		// when & then
		mvc.perform(post("/api/texts").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());

		verifyNoInteractions(texts);
	}

	@Test
	@DisplayName("글을 보내면 202이고 본문을 그대로 넘긴다")
	void 글을_보내면_202다() throws Exception {
		// when & then
		mvc.perform(post("/api/texts").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"제목\",\"body\":\"본문\",\"place\":\"집\",\"situation\":null}"))
			.andExpect(status().isAccepted());

		verify(texts).create(USER_ID, new TextCreateRequest("제목", "본문", "집", null));
	}

	@Test
	@DisplayName("깨진 JSON은 400 INVALID_REQUEST이고 서비스를 부르지 않는다")
	void 깨진_JSON은_400이다() throws Exception {
		// when & then
		mvc.perform(post("/api/texts").header(AUTH, user()).contentType(MediaType.APPLICATION_JSON).content("{\"title\""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		verifyNoInteractions(texts);
	}

	@Test
	@DisplayName("서비스가 TEXT_TOO_LONG을 던지면 400 TEXT_TOO_LONG으로 답한다")
	void 글이_너무_길면_TEXT_TOO_LONG이다() throws Exception {
		// given
		when(texts.create(eq(USER_ID), any())).thenThrow(new ApiException(ErrorCode.TEXT_TOO_LONG));

		// when & then
		mvc.perform(post("/api/texts").header(AUTH, user()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TEXT_TOO_LONG"));
	}

	@Test
	@DisplayName("글 조회는 경로의 글 ID를 넘기고, 숫자가 아니면 field는 id다")
	void 글_조회() throws Exception {
		// when & then
		mvc.perform(get("/api/texts/3").header(AUTH, user())).andExpect(status().isOk());
		mvc.perform(get("/api/texts/abc").header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("id"));

		verify(texts).get(USER_ID, 3L);
	}

}
