package com.lolog.api.common;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Spring이 직접 거절하는 요청(없는 주소, 틀린 메서드, 틀린 Content-Type)도 ErrorResponse 모양인지 본다
// (api-dto.md 오류 코드, LOLog-docs PR #60). Docker가 켜져 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FrameworkErrorTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Container
	@ServiceConnection(name = "redis")
	static GenericContainer<?> redis = new GenericContainer<>("redis:latest").withExposedPorts(6379);

	@Autowired
	MockMvc mvc;

	@Test
	void 없는_주소는_404_NOT_FOUND다() throws Exception {
		mvc.perform(get("/api/nothing").header("Authorization", TestTokens.bearer(1)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.field").isEmpty());
	}

	@Test
	void 틀린_메서드는_405_METHOD_NOT_ALLOWED이고_Allow_헤더를_준다() throws Exception {
		mvc.perform(put("/api/characters/defaults").header("Authorization", TestTokens.bearer(1)))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(header().string("Allow", "GET"))
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	void JSON_주소에_JSON이_아닌_본문은_415_UNSUPPORTED_MEDIA_TYPE이다() throws Exception {
		mvc.perform(post("/api/recordings").header("Authorization", TestTokens.bearer(1))
			.contentType(MediaType.TEXT_PLAIN)
			.content("hello"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	void 토큰이_없으면_지금처럼_401_UNAUTHORIZED다() throws Exception {
		// 보안 검사가 주소 확인보다 먼저라, 없는 주소도 토큰이 없으면 401이다.
		mvc.perform(get("/api/nothing"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

}
