package com.lolog.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 실제 PostgreSQL에 Flyway 마이그레이션까지 적용해 컨텍스트를 띄운다. Docker가 켜져 있어야 한다.
@SpringBootTest
@Testcontainers
class LologApiApplicationTests {

	// compose.yaml과 같은 이미지를 쓴다.
	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Test
	void contextLoads() {
	}

}
