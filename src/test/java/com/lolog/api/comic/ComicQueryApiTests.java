package com.lolog.api.comic;

import com.lolog.api.auth.TestTokens;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.card.Card;
import com.lolog.api.card.CardRepository;
import com.lolog.api.storage.ImageStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.OffsetDateTime;
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
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 앱의 만화 목록·자세히·지우기를 실제 PostgreSQL·Redis·SeaweedFS로 확인한다. Docker가 켜져 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ComicQueryApiTests {

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
		registry.add("lolog.storage.access-key", () -> "test-access-key");
		registry.add("lolog.storage.secret-key", () -> "test-secret-key");
	}

	private static final AtomicLong USERS = new AtomicLong(4000);

	@Autowired
	MockMvc mvc;

	@Autowired
	CardRepository cards;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ImageStorage storage;

	@Autowired
	ObjectMapper objectMapper;

	private long card(long userId, String summary) {
		Card card = new Card(null, userId, "웃김", summary, 1000, 9000, OffsetDateTime.parse("2026-11-10T12:30:01+09:00"));
		card.addSpeaker("화자1");
		card.addLine(1, "화자1", "대사", null, null);
		return cards.save(card).getId();
	}

	private long request(long userId, long cardId) throws Exception {
		String body = mvc.perform(post("/api/cards/" + cardId + "/comics").header("Authorization", TestTokens.bearer(String.valueOf(userId))))
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(body).get("comicId").asLong();
	}

	// 워커가 이미지를 올린 것처럼 저장소에 넣고 DONE으로 바꾼다.
	private byte[] finish(long userId, long comicId) {
		byte[] png = ("만화 " + comicId).getBytes();
		String key = ImageStorage.comicKey(userId, comicId);
		storage.putPng(key, png);
		jdbc.update("update comics set status = 'DONE', image_key = ? where id = ?", key, comicId);
		jdbc.update("update jobs set status = 'DONE' where comic_id = ?", comicId);
		return png;
	}

	@Test
	void 목록은_내_만화만_최신순이고_DONE만_imageUrl이_있다() throws Exception {
		long me = USERS.incrementAndGet();
		long first = request(me, card(me, "첫 카드"));
		finish(me, first);
		long cardId = card(me, "둘째 카드");
		long second = request(me, cardId);
		long other = USERS.incrementAndGet();
		request(other, card(other, "남의 카드"));

		String cursor = mvc.perform(get("/api/comics?size=1").header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.comics.length()").value(1))
			.andExpect(jsonPath("$.comics[0].id").value(second))
			.andExpect(jsonPath("$.comics[0].cardId").value(cardId))
			.andExpect(jsonPath("$.comics[0].cardSummary").value("둘째 카드"))
			.andExpect(jsonPath("$.comics[0].status").value("QUEUED"))
			.andExpect(jsonPath("$.comics[0].imageUrl").isEmpty())
			.andExpect(jsonPath("$.comics[0].createdAt").exists())
			.andExpect(jsonPath("$.nextCursor").isString())
			.andReturn().getResponse().getContentAsString().replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

		mvc.perform(get("/api/comics?size=1&cursor=" + cursor).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(jsonPath("$.comics.length()").value(1))
			.andExpect(jsonPath("$.comics[0].id").value(first))
			.andExpect(jsonPath("$.comics[0].status").value("DONE"))
			.andExpect(jsonPath("$.comics[0].imageUrl").isString())
			.andExpect(jsonPath("$.nextCursor").isEmpty());

		mvc.perform(get("/api/comics?size=0").header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("size"));
	}

	@Test
	void sort가_oldest면_오래된순으로_나눠_준다() throws Exception {
		long me = USERS.incrementAndGet();
		long first = request(me, card(me, "첫 카드"));
		long second = request(me, card(me, "둘째 카드"));

		String cursor = mvc.perform(get("/api/comics?sort=oldest&size=1").header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.comics[0].id").value(first))
			.andExpect(jsonPath("$.nextCursor").isString())
			.andReturn().getResponse().getContentAsString().replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

		mvc.perform(get("/api/comics?sort=oldest&size=1&cursor=" + cursor).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(jsonPath("$.comics.length()").value(1))
			.andExpect(jsonPath("$.comics[0].id").value(second))
			.andExpect(jsonPath("$.nextCursor").isEmpty());

		mvc.perform(get("/api/comics?sort=wrong").header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sort"));
	}

	@Test
	void 자세히의_imageUrl로_이미지를_열_수_있고_카드를_지워도_요약이_남는다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me, "치킨 주문하다 생긴 일");
		long comicId = request(me, cardId);
		byte[] png = finish(me, comicId);
		mvc.perform(delete("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isNoContent());

		String body = mvc.perform(get("/api/comics/" + comicId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(6))
			.andExpect(jsonPath("$.cardId").isEmpty())
			.andExpect(jsonPath("$.cardSummary").value("치킨 주문하다 생긴 일"))
			.andReturn().getResponse().getContentAsString();
		JsonNode node = objectMapper.readTree(body);
		assertThat(node.get("imageUrl").asString()).contains("X-Amz-Expires=600");
		HttpResponse<byte[]> image = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(node.get("imageUrl").asString())).build(),
					HttpResponse.BodyHandlers.ofByteArray());
		assertThat(image.statusCode()).isEqualTo(200);
		assertThat(image.body()).isEqualTo(png);

		mvc.perform(get("/api/comics/" + comicId).header("Authorization", TestTokens.bearer(String.valueOf(USERS.incrementAndGet()))))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMIC_NOT_FOUND"));
	}

	@Test
	void 지우면_이미지도_지우고_만들기_횟수는_그대로다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me, "카드");
		long comicId = request(me, cardId);
		finish(me, comicId);
		String key = ImageStorage.comicKey(me, comicId);

		mvc.perform(delete("/api/comics/" + comicId).header("Authorization", TestTokens.bearer(String.valueOf(USERS.incrementAndGet()))))
			.andExpect(status().isNotFound());
		mvc.perform(delete("/api/comics/" + comicId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isNoContent());

		assertThat(jdbc.queryForObject("select count(*) from comics where id = ?", Long.class, comicId)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from jobs where comic_id = ?", Long.class, comicId)).isZero();
		assertThatThrownBy(() -> storage.get(key)).isInstanceOf(NoSuchKeyException.class);
		mvc.perform(get("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(jsonPath("$.comicRemaining").value(2))
			.andExpect(jsonPath("$.comics.length()").value(0));
		mvc.perform(delete("/api/comics/" + comicId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isNotFound());
	}

	@Test
	void 만드는_중인_만화도_지울_수_있고_워커의_다음_요청은_404다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me, "카드");
		long comicId = request(me, cardId);
		long jobId = jdbc.queryForObject("select id from jobs where comic_id = ?", Long.class, comicId);

		mvc.perform(delete("/api/comics/" + comicId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isNoContent());

		mvc.perform(get("/internal/comics/" + comicId + "/source").header("X-Internal-Token", "internal-test-token"))
			.andExpect(status().isNotFound());
		mvc.perform(post("/internal/jobs/" + jobId + "/fail").header("X-Internal-Token", "internal-test-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"x\"}")).andExpect(status().isBadRequest());
		// 카드 잠금이 풀린다.
		mvc.perform(patch("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(me)))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"place\":\"집\"}")).andExpect(status().isOk());
		assertThat(jdbc.queryForObject("select comic_count from cards where id = ?", Integer.class, cardId)).isEqualTo(1);
	}

}
