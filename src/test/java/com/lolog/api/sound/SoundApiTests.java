package com.lolog.api.sound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.storage.ImageStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 쇼츠 효과음·배경음 소리 목록(#84, sounds.md 5장)을 실제 PostgreSQL·SeaweedFS로 확인한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SoundApiTests {

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

	private static final String INTERNAL = "internal-test-token";
	private static final AtomicLong FREESOUND = new AtomicLong(840000);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ImageStorage storage;

	@Autowired
	ObjectMapper objectMapper;

	static byte[] ogg(int size, int mark) {
		byte[] b = new byte[size];
		System.arraycopy("OggS".getBytes(), 0, b, 0, 4);
		b[size - 1] = (byte) mark;
		return b;
	}

	private MockMultipartHttpServletRequestBuilder put(long freesoundId, byte[] file) {
		MockMultipartHttpServletRequestBuilder request = multipart(HttpMethod.PUT, "/internal/sounds/" + freesoundId);
		if (file != null) {
			request.file(new MockMultipartFile("file", "s.ogg", "audio/ogg", file));
		}
		request.header("X-Internal-Token", INTERNAL);
		return request;
	}

	private ResultActions putSound(long freesoundId, String kind, String query, byte[] file) throws Exception {
		return mvc.perform(put(freesoundId, file).param("kind", kind)
			.param("query", query)
			.param("title", "Door Slam " + freesoundId)
			.param("durationMs", "1200")
			.param("sourceUrl", "https://freesound.org/s/" + freesoundId + "/")
			.param("license", "CC0"));
	}

	private ResultActions list(String kind, String query) throws Exception {
		return mvc.perform(get("/internal/sounds").param("kind", kind).param("query", query).header("X-Internal-Token",
				INTERNAL));
	}

	private JsonNode json(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	@Test
	void 소리를_넣으면_검색어로_목록이_나오고_서명_주소로_받는다() throws Exception {
		long a = FREESOUND.incrementAndGet();
		long b = FREESOUND.incrementAndGet();
		long first = json(putSound(a, "SFX", "door_slam", ogg(64, 1)).andExpect(status().isOk())).get("id").asLong();
		putSound(b, "SFX", "door_slam", ogg(64, 2)).andExpect(status().isOk());
		putSound(FREESOUND.incrementAndGet(), "BGM", "bgm_comic", ogg(64, 3)).andExpect(status().isOk());

		JsonNode sounds = json(list("SFX", "door_slam").andExpect(status().isOk())).get("sounds");
		assertThat(sounds.size()).isEqualTo(2);
		assertThat(sounds.get(0).get("id").asLong()).isEqualTo(first);
		assertThat(sounds.get(0).get("kind").asString()).isEqualTo("SFX");
		assertThat(sounds.get(0).get("durationMs").asInt()).isEqualTo(1200);
		assertThat(sounds.get(0).size()).isEqualTo(4);
		HttpResponse<byte[]> got = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(sounds.get(0).get("url").asString())).build(),
					HttpResponse.BodyHandlers.ofByteArray());
		assertThat(got.statusCode()).isEqualTo(200);
		assertThat(got.body()).isEqualTo(ogg(64, 1));
		assertThat(jdbc.queryForObject("select object_key from sound_library where id = ?", String.class, first))
			.isEqualTo("defaults/sounds/" + first + ".ogg");

		// 종류가 다르거나 없는 검색어는 빈 목록이다(오류 아님).
		list("BGM", "door_slam").andExpect(status().isOk()).andExpect(jsonPath("$.sounds.length()").value(0));
		list("SFX", "nothing_here").andExpect(status().isOk()).andExpect(jsonPath("$.sounds.length()").value(0));
	}

	@Test
	void 같은_freesoundId로_넣으면_덮어쓰고_빼면_파일도_지운다() throws Exception {
		long a = FREESOUND.incrementAndGet();
		long id = json(putSound(a, "SFX", "thud", ogg(64, 1)).andExpect(status().isOk())).get("id").asLong();
		long again = json(putSound(a, "SFX", "whoosh", ogg(64, 9)).andExpect(status().isOk())).get("id").asLong();
		assertThat(again).isEqualTo(id);
		list("SFX", "thud").andExpect(jsonPath("$.sounds.length()").value(0));
		list("SFX", "whoosh").andExpect(jsonPath("$.sounds.length()").value(1));
		assertThat(storage.get(ImageStorage.soundKey(id))).isEqualTo(ogg(64, 9));

		mvc.perform(delete("/internal/sounds/" + a).header("X-Internal-Token", INTERNAL)).andExpect(status().isNoContent());
		mvc.perform(delete("/internal/sounds/" + a).header("X-Internal-Token", INTERNAL)).andExpect(status().isNoContent());
		list("SFX", "whoosh").andExpect(jsonPath("$.sounds.length()").value(0));
		assertThatThrownBy(() -> storage.get(ImageStorage.soundKey(id)))
			.isInstanceOf(software.amazon.awssdk.services.s3.model.NoSuchKeyException.class);
	}

	@Test
	void 값_검사와_내부_토큰() throws Exception {
		long a = FREESOUND.incrementAndGet();
		putSound(a, "VOICE", "door_slam", ogg(64, 1)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("kind"));
		putSound(a, "SFX", "Door Slam", ogg(64, 1)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("query"));
		putSound(a, "SFX", "a".repeat(31), ogg(64, 1)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("query"));
		putSound(a, "SFX", "door_slam", null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file"));
		putSound(a, "SFX", "door_slam", new byte[] { 'R', 'I', 'F', 'F', 0 }).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("file"));
		putSound(a, "SFX", "door_slam", ogg((int) SoundService.MAX_FILE_BYTES + 1, 1))
			.andExpect(status().isPayloadTooLarge());
		mvc.perform(put(a, ogg(64, 1)).param("kind", "SFX").param("query", "door_slam").param("durationMs", "1000")
			.param("sourceUrl", "https://freesound.org/s/1/")
			.param("license", "CC-BY")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("license"));
		mvc.perform(put(a, ogg(64, 1)).param("kind", "SFX").param("query", "door_slam").param("durationMs", "0")
			.param("sourceUrl", "https://freesound.org/s/1/")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("durationMs"));
		mvc.perform(put(a, ogg(64, 1)).param("kind", "SFX").param("query", "door_slam").param("durationMs", "1000"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("sourceUrl"));
		assertThat(jdbc.queryForObject("select count(*) from sound_library where freesound_id = ?", Long.class, a)).isZero();

		list("SFX", "BAD!").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("query"));
		mvc.perform(get("/internal/sounds").param("query", "door_slam").header("X-Internal-Token", INTERNAL))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("kind"));
		mvc.perform(get("/internal/sounds").param("kind", "SFX").param("query", "door_slam"))
			.andExpect(status().isUnauthorized());
	}

}
