package com.lolog.api.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// local 프로필이 아니면 임시 인증이 켜지지 않는지 확인한다. Docker가 켜져 있어야 한다.
@SpringBootTest(properties = "lolog.auth.dev-test-token=test-token")
@AutoConfigureMockMvc
@Testcontainers
class DevAuthProfileTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Autowired
	MockMvc mvc;

	@Test
	void local_프로필이_아니면_임시_헤더를_믿지_않는다() throws Exception {
		mvc.perform(get("/api/recordings/1").header("X-User-Id", "1")).andExpect(status().isUnauthorized());
	}

	@Test
	void local_프로필이_아니면_테스트_토큰을_받지_않는다() throws Exception {
		mvc.perform(get("/api/recordings/1").header("Authorization", "Bearer test-token"))
			.andExpect(status().isUnauthorized());
	}

}
