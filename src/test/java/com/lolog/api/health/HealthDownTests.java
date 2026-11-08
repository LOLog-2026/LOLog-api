package com.lolog.api.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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

// Redis에 붙지 못하면 503 DOWN이고, 오래 기다리지 않는다. Redis 주소는 아무것도 없는 포트로 둔다.
@SpringBootTest(properties = { "spring.data.redis.host=127.0.0.1", "spring.data.redis.port=1" })
@AutoConfigureMockMvc
@Testcontainers
class HealthDownTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Autowired
	MockMvc mvc;

	@Test
	void Redis에_못_붙으면_503_DOWN이다() throws Exception {
		long start = System.nanoTime();
		mvc.perform(get("/api/health"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.status").value("DOWN"));
		assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
	}

}
