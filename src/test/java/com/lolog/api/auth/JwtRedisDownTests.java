package com.lolog.api.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Redis에 못 붙어 탈퇴 여부를 모르면 토큰을 통과시키지 않고 500 INTERNAL_ERROR다.
// 401이면 앱이 로그인을 풀어 버리므로 5xx로 답해 다시 시도하게 한다. Redis 주소는 아무것도 없는 포트로 둔다.
@SpringBootTest(properties = { "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1" })
@AutoConfigureMockMvc
@Testcontainers
class JwtRedisDownTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Autowired
	MockMvc mvc;

	@Test
	void Redis에_못_붙으면_맞는_토큰도_500_INTERNAL_ERROR다() throws Exception {
		mvc.perform(get("/api/recordings/999999").header("Authorization", TestTokens.bearer(1)))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.field").isEmpty());
	}

	@Test
	void 만료된_토큰은_Redis와_관계없이_401_TOKEN_EXPIRED다() throws Exception {
		String expired = TestTokens.token("1", "access", Duration.ofMinutes(-5), TestTokens.SECRET);
		mvc.perform(get("/api/recordings/999999").header("Authorization", "Bearer " + expired))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("TOKEN_EXPIRED"));
	}

}
