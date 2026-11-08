package com.lolog.api.comic;

import com.lolog.api.auth.TestTokens;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.card.Card;
import com.lolog.api.card.CardRepository;
import com.lolog.api.storage.ImageStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

// 워커의 만화 단계(카드 내용 → 콘티 → 완성 이미지)를 실제 PostgreSQL·Redis·SeaweedFS로 확인한다. Docker가 켜져 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ComicWorkerApiTests {

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

	private static final AtomicLong USERS = new AtomicLong(3000);
	private static final String TOKEN = "internal-test-token";

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

	private record Made(long userId, long cardId, long comicId) {
	}

	// 카드를 만들고 앱처럼 만화를 요청한다.
	private Made comic() throws Exception {
		long userId = USERS.incrementAndGet();
		Card card = new Card(null, userId, "웃김", "치킨 주문하다 생긴 일", 1000, 9000,
				OffsetDateTime.parse("2026-11-10T12:30:01+09:00"));
		card.addSpeaker("화자1");
		card.addSpeaker("화자2");
		card.addLine(1, "화자1", "양념 반 후라이드 반", null, null);
		card.addLine(2, "화자2", "또 반반이야?", null, null);
		long cardId = cards.save(card).getId();
		String body = mvc.perform(post("/api/cards/" + cardId + "/comics").header("Authorization", TestTokens.bearer(String.valueOf(userId))))
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return new Made(userId, cardId, objectMapper.readTree(body).get("comicId").asLong());
	}

	private String comicStatus(long comicId) {
		return jdbc.queryForObject("select status from comics where id = ?", String.class, comicId);
	}

	private ResultActions source(long comicId) throws Exception {
		return mvc.perform(get("/internal/comics/" + comicId + "/source").header("X-Internal-Token", TOKEN));
	}

	private ResultActions storyboard(long comicId, String json) throws Exception {
		return mvc.perform(post("/internal/comics/" + comicId + "/storyboard").header("X-Internal-Token", TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private ResultActions image(long comicId, byte[] bytes) throws Exception {
		return mvc.perform(multipart("/internal/comics/" + comicId + "/image")
			.file(new MockMultipartFile("file", "comic.png", "image/png", bytes))
			.header("X-Internal-Token", TOKEN));
	}

	private static String board(Made m, int panels) {
		String panel = "{\"beat\":\"build-up\"}";
		return "{\"version\":1,\"comicId\":%d,\"cardId\":%d,\"layout\":\"vertical4\",\"panels\":[%s]}"
			.formatted(m.comicId(), m.cardId(), String.join(",", java.util.Collections.nCopies(panels, panel)));
	}

	private static byte[] png(int width, int height) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out);
		return out.toByteArray();
	}

	@Test
	void 카드_내용_콘티_이미지를_차례로_받아_완성한다() throws Exception {
		Made m = comic();

		source(m.comicId()).andExpect(status().isOk())
			.andExpect(jsonPath("$.comicId").value(m.comicId()))
			.andExpect(jsonPath("$.cardId").value(m.cardId()))
			.andExpect(jsonPath("$.kind").value("웃김"))
			.andExpect(jsonPath("$.place").isEmpty())
			.andExpect(jsonPath("$.speakers[1].key").value("화자2"))
			.andExpect(jsonPath("$.speakers[1].name").isEmpty())
			.andExpect(jsonPath("$.lines[0].id").isNumber())
			.andExpect(jsonPath("$.lines[0].speaker").value("화자1"))
			.andExpect(jsonPath("$.lines[0].text").value("양념 반 후라이드 반"));
		assertThat(comicStatus(m.comicId())).isEqualTo("STORYBOARD");

		storyboard(m.comicId(), board(m, 4)).andExpect(status().isNoContent());
		assertThat(comicStatus(m.comicId())).isEqualTo("DRAWING");
		String saved = jdbc.queryForObject("select storyboard::text from comics where id = ?", String.class, m.comicId());
		assertThat(objectMapper.readTree(saved)).isEqualTo(objectMapper.readTree(board(m, 4)));

		byte[] png = png(800, 3152);
		image(m.comicId(), png).andExpect(status().isNoContent());
		assertThat(comicStatus(m.comicId())).isEqualTo("DONE");
		String key = jdbc.queryForObject("select image_key from comics where id = ?", String.class, m.comicId());
		assertThat(key).isEqualTo("users/" + m.userId() + "/comics/" + m.comicId() + ".png");
		assertThat(storage.get(key)).isEqualTo(png);
		assertThat(jdbc.queryForObject("select status from jobs where comic_id = ?", String.class, m.comicId()))
			.isEqualTo("DONE");

		// 끝났으므로 카드를 다시 고칠 수 있고, 다음 만화도 요청할 수 있다.
		mvc.perform(post("/api/cards/" + m.cardId() + "/comics").header("Authorization", TestTokens.bearer(String.valueOf(m.userId()))))
			.andExpect(status().isAccepted());
	}

	@Test
	void 콘티는_기본만_검사한다() throws Exception {
		Made m = comic();
		storyboard(m.comicId(), board(m, 4).replace("\"version\":1", "\"version\":2"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("version"));
		storyboard(m.comicId(), board(m, 4).replace("\"comicId\":" + m.comicId(), "\"comicId\":1"))
			.andExpect(jsonPath("$.field").value("comicId"));
		storyboard(m.comicId(), board(m, 4).replace("\"cardId\":" + m.cardId(), "\"cardId\":1"))
			.andExpect(jsonPath("$.field").value("cardId"));
		storyboard(m.comicId(), board(m, 3)).andExpect(jsonPath("$.field").value("panels"));
		storyboard(m.comicId(), "[1,2]").andExpect(status().isBadRequest());
		storyboard(m.comicId(), "{오류").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		assertThat(comicStatus(m.comicId())).isEqualTo("QUEUED");
	}

	@Test
	void 이미지가_PNG가_아니거나_크기가_다르면_400이고_20MB를_넘으면_413이다() throws Exception {
		Made m = comic();
		image(m.comicId(), "PNG 아님".getBytes()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("file"));
		image(m.comicId(), png(800, 800)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("file"));
		mvc.perform(multipart("/internal/comics/" + m.comicId() + "/image").header("X-Internal-Token", TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("file"));
		image(m.comicId(), new byte[20 * 1024 * 1024 + 1]).andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
		assertThat(comicStatus(m.comicId())).isEqualTo("QUEUED");
	}

	@Test
	void 끝난_만화는_source가_404이고_콘티_이미지는_무시한다() throws Exception {
		Made m = comic();
		long jobId = jdbc.queryForObject("select id from jobs where comic_id = ?", Long.class, m.comicId());
		mvc.perform(post("/internal/jobs/" + jobId + "/fail").header("X-Internal-Token", TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"그림 실패\"}")).andExpect(status().isNoContent());

		source(m.comicId()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COMIC_NOT_FOUND"));
		storyboard(m.comicId(), board(m, 4)).andExpect(status().isNoContent());
		image(m.comicId(), png(800, 3152)).andExpect(status().isNoContent());

		assertThat(comicStatus(m.comicId())).isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("select storyboard::text from comics where id = ?", String.class, m.comicId()))
			.isNull();
		assertThat(jdbc.queryForObject("select image_key from comics where id = ?", String.class, m.comicId())).isNull();
	}

	@Test
	void 이미지와_fail이_동시에_와도_교착_없이_하나로_끝난다() throws Exception {
		byte[] png = png(800, 3152);
		for (int i = 0; i < 5; i++) {
			Made m = comic();
			long jobId = jdbc.queryForObject("select id from jobs where comic_id = ?", Long.class, m.comicId());
			java.util.List<java.util.concurrent.Callable<Integer>> calls = java.util.List.of(
					() -> image(m.comicId(), png).andReturn().getResponse().getStatus(),
					() -> mvc.perform(post("/internal/jobs/" + jobId + "/fail").header("X-Internal-Token", TOKEN)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"reason\":\"겹침\"}")).andReturn().getResponse().getStatus());
			try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
				for (var f : pool.invokeAll(calls)) {
					assertThat(f.get()).isEqualTo(204);
				}
			}
			String status = comicStatus(m.comicId());
			String jobStatus = jdbc.queryForObject("select status from jobs where id = ?", String.class, jobId);
			assertThat(status).isIn("DONE", "FAILED");
			assertThat(jobStatus).isEqualTo(status);
			int count = jdbc.queryForObject("select comic_count from cards where id = ?", Integer.class, m.cardId());
			assertThat(count).isEqualTo("DONE".equals(status) ? 1 : 0);
		}
	}

	@Test
	void 없는_만화는_404이고_토큰이_없으면_401이다() throws Exception {
		source(999_999).andExpect(status().isNotFound());
		storyboard(999_999, "{}").andExpect(status().isNotFound());
		image(999_999, png(800, 3152)).andExpect(status().isNotFound());
		mvc.perform(get("/internal/comics/1/source")).andExpect(status().isUnauthorized());
	}

}
