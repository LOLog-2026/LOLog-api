package com.lolog.api.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import com.lolog.api.storage.ImageStorage;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.ObjectMapper;

// DELETE /internal/users/{id}(탈퇴 데이터 지우기)를 실제 PostgreSQL·Redis·SeaweedFS로 확인한다. Docker가 켜져 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class UserDataDeleteTests {

	private static final String S3_CONFIG = """
			{"identities":[{"name":"lolog-api","credentials":[{"accessKey":"test-access-key","secretKey":"test-secret-key"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
			""";

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Container
	@ServiceConnection(name = "redis")
	@SuppressWarnings("resource")
	static GenericContainer<?> redis = new GenericContainer<>("redis:latest").withExposedPorts(6379);

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

	private static final AtomicLong USERS = new AtomicLong(6000);
	private static final String[] TABLES = { "recordings", "cards", "comics", "jobs", "device_tokens" };

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@MockitoSpyBean
	ImageStorage storage;

	@Autowired
	ObjectMapper objectMapper;

	// 녹음(감지 작업 포함), 카드, 만화(작업 포함)와 이미지 2장, 푸시 토큰을 만든다.
	private void fill(long userId) throws Exception {
		String body = """
				{"clientRecordingId":"%s","partNo":1,"isLast":false,"startedAt":"2026-11-10T12:30:00+09:00","durationSec":60,"sentences":[{"startMs":1,"endMs":2,"text":"대화 글"}]}
				""".formatted(UUID.randomUUID());
		String response = mvc.perform(post("/api/recordings").header("Authorization", TestTokens.bearer(userId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		long recordingId = objectMapper.readTree(response).get("recordingId").asLong();
		long cardId = jdbc.queryForObject("insert into cards (recording_id, user_id, kind, summary, start_ms, end_ms, recorded_at) "
				+ "values (?, ?, '웃김', '요약', 0, 1, now()) returning id", Long.class, recordingId, userId);
		jdbc.update("insert into card_speakers (card_id, speaker_key) values (?, '화자1')", cardId);
		jdbc.update("insert into card_lines (card_id, line_no, speaker_key, text) values (?, 1, '화자1', '대사')", cardId);
		String comic = mvc.perform(post("/api/cards/" + cardId + "/comics").header("Authorization", TestTokens.bearer(userId)))
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		long comicId = objectMapper.readTree(comic).get("comicId").asLong();
		storage.putPng(ImageStorage.comicKey(userId, comicId), "그림".getBytes());
		storage.putPng(ImageStorage.comicKey(userId, comicId + 1000), "남은 그림".getBytes());
		jdbc.update("insert into device_tokens (user_id, token) values (?, ?)", userId, "fcm-" + UUID.randomUUID());
	}

	private long count(String table, long userId) {
		return jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Long.class, userId);
	}

	private long imageCount(long userId) {
		return jdbc.queryForList("select id from comics where user_id = ?", Long.class, userId).size();
	}

	private ResultActions deleteUser(long userId) throws Exception {
		return mvc.perform(delete("/internal/users/" + userId).header("X-Internal-Token", "internal-test-token"));
	}

	@Test
	void 탈퇴한_사용자의_데이터를_모두_지우고_다른_사용자는_그대로다() throws Exception {
		long gone = USERS.incrementAndGet();
		long other = USERS.incrementAndGet();
		fill(gone);
		fill(other);
		long goneComic = jdbc.queryForObject("select id from comics where user_id = ?", Long.class, gone);
		long otherComic = jdbc.queryForObject("select id from comics where user_id = ?", Long.class, other);

		deleteUser(gone).andExpect(status().isNoContent());

		for (String table : TABLES) {
			assertThat(count(table, gone)).as(table).isZero();
			assertThat(count(table, other)).as(table).isPositive();
		}
		assertThat(jdbc.queryForObject("select count(*) from recording_parts p join recordings r on r.id = p.recording_id where r.user_id = ?",
				Long.class, gone)).isZero();
		assertThatThrownBy(() -> storage.get(ImageStorage.comicKey(gone, goneComic))).isInstanceOf(NoSuchKeyException.class);
		assertThatThrownBy(() -> storage.get(ImageStorage.comicKey(gone, goneComic + 1000)))
			.isInstanceOf(NoSuchKeyException.class);
		assertThat(storage.get(ImageStorage.comicKey(other, otherComic))).isEqualTo("그림".getBytes());

		// 다시 불러도, 없는 사용자여도 204
		deleteUser(gone).andExpect(status().isNoContent());
		deleteUser(999_999).andExpect(status().isNoContent());
	}

	@Test
	void 이미지를_못_지우면_500이고_다시_부르면_마저_지운다() throws Exception {
		long gone = USERS.incrementAndGet();
		fill(gone);
		long comicId = jdbc.queryForObject("select id from comics where user_id = ?", Long.class, gone);

		doThrow(new IllegalStateException("저장소 장애")).when(storage).deleteUser(anyLong());
		deleteUser(gone).andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
		assertThat(count("cards", gone)).isZero();
		assertThat(storage.get(ImageStorage.comicKey(gone, comicId))).isNotEmpty();

		doCallRealMethod().when(storage).deleteUser(anyLong());
		deleteUser(gone).andExpect(status().isNoContent());
		assertThatThrownBy(() -> storage.get(ImageStorage.comicKey(gone, comicId))).isInstanceOf(NoSuchKeyException.class);
		assertThat(imageCount(gone)).isZero();
	}

	@Test
	void 내부_토큰이_없으면_401이다() throws Exception {
		mvc.perform(delete("/internal/users/1")).andExpect(status().isUnauthorized());
		mvc.perform(delete("/internal/users/1").header("Authorization", TestTokens.bearer(1)))
			.andExpect(status().isUnauthorized());
	}

}
