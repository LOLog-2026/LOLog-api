package com.lolog.api.internal.user;

import static com.lolog.api.support.InternalWebMvcTest.HEADER;
import static com.lolog.api.support.InternalWebMvcTest.TOKEN;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.admin.service.AdminService;
import com.lolog.api.domain.user.service.UserDataService;
import com.lolog.api.support.InternalWebMvcTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("InternalUserController")
@InternalWebMvcTest(InternalUserController.class)
class InternalUserControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	UserDataService userData;

	@MockitoBean
	AdminService admin;

	@Test
	@DisplayName("정지 기한을 받아 회원 ID와 함께 서비스에 넘기고 204로 답한다")
	void 정지_기한을_넘긴다() throws Exception {
		// when
		mvc.perform(put("/internal/users/7/suspension").header(HEADER, TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"suspendedUntil\":\"2026-12-01T00:00:00+09:00\"}")).andExpect(status().isNoContent());

		// then
		// 시간대는 바뀔 수 있어 같은 순간인지로 본다
		verify(admin).suspend(eq(7L), argThat(until -> until.isEqual(OffsetDateTime.parse("2026-12-01T00:00:00+09:00"))));
	}

	@Test
	@DisplayName("정지 본문이 없으면 400이고 서비스를 부르지 않는다")
	void 정지_본문이_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(put("/internal/users/7/suspension").header(HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		verifyNoInteractions(admin);
	}

	@Test
	@DisplayName("정지 기한이 날짜 형식이 아니면 400, field는 suspendedUntil이다")
	void 정지_기한_형식이_틀리면_400이다() throws Exception {
		// when & then
		mvc.perform(put("/internal/users/7/suspension").header(HEADER, TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"suspendedUntil\":\"내일\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("suspendedUntil"));

		verifyNoInteractions(admin);
	}

	@Test
	@DisplayName("정지 풀기는 회원 ID를 넘기고 204로 답한다")
	void 정지를_푼다() throws Exception {
		// when
		mvc.perform(delete("/internal/users/7/suspension").header(HEADER, TOKEN)).andExpect(status().isNoContent());

		// then
		verify(admin).unsuspend(7L);
		verifyNoInteractions(userData);
	}

	@Test
	@DisplayName("탈퇴 정리는 회원 ID를 UserDataService에 넘기고 204로 답한다")
	void 탈퇴_정리를_넘긴다() throws Exception {
		// when
		mvc.perform(delete("/internal/users/7").header(HEADER, TOKEN)).andExpect(status().isNoContent());

		// then
		verify(userData).delete(7L);
		verifyNoInteractions(admin);
	}

	@Test
	@DisplayName("회원 ID가 숫자가 아니면 400, field는 id다")
	void 회원_ID가_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(delete("/internal/users/abc").header(HEADER, TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("id"));

		verifyNoInteractions(userData);
	}

	@Test
	@DisplayName("내부 토큰이 없으면 401이고 탈퇴 정리를 하지 않는다")
	void 내부_토큰이_없으면_401이다() throws Exception {
		// when & then
		mvc.perform(delete("/internal/users/7")).andExpect(status().isUnauthorized());

		verifyNoInteractions(userData, admin);
	}

}
