package com.lolog.api.domain.admin.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.admin.dto.AdminDtos.FeedbackRequest;
import com.lolog.api.domain.admin.dto.AdminDtos.Notice;
import com.lolog.api.domain.admin.service.AdminService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.support.AppControllerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("AdminController")
@WebMvcTest(AdminController.class)
class AdminControllerTest extends AppControllerTestSupport {

	@MockitoBean
	AdminService admin;

	// ---- 인증·권한 ----

	@Test
	@DisplayName("토큰 없이 관리자 주소를 부르면 401 UNAUTHORIZED다")
	void 토큰_없이_관리자_주소는_401이다() throws Exception {
		// when & then
		mvc.perform(get("/api/admin/users/1"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		verifyNoInteractions(admin);
	}

	@Test
	@DisplayName("관리자가 아니면 403 FORBIDDEN이고 서비스를 부르지 않는다")
	void 관리자가_아니면_403이다() throws Exception {
		// when & then
		mvc.perform(get("/api/admin/users/1").header(AUTH, user()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"))
			.andExpect(jsonPath("$.field").isEmpty());

		verifyNoInteractions(admin);
	}

	@Test
	@DisplayName("관리자가 아니면 본문이 깨진 JSON이어도 400보다 403이 먼저다")
	void 관리자가_아니면_깨진_본문보다_403이_먼저다() throws Exception {
		// when & then
		mvc.perform(post("/api/admin/notices").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));

		verifyNoInteractions(admin);
	}

	@Test
	@DisplayName("관리자가 아니면 경로 변수 형식이 틀려도 400보다 403이 먼저다")
	void 관리자가_아니면_틀린_경로_변수보다_403이_먼저다() throws Exception {
		// when & then
		mvc.perform(patch("/api/admin/notices/abc").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	@DisplayName("관리자면 경로 변수 형식이 틀릴 때 400 INVALID_REQUEST이고 field는 id다")
	void 관리자면_틀린_경로_변수는_400이다() throws Exception {
		// when & then
		mvc.perform(patch("/api/admin/notices/abc").header(AUTH, admin())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("id"));
	}

	@Test
	@DisplayName("관리자면 깨진 JSON 본문은 400 INVALID_REQUEST다")
	void 관리자면_깨진_본문은_400이다() throws Exception {
		// when & then
		mvc.perform(post("/api/admin/notices").header(AUTH, admin())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		verifyNoInteractions(admin);
	}

	// ---- 공지 ----

	@Test
	@DisplayName("공지를 만들면 201이고 제목과 본문을 넘긴다")
	void 공지를_만들면_201이다() throws Exception {
		// given
		when(admin.createNotice("점검", "오늘 밤"))
			.thenReturn(new Notice(3L, "점검", "오늘 밤", null, null));

		// when & then
		mvc.perform(post("/api/admin/notices").header(AUTH, admin())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"점검\",\"body\":\"오늘 밤\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(3))
			.andExpect(jsonPath("$.title").value("점검"));
	}

	@Test
	@DisplayName("공지를 고칠 때 본문에서 뺀 값은 null로 넘긴다")
	void 공지를_고칠_때_뺀_값은_null로_넘긴다() throws Exception {
		// when & then
		mvc.perform(patch("/api/admin/notices/3").header(AUTH, admin())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"새 제목\"}"))
			.andExpect(status().isOk());

		verify(admin).updateNotice(3L, "새 제목", null);
	}

	@Test
	@DisplayName("공지를 지우면 204다")
	void 공지를_지우면_204다() throws Exception {
		// when & then
		mvc.perform(delete("/api/admin/notices/3").header(AUTH, admin())).andExpect(status().isNoContent());

		verify(admin).deleteNotice(3L);
	}

	@Test
	@DisplayName("서비스가 NOTICE_NOT_FOUND를 던지면 404로 답한다")
	void 없는_공지는_404다() throws Exception {
		// given
		when(admin.updateNotice(9L, "t", "b")).thenThrow(new ApiException(ErrorCode.NOTICE_NOT_FOUND));

		// when & then
		mvc.perform(patch("/api/admin/notices/9").header(AUTH, admin())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"t\",\"body\":\"b\"}"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOTICE_NOT_FOUND"));
	}

	// ---- 회원·의견 ----

	@Test
	@DisplayName("회원 조회는 경로의 회원 ID를 넘긴다")
	void 회원_조회는_경로의_ID를_넘긴다() throws Exception {
		// when & then
		mvc.perform(get("/api/admin/users/42").header(AUTH, admin())).andExpect(status().isOk());

		verify(admin).user(42L);
	}

	@Test
	@DisplayName("의견 목록은 status와 cursor를 그대로 넘긴다")
	void 의견_목록은_쿼리를_그대로_넘긴다() throws Exception {
		// when & then
		mvc.perform(get("/api/admin/feedback").param("status", "OPEN").param("cursor", "10").header(AUTH, admin()))
			.andExpect(status().isOk());

		verify(admin).feedbackList("OPEN", "10");
	}

	@Test
	@DisplayName("의견 목록에 쿼리가 없으면 둘 다 null로 넘긴다")
	void 의견_목록에_쿼리가_없으면_null이다() throws Exception {
		// when & then
		mvc.perform(get("/api/admin/feedback").header(AUTH, admin())).andExpect(status().isOk());

		verify(admin).feedbackList(null, null);
	}

	@Test
	@DisplayName("의견 상태를 바꾸면 경로 ID와 본문의 status를 넘긴다")
	void 의견_상태를_바꾸면_ID와_status를_넘긴다() throws Exception {
		// when & then
		mvc.perform(patch("/api/admin/feedback/4").header(AUTH, admin())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"DONE\"}"))
			.andExpect(status().isOk());

		verify(admin).markFeedback(4L, "DONE");
	}

	@Test
	@DisplayName("의견 보내기는 관리자가 아니어도 201이고 내 회원 ID와 본문을 넘긴다")
	void 의견_보내기는_누구나_201이다() throws Exception {
		// when & then
		mvc.perform(post("/api/feedback").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"category\":\"bug\",\"text\":\"멈춰요\",\"appVersion\":\"1.0\",\"deviceModel\":\"Pixel\"}"))
			.andExpect(status().isCreated());

		verify(admin).send(USER_ID, new FeedbackRequest("bug", "멈춰요", "1.0", "Pixel"));
	}

	@Test
	@DisplayName("의견 보내기도 토큰이 없으면 401 UNAUTHORIZED다")
	void 의견_보내기도_토큰이_없으면_401이다() throws Exception {
		// when & then
		mvc.perform(post("/api/feedback").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isUnauthorized());

		verifyNoInteractions(admin);
	}

}
