package com.lolog.api.text;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import com.lolog.api.notification.PushSender;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 직접 쓴 글로 카드 만들기(#71, texts.md)를 실제 PostgreSQL·Redis로 확인한다. Docker가 켜져 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class TextApiTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Container
	@ServiceConnection(name = "redis")
	@SuppressWarnings("resource")
	static GenericContainer<?> redis = new GenericContainer<>("redis:latest").withExposedPorts(6379);

	private static final String S3_CONFIG = """
			{"identities":[{"name":"lolog-api","credentials":[{"accessKey":"test-access-key","secretKey":"test-secret-key"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
			""";

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

	private static final AtomicLong USERS = new AtomicLong(61000);
	private static final String INTERNAL = "internal-test-token";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	StringRedisTemplate redisTemplate;

	@MockitoBean
	PushSender sender;

	@BeforeEach
	void pushOn() {
		when(sender.enabled()).thenReturn(true);
	}

	private ResultActions call(MockHttpServletRequestBuilder request, long userId) throws Exception {
		return mvc.perform(request.header("Authorization", TestTokens.bearer(userId)));
	}

	private JsonNode json(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private ResultActions write(long userId, Map<String, Object> body) throws Exception {
		return call(post("/api/texts").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)),
				userId);
	}

	private long write(long userId) throws Exception {
		return json(write(userId, Map.of("title", "동생이랑 마트 간 날", "body", "오늘 동생이랑 마트에 갔는데...", "place", "마트"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("QUEUED"))).get("textId").asLong();
	}

	private List<JsonNode> messages(long textId) {
		return redisTemplate.opsForStream().range("lolog:detect", Range.unbounded()).stream()
			.map(r -> objectMapper.readTree((String) r.getValue().get("payload")))
			.filter(n -> n.has("textId") && n.get("textId").asLong() == textId)
			.toList();
	}

	private long jobId(long textId) {
		return jdbc.queryForObject("select id from jobs where text_id = ?", Long.class, textId);
	}

	private static String result(long jobId) {
		return """
				{"jobId": %d, "cards": [{"kind": "웃김", "summary": "동생이 마트에서 생긴 일", "startMs": null, "endMs": null,
				  "speakers": ["화자1", "화자2"],
				  "lines": [{"lineNo": 1, "speaker": "화자1", "text": "이거 사도 돼?", "startMs": null, "endMs": null},
				            {"lineNo": 2, "speaker": "화자2", "text": "안 돼"}]}]}
				""".formatted(jobId);
	}

	private ResultActions send(long textId, String body) throws Exception {
		return mvc.perform(post("/internal/texts/" + textId + "/cards").header("X-Internal-Token", INTERNAL)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body));
	}

	@Test
	void 글을_올리면_큐에_kind_text로_들어간다() throws Exception {
		long user = USERS.incrementAndGet();
		long textId = write(user);

		List<JsonNode> sent = messages(textId);
		assertThat(sent).hasSize(1);
		JsonNode message = sent.getFirst();
		assertThat(message.get("kind").asString()).isEqualTo("text");
		assertThat(message.get("jobId").asLong()).isEqualTo(jobId(textId));
		assertThat(message.get("title").asString()).isEqualTo("동생이랑 마트 간 날");
		assertThat(message.get("body").asString()).isEqualTo("오늘 동생이랑 마트에 갔는데...");
		assertThat(message.get("place").asString()).isEqualTo("마트");
		// 상황을 비우면 메시지에서 빠진다.
		assertThat(message.has("situation")).isFalse();

		call(get("/api/texts/" + textId), user).andExpect(status().isOk())
			.andExpect(jsonPath("$.textId").value(textId))
			.andExpect(jsonPath("$.source").value("WRITTEN"))
			.andExpect(jsonPath("$.status").value("QUEUED"))
			.andExpect(jsonPath("$.cardId").doesNotExist())
			.andExpect(jsonPath("$.body").value("오늘 동생이랑 마트에 갔는데..."))
			.andExpect(jsonPath("$.place").value("마트"))
			.andExpect(jsonPath("$.truncated").value(false))
			.andExpect(jsonPath("$.link").doesNotExist());
	}

	@Test
	void 제목_본문이_비었거나_본문이_길면_거절한다() throws Exception {
		long user = USERS.incrementAndGet();
		write(user, Map.of("title", "  ", "body", "본문")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("title"));
		write(user, Map.of("title", "제목")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("body"));
		write(user, Map.of("title", "제목", "body", "가".repeat(2001))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("TEXT_TOO_LONG"));
		write(user, Map.of("title", "제목", "body", "가".repeat(2000))).andExpect(status().isAccepted());
		write(user, Map.of("title", "제목", "body", "본문", "place", "가".repeat(21))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("place"));
	}

	@Test
	void 남의_글이나_없는_글은_404다() throws Exception {
		long textId = write(USERS.incrementAndGet());
		call(get("/api/texts/" + textId), USERS.incrementAndGet()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("TEXT_NOT_FOUND"));
		send(999_999, result(1)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("TEXT_NOT_FOUND"));
	}

	@Test
	void 결과를_받으면_카드가_생기고_푸시가_간다() throws Exception {
		long user = USERS.incrementAndGet();
		String token = "fcm-" + UUID.randomUUID();
		call(put("/api/devices/fcm-token").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"),
				user).andExpect(status().isNoContent());
		long textId = write(user);
		OffsetDateTime createdAt = jdbc.queryForObject("select created_at from texts where id = ?", OffsetDateTime.class,
				textId);

		send(textId, result(jobId(textId))).andExpect(status().isNoContent());
		// 같은 결과가 다시 와도 카드는 한 장이다.
		send(textId, result(jobId(textId))).andExpect(status().isNoContent());

		long cardId = jdbc.queryForObject("select id from cards where text_id = ?", Long.class, textId);
		assertThat(jdbc.queryForObject("select count(*) from cards where text_id = ?", Long.class, textId)).isEqualTo(1);
		verify(sender, timeout(5000)).send(List.of(token),
				Map.of("type", "CARDS_CREATED", "textId", String.valueOf(textId), "cardCount", "1"));

		call(get("/api/texts/" + textId), user).andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.cardId").value(cardId))
			// 직접 쓴 글은 카드가 생겨도 원문을 남긴다.
			.andExpect(jsonPath("$.body").value("오늘 동생이랑 마트에 갔는데..."));

		JsonNode detail = json(call(get("/api/cards/" + cardId), user).andExpect(status().isOk()));
		assertThat(detail.get("source").asString()).isEqualTo("WRITTEN");
		assertThat(detail.get("textId").asLong()).isEqualTo(textId);
		assertThat(detail.get("truncated").asBoolean()).isFalse();
		assertThat(detail.get("startMs").isNull()).isTrue();
		assertThat(detail.get("endMs").isNull()).isTrue();
		assertThat(detail.get("place").asString()).isEqualTo("마트");
		assertThat(detail.get("recordingId").isNull()).isTrue();
		assertThat(OffsetDateTime.parse(detail.get("recordedAt").asString()).toInstant()).isEqualTo(createdAt.toInstant());
		assertThat(detail.get("speakers")).hasSize(2);
		assertThat(detail.get("lines")).hasSize(2);
		assertThat(detail.get("audio").get("status").asString()).isEqualTo("NONE");

		JsonNode list = json(call(get("/api/cards"), user).andExpect(status().isOk()));
		JsonNode item = list.get("cards").get(0);
		assertThat(item.get("source").asString()).isEqualTo("WRITTEN");
		assertThat(item.get("textId").asLong()).isEqualTo(textId);
		assertThat(item.get("hasAudio").asBoolean()).isFalse();
	}

	@Test
	void 녹음_카드의_source는_RECORDING이다() throws Exception {
		long user = USERS.incrementAndGet();
		jdbc.update("""
				insert into cards (user_id, kind, summary, start_ms, end_ms, recorded_at) values (?, '웃김', '치킨', 0, 1000, now())
				""", user);
		JsonNode item = json(call(get("/api/cards"), user)).get("cards").get(0);
		assertThat(item.get("source").asString()).isEqualTo("RECORDING");
		assertThat(item.get("textId").isNull()).isTrue();
	}

	@Test
	void 워커가_실패를_알리면_FAILED다() throws Exception {
		long user = USERS.incrementAndGet();
		long textId = write(user);
		mvc.perform(post("/internal/jobs/" + jobId(textId) + "/fail").header("X-Internal-Token", INTERNAL)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"LLM 오류\"}")).andExpect(status().isNoContent());
		call(get("/api/texts/" + textId), user).andExpect(jsonPath("$.status").value("FAILED"));
		// 실패한 뒤 온 결과는 무시한다.
		send(textId, result(jobId(textId))).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from cards where text_id = ?", Long.class, textId)).isZero();
	}

	@Test
	void 결과_모양이_틀리면_400이다() throws Exception {
		long user = USERS.incrementAndGet();
		long textId = write(user);
		long otherText = write(user);
		// 다른 글의 작업
		send(textId, result(jobId(otherText))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("jobId"));
		// 카드는 한 장이어야 한다.
		send(textId, "{\"jobId\": " + jobId(textId) + ", \"cards\": []}").andExpect(status().isBadRequest());
		send(textId, result(jobId(textId)).replace("\"화자2\", \"text\"", "\"화자3\", \"text\""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("cards[0].lines[1].speaker"));
	}

	@Test
	void 카드를_지우면_글도_지운다() throws Exception {
		long user = USERS.incrementAndGet();
		long textId = write(user);
		send(textId, result(jobId(textId))).andExpect(status().isNoContent());
		long cardId = jdbc.queryForObject("select id from cards where text_id = ?", Long.class, textId);

		call(delete("/api/cards/" + cardId), user).andExpect(status().isNoContent());
		call(get("/api/texts/" + textId), user).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select count(*) from jobs where text_id = ?", Long.class, textId)).isZero();
	}

	@Test
	void 탈퇴하면_글을_지운다() throws Exception {
		long user = USERS.incrementAndGet();
		long textId = write(user);
		send(textId, result(jobId(textId))).andExpect(status().isNoContent());
		write(user);

		mvc.perform(delete("/internal/users/" + user).header("X-Internal-Token", INTERNAL))
			.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from texts where user_id = ?", Long.class, user)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from cards where user_id = ?", Long.class, user)).isZero();
	}

}
