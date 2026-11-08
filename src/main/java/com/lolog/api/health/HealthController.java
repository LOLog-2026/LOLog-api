package com.lolog.api.health;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.data.redis.connection.RedisConnectionCommands;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// 서버 상태 확인(API DTO 명세 0장). 토큰 없이 부르고, DB·Redis에 붙으면 200 UP, 못 붙으면 503 DOWN.
// 배포 헬스체크에도 쓰므로, 저장소가 응답하지 않아도 오래 기다리지 않게 확인마다 제한 시간을 둔다.
@RestController
public class HealthController {

	static final Duration TIMEOUT = Duration.ofSeconds(2);

	private final JdbcTemplate jdbc;
	private final StringRedisTemplate redis;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public HealthController(JdbcTemplate jdbc, StringRedisTemplate redis) {
		this.jdbc = jdbc;
		this.redis = redis;
	}

	@PreDestroy
	void close() {
		executor.shutdownNow();
	}

	public record Health(String status) {
	}

	@GetMapping("/api/health")
	public ResponseEntity<Health> health() {
		boolean up = check(() -> jdbc.queryForObject("select 1", Integer.class))
				&& check(() -> redis.execute((RedisCallback<String>) RedisConnectionCommands::ping));
		return up ? ResponseEntity.ok(new Health("UP"))
				: ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(new Health("DOWN"));
	}

	private boolean check(Supplier<?> probe) {
		try {
			return CompletableFuture.supplyAsync(probe, executor).get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) != null;
		}
		catch (Exception e) {
			// 원인은 남기지 않는다. 헬스체크는 자주 불리고, 실패 원인은 각 저장소 로그로 본다.
			return false;
		}
	}

}
