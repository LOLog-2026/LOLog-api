package com.lolog.api.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// /api의 액세스 토큰(JWT) 규칙을 확인한다(API 명세 "토큰"). Docker가 켜져 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class JwtAuthTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Autowired
	MockMvc mvc;

	private ResultActions get404(String authorization) throws Exception {
		var request = get("/api/recordings/999999");
		return mvc.perform(authorization == null ? request : request.header("Authorization", authorization));
	}

	@Test
	void 맞는_액세스_토큰이면_sub가_회원이다() throws Exception {
		// 남의 녹음과 없는 녹음은 똑같이 404이므로, 인증을 통과하면 404다.
		get404(TestTokens.bearer(1)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
	}

	@Test
	void 만료된_토큰은_401_TOKEN_EXPIRED다() throws Exception {
		String expired = TestTokens.token("1", "access", Duration.ofMinutes(-5), TestTokens.SECRET);
		get404("Bearer " + expired).andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"))
			.andExpect(jsonPath("$.field").isEmpty());
	}

	@Test
	void 토큰이_없거나_틀리면_401_UNAUTHORIZED다() throws Exception {
		get404(null).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		get404("Bearer not-a-jwt").andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		String otherSecret = TestTokens.token("1", "access", Duration.ofMinutes(30), "another-secret-at-least-32-bytes-long!!");
		get404("Bearer " + otherSecret).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		// 리프레시처럼 typ가 access가 아니거나 없으면 받지 않는다.
		get404("Bearer " + TestTokens.token("1", "refresh", Duration.ofMinutes(30), TestTokens.SECRET))
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		get404("Bearer " + TestTokens.token("1", null, Duration.ofMinutes(30), TestTokens.SECRET))
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		get404("Bearer " + TestTokens.token("abc", "access", Duration.ofMinutes(30), TestTokens.SECRET))
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void 예전_임시_인증은_더_이상_받지_않는다() throws Exception {
		mvc.perform(get("/api/recordings/1").header("X-User-Id", "1"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void 내부_주소는_액세스_토큰이_아니라_내부_토큰으로_막는다() throws Exception {
		mvc.perform(post("/internal/jobs/1/fail").header("Authorization", TestTokens.bearer(1))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"x\"}")).andExpect(status().isUnauthorized());
		mvc.perform(post("/internal/jobs/1/fail").header("X-Internal-Token", "internal-test-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"x\"}")).andExpect(status().isBadRequest());
	}

}
