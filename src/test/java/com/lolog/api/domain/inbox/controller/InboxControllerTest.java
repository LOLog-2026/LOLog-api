package com.lolog.api.domain.inbox.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.inbox.service.InboxService;
import com.lolog.api.support.AppControllerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("InboxController")
@WebMvcTest(InboxController.class)
class InboxControllerTest extends AppControllerTestSupport {

	@MockitoBean
	InboxService inbox;

	@Test
	@DisplayName("토큰 없이 알림 목록을 부르면 401 UNAUTHORIZED다")
	void 토큰_없이_부르면_401이다() throws Exception {
		// when & then
		mvc.perform(get("/api/inbox")).andExpect(status().isUnauthorized());

		verifyNoInteractions(inbox);
	}

	@Test
	@DisplayName("알림 목록은 tab과 cursor를 그대로 넘긴다")
	void 목록은_tab과_cursor를_넘긴다() throws Exception {
		// when & then
		mvc.perform(get("/api/inbox").param("tab", "notice").param("cursor", "10").header(AUTH, user()))
			.andExpect(status().isOk());

		verify(inbox).list(USER_ID, "notice", "10");
	}

	@Test
	@DisplayName("알림 목록에 쿼리가 없으면 둘 다 null로 넘긴다")
	void 쿼리가_없으면_null이다() throws Exception {
		// when & then
		mvc.perform(get("/api/inbox").header(AUTH, user())).andExpect(status().isOk());

		verify(inbox).list(USER_ID, null, null);
	}

	@Test
	@DisplayName("안 읽은 것 확인은 내 회원 ID를 넘긴다")
	void 안_읽은_것_확인() throws Exception {
		// when & then
		mvc.perform(get("/api/inbox/unread").header(AUTH, user())).andExpect(status().isOk());

		verify(inbox).unread(USER_ID);
	}

	@Test
	@DisplayName("하나 읽음은 204이고 경로의 kind와 id를 넘긴다")
	void 하나_읽음은_kind와_id를_넘긴다() throws Exception {
		// when & then
		mvc.perform(post("/api/inbox/notice/3/read").header(AUTH, user())).andExpect(status().isNoContent());

		verify(inbox).read(USER_ID, "notice", 3L);
	}

	@Test
	@DisplayName("하나 읽음의 id가 숫자가 아니면 400 INVALID_REQUEST이고 field는 id다")
	void 하나_읽음의_id가_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(post("/api/inbox/notice/abc/read").header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("id"));

		verifyNoInteractions(inbox);
	}

	@Test
	@DisplayName("모두 읽음은 204이고 하나 읽음 주소와 섞이지 않는다")
	void 모두_읽음은_204다() throws Exception {
		// when & then
		mvc.perform(post("/api/inbox/read").header(AUTH, user())).andExpect(status().isNoContent());

		verify(inbox).readAll(USER_ID);
	}

	@Test
	@DisplayName("새 소식 표시를 읽고, 본 kind를 넘긴다")
	void 새_소식_표시() throws Exception {
		// when & then
		mvc.perform(get("/api/me/badges").header(AUTH, user())).andExpect(status().isOk());
		mvc.perform(post("/api/me/badges/cards/read").header(AUTH, user())).andExpect(status().isNoContent());

		verify(inbox).badges(USER_ID);
		verify(inbox).seen(USER_ID, "cards");
	}

}
