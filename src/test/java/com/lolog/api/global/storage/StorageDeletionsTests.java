package com.lolog.api.global.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

// 저장소 파일 지우기(storage_deletions)를 실제 PostgreSQL·SeaweedFS로 확인한다. Docker가 켜져 있어야 한다.
@SpringBootTest(properties = "lolog.storage.deletion-retry-initial-delay-ms=3600000")
@Testcontainers
class StorageDeletionsTests {

	private static final String S3_CONFIG = """
			{"identities":[{"name":"lolog-api","credentials":[{"accessKey":"test-access-key","secretKey":"test-secret-key"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
			""";

	private static final AtomicLong USERS = new AtomicLong(91000);

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

	@Container
	@SuppressWarnings("resource")
	static GenericContainer<?> seaweedfs = new GenericContainer<>("chrislusf/seaweedfs:4.48")
		.withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data")
		.withCopyToContainer(Transferable.of(S3_CONFIG), "/etc/seaweedfs/s3.json")
		.withExposedPorts(8333)
		.waitingFor(Wait.forListeningPort());

	@DynamicPropertySource
	static void storage(DynamicPropertyRegistry registry) {
		String url = "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333);
		registry.add("lolog.storage.endpoint", () -> url);
		registry.add("lolog.storage.public-endpoint", () -> url);
	}

	@MockitoSpyBean
	ImageStorage storage;

	@Autowired
	StorageDeletions deletions;

	@Autowired
	TransactionTemplate tx;

	@Autowired
	JdbcTemplate jdbc;

	@AfterEach
	void storageBack() {
		doCallRealMethod().when(storage).delete(anyString());
	}

	// 파일 하나를 올리고 키를 돌려준다.
	private String put() {
		String key = ImageStorage.comicKey(USERS.incrementAndGet(), 1);
		storage.putPng(key, "그림".getBytes(StandardCharsets.UTF_8));
		return key;
	}

	private int rows(String key) {
		return jdbc.queryForObject("select count(*) from storage_deletions where object_key = ?", Integer.class, key);
	}

	@Test
	void 되돌린_트랜잭션의_지우기는_남지_않고_파일도_그대로다() {
		String key = put();

		tx.executeWithoutResult(status -> {
			deletions.schedule(key);
			status.setRollbackOnly();
		});

		assertThat(rows(key)).isZero();
		assertThat(storage.get(key)).isNotEmpty();
	}

	@Test
	void 커밋하면_바로_지우고_줄도_뺀다() {
		String key = put();

		tx.executeWithoutResult(status -> deletions.schedule(key));

		assertThat(rows(key)).isZero();
		assertThatThrownBy(() -> storage.get(key)).isInstanceOf(NoSuchKeyException.class);
	}

	@Test
	void 지우기에_실패하면_줄이_남고_다시_지우기에서_지운다() {
		String key = put();
		doThrow(new IllegalStateException("저장소 장애")).when(storage).delete(key);

		// 커밋 뒤 지우기가 실패해도 부른 쪽에는 예외가 오지 않는다.
		tx.executeWithoutResult(status -> deletions.schedule(key));

		Map<String, Object> row = jdbc.queryForMap("select attempts, last_error from storage_deletions where object_key = ?", key);
		assertThat(row.get("attempts")).isEqualTo(1);
		// 예외 메시지는 남기지 않고 종류만 남긴다.
		assertThat(row.get("last_error")).isEqualTo("IllegalStateException");
		assertThat(storage.get(key)).isNotEmpty();

		doCallRealMethod().when(storage).delete(key);
		deletions.retry();

		assertThat(rows(key)).isZero();
		assertThatThrownBy(() -> storage.get(key)).isInstanceOf(NoSuchKeyException.class);
	}

	@Test
	void 없는_파일을_지워도_성공이다() {
		String key = ImageStorage.comicKey(USERS.incrementAndGet(), 1);

		tx.executeWithoutResult(status -> deletions.schedule(key));

		assertThat(rows(key)).isZero();
	}

	@Test
	void 접두어_아래를_모두_지운다() {
		long userId = USERS.incrementAndGet();
		String video = ImageStorage.videoKey(userId, 5);
		String thumb = ImageStorage.videoThumbKey(userId, 5);
		String other = ImageStorage.videoKey(userId, 6);
		storage.put(video, "영상".getBytes(StandardCharsets.UTF_8), "video/mp4");
		storage.put(thumb, "썸네일".getBytes(StandardCharsets.UTF_8), "image/jpeg");
		storage.put(other, "다른 영상".getBytes(StandardCharsets.UTF_8), "video/mp4");

		tx.executeWithoutResult(status -> deletions.schedulePrefix(ImageStorage.videoPrefix(userId, 5)));

		assertThat(rows(ImageStorage.videoPrefix(userId, 5))).isZero();
		assertThatThrownBy(() -> storage.get(video)).isInstanceOf(NoSuchKeyException.class);
		assertThatThrownBy(() -> storage.get(thumb)).isInstanceOf(NoSuchKeyException.class);
		assertThat(storage.get(other)).isNotEmpty();
	}

	@Test
	void 같은_키에_다시_올리기_전에_빼면_새_파일은_지워지지_않는다() {
		String key = put();
		doThrow(new IllegalStateException("저장소 장애")).when(storage).delete(key);
		tx.executeWithoutResult(status -> deletions.schedule(key));
		assertThat(rows(key)).isOne();
		doCallRealMethod().when(storage).delete(key);

		tx.executeWithoutResult(status -> {
			deletions.cancel(key);
			storage.putPng(key, "새 그림".getBytes(StandardCharsets.UTF_8));
		});
		deletions.retry();

		assertThat(rows(key)).isZero();
		assertThat(storage.get(key)).isEqualTo("새 그림".getBytes(StandardCharsets.UTF_8));
	}

}
