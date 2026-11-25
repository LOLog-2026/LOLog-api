package com.lolog.api.internal.job;

import static com.lolog.api.support.InternalWebMvcTest.HEADER;
import static com.lolog.api.support.InternalWebMvcTest.TOKEN;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.support.InternalWebMvcTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@DisplayName("JobWorkerController")
@InternalWebMvcTest(JobWorkerController.class)
class JobWorkerControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	JobFailService jobFails;

	private ResultActions fail(String path, String body) throws Exception {
		return mvc.perform(post(path).header(HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String reasonBody(String reason) {
		return "{\"reason\":\"" + reason + "\"}";
	}

	@Test
	@DisplayName("사유가 500자면 받아서 작업 ID와 사유를 그대로 넘긴다")
	void 사유가_500자면_받는다() throws Exception {
		// given
		String reason = "가".repeat(500);

		// when & then
		fail("/internal/jobs/3/fail", reasonBody(reason)).andExpect(status().isNoContent());

		verify(jobFails).fail(3L, reason);
	}

	@Test
	@DisplayName("사유가 501자면 400 INVALID_REQUEST, field는 reason이다")
	void 사유가_501자면_400이다() throws Exception {
		// when & then
		fail("/internal/jobs/3/fail", reasonBody("가".repeat(501))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("reason"));

		verify(jobFails, never()).fail(anyLong(), anyString());
	}

	@Test
	@DisplayName("사유가 공백뿐이면 400, field는 reason이다")
	void 사유가_공백이면_400이다() throws Exception {
		// when & then
		fail("/internal/jobs/3/fail", reasonBody("   ")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("reason"));

		verifyNoInteractions(jobFails);
	}

	@Test
	@DisplayName("사유가 없으면 400, field는 reason이다")
	void 사유가_없으면_400이다() throws Exception {
		// when & then
		fail("/internal/jobs/3/fail", "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("reason"));

		verifyNoInteractions(jobFails);
	}

	@Test
	@DisplayName("작업 ID가 숫자가 아니면 400, field는 id다")
	void 작업_ID가_숫자가_아니면_400이다() throws Exception {
		// when & then
		fail("/internal/jobs/abc/fail", reasonBody("실패")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("id"));

		verifyNoInteractions(jobFails);
	}

	@Test
	@DisplayName("서비스가 ApiException을 던지면 그 상태와 코드로 답한다")
	void 서비스_오류를_그대로_답한다() throws Exception {
		// given
		doThrow(new ApiException(ErrorCode.NOT_FOUND)).when(jobFails).fail(9L, "실패");

		// when & then
		fail("/internal/jobs/9/fail", reasonBody("실패")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("내부 토큰이 없으면 401 UNAUTHORIZED이고 서비스를 부르지 않는다")
	void 내부_토큰이_없으면_401이다() throws Exception {
		// when & then
		mvc.perform(post("/internal/jobs/3/fail").contentType(MediaType.APPLICATION_JSON).content(reasonBody("실패")))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		verifyNoInteractions(jobFails);
	}

}
