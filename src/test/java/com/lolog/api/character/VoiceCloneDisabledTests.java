package com.lolog.api.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 큐 넣기를 끈 기본 설정(lolog.voice-clone.queue-enabled=false)에서는 내 목소리를 만들지 않고 503으로 거절한다(#83).
// ai 워커가 lolog:video를 kind로 나누기 전에 voice 작업이 쇼츠로 잘못 처리되지 않게 하려는 것이다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class VoiceCloneDisabledTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Container
	@ServiceConnection(name = "redis")
	@SuppressWarnings("resource")
	static GenericContainer<?> redis = new GenericContainer<>("redis:latest").withExposedPorts(6379);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void 큐를_끄면_만들기는_503이고_작업을_만들지_않는다() throws Exception {
		long me = 83900;
		mvc.perform(multipart(HttpMethod.PUT, "/api/me/profile/character/voice-clone")
			.file(new MockMultipartFile("audio", "me.wav", "audio/wav", VoiceCloneApiTests.wav(64)))
			.param("consent", "true")
			.header("Authorization", TestTokens.bearer(me)))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("VOICE_CLONE_UNAVAILABLE"));
		assertThat(jdbc.queryForObject("select count(*) from jobs where job_type = 'VOICE_CLONE'", Long.class)).isZero();
	}

}
