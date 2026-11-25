package com.lolog.api.domain.sound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.sound.service.SoundService;
import com.lolog.api.domain.sound.service.SoundTextEmbedder;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
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
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 쇼츠 효과음·배경음(#84 소리 목록, #106 벡터 검색, sounds.md 5장)을 실제 PostgreSQL(pgvector)·SeaweedFS로 확인한다.
// CLAP 글 모델은 가짜로 바꿔 끼운다. "axis 3"은 3번 칸만 1인 벡터이고, "broken"은 모델을 못 읽은 것처럼 503을 던진다.
@SpringBootTest(properties = { "lolog.sound.max-distance.sfx=0.5", "lolog.sound.max-distance.bgm=0.2" })
@AutoConfigureMockMvc
@Testcontainers
class SoundApiTests {

	private static final String S3_CONFIG = """
			{"identities":[{"name":"lolog-api","credentials":[{"accessKey":"test-access-key","secretKey":"test-secret-key"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
			""";

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"));

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
	private static final String MODEL = "laion/clap-htsat-unfused";

	// 글을 정해진 벡터로 바꾸는 가짜 CLAP 글 모델.
	@TestConfiguration
	static class FakeEmbedder {

		@Bean
		@Primary
		SoundTextEmbedder fakeSoundTextEmbedder() {
			return new SoundTextEmbedder() {
				@Override
				public float[] embed(String text) {
					if (text.equals("broken")) {
						throw new ApiException(ErrorCode.EMBEDDING_UNAVAILABLE);
					}
					return axis(Integer.parseInt(text.replace("axis ", "")));
				}

				@Override
				public String modelName() {
					return MODEL;
				}
			};
		}

	}

	// i번 칸만 1인 512차원 벡터
	static float[] axis(int i) {
		float[] v = new float[512];
		v[i] = 1;
		return v;
	}

	// 벡터를 PUT의 embedding 칸(JSON 배열)으로 바꾼다.
	static String json(float[] v) {
		StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < v.length; i++) {
			sb.append(i == 0 ? "" : ",").append(v[i]);
		}
		return sb.append(']').toString();
	}
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
		return putSound(freesoundId, kind, query, file, axis(0));
	}

	private ResultActions putSound(long freesoundId, String kind, String query, byte[] file, float[] embedding)
			throws Exception {
		return mvc.perform(put(freesoundId, file).param("kind", kind)
			.param("query", query)
			.param("title", "Door Slam " + freesoundId)
			.param("durationMs", "1200")
			.param("sourceUrl", "https://freesound.org/s/" + freesoundId + "/")
			.param("license", "CC0")
			.param("embedding", json(embedding))
			.param("embeddingModel", MODEL));
	}

	private ResultActions search(String body) throws Exception {
		return mvc.perform(post("/internal/sounds/search").contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.header("X-Internal-Token", INTERNAL));
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
		putSound(a, "SFX", "a".repeat(51), ogg(64, 1)).andExpect(status().isBadRequest())
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
		putSound(a, "SFX", "door_slam", ogg(64, 1), new float[511]).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("embedding"));
		putSound(a, "SFX", "door_slam", ogg(64, 1), new float[512]).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("embedding"));
		mvc.perform(put(a, ogg(64, 1)).param("kind", "SFX").param("query", "door_slam").param("durationMs", "1000")
			.param("sourceUrl", "https://freesound.org/s/1/")
			.param("embedding", "[1, \"x\"]")
			.param("embeddingModel", MODEL)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("embedding"));
		mvc.perform(put(a, ogg(64, 1)).param("kind", "SFX").param("query", "door_slam").param("durationMs", "1000")
			.param("sourceUrl", "https://freesound.org/s/1/")
			.param("embedding", json(axis(1)))
			.param("embeddingModel", "other/model")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("embeddingModel"));
		assertThat(jdbc.queryForObject("select count(*) from sound_library where freesound_id = ?", Long.class, a)).isZero();

		list("SFX", "BAD!").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("query"));
		mvc.perform(get("/internal/sounds").param("query", "door_slam").header("X-Internal-Token", INTERNAL))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("kind"));
		mvc.perform(get("/internal/sounds").param("kind", "SFX").param("query", "door_slam"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void 설명_글과_가까운_소리를_거리_순으로_찾는다() throws Exception {
		// 300번 칸 쪽 소리 셋: 똑같음(거리 0), 비스듬함(거리 1-1/√2), 반대(거리 2). 다른 테스트의 소리는 직각이라 거리 1이다.
		// 효과음 거리 기준 0.5보다 먼 소리는 나오지 않는다.
		float[] tilted = axis(300);
		tilted[301] = 1;
		float[] opposite = new float[512];
		opposite[300] = -1;
		long near = json(putSound(FREESOUND.incrementAndGet(), "SFX", "door", ogg(64, 1), axis(300))
			.andExpect(status().isOk())).get("id").asLong();
		long mid = json(putSound(FREESOUND.incrementAndGet(), "SFX", "door", ogg(64, 2), tilted)
			.andExpect(status().isOk())).get("id").asLong();
		putSound(FREESOUND.incrementAndGet(), "SFX", "door", ogg(64, 3), opposite).andExpect(status().isOk());
		// 같은 벡터라도 배경음은 효과음 검색에 나오지 않는다.
		long bgm = json(putSound(FREESOUND.incrementAndGet(), "BGM", "music", ogg(64, 4), axis(300))
			.andExpect(status().isOk())).get("id").asLong();
		// 배경음 기준은 0.2라 효과음에서는 나오는 비스듬한 소리(거리 0.29)가 배경음에서는 빠진다.
		putSound(FREESOUND.incrementAndGet(), "BGM", "music", ogg(64, 5), tilted).andExpect(status().isOk());

		JsonNode sounds = json(search("{\"kind\":\"SFX\",\"text\":\"axis 300\"}").andExpect(status().isOk()))
			.get("sounds");
		assertThat(sounds.size()).isEqualTo(2);
		assertThat(sounds.get(0).get("id").asLong()).isEqualTo(near);
		assertThat(sounds.get(0).get("distance").asDouble()).isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
		assertThat(sounds.get(1).get("id").asLong()).isEqualTo(mid);
		assertThat(sounds.get(1).get("distance").asDouble())
			.isCloseTo(1 - 1 / Math.sqrt(2), org.assertj.core.data.Offset.offset(1e-5));
		assertThat(sounds.get(0).get("kind").asString()).isEqualTo("SFX");
		assertThat(sounds.get(0).get("durationMs").asInt()).isEqualTo(1200);
		assertThat(sounds.get(0).size()).isEqualTo(5);
		HttpResponse<byte[]> got = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(sounds.get(0).get("url").asString())).build(),
					HttpResponse.BodyHandlers.ofByteArray());
		assertThat(got.body()).isEqualTo(ogg(64, 1));

		// limit만큼만 준다.
		search("{\"kind\":\"SFX\",\"text\":\"axis 300\",\"limit\":1}").andExpect(status().isOk())
			.andExpect(jsonPath("$.sounds.length()").value(1));
		// 배경음 검색에는 배경음만, 배경음 기준 안쪽만 나온다.
		search("{\"kind\":\"BGM\",\"text\":\"axis 300\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.sounds.length()").value(1))
			.andExpect(jsonPath("$.sounds[0].id").value(bgm));
		// 가까운 소리가 없으면 빈 배열이다(오류 아님).
		search("{\"kind\":\"SFX\",\"text\":\"axis 400\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.sounds.length()").value(0));
	}

	@Test
	void 벡터가_없거나_다른_모델로_만든_소리는_찾지_않는다() throws Exception {
		long a = FREESOUND.incrementAndGet();
		putSound(a, "SFX", "old", ogg(64, 1), axis(200)).andExpect(status().isOk());
		// V23 전에 넣은 소리처럼 벡터를 비우고, 다른 모델로 만든 벡터를 하나 둔다.
		jdbc.update("update sound_library set embedding = null, embedding_model = null where freesound_id = ?", a);
		long b = FREESOUND.incrementAndGet();
		putSound(b, "SFX", "old", ogg(64, 2), axis(200)).andExpect(status().isOk());
		jdbc.update("update sound_library set embedding_model = 'other/model' where freesound_id = ?", b);

		search("{\"kind\":\"SFX\",\"text\":\"axis 200\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.sounds.length()").value(0));
	}

	@Test
	void 검색_값_검사와_모델_오류() throws Exception {
		search("{\"kind\":\"VOICE\",\"text\":\"axis 1\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("kind"));
		search("{\"kind\":\"SFX\",\"text\":\"  \"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("text"));
		search("{\"kind\":\"SFX\",\"text\":\"" + "a".repeat(201) + "\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("text"));
		search("{\"kind\":\"SFX\",\"text\":\"axis 1\",\"limit\":0}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("limit"));
		search("{\"kind\":\"SFX\",\"text\":\"axis 1\",\"limit\":11}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("limit"));
		search("{\"kind\":\"SFX\",\"text\":\"broken\"}").andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("EMBEDDING_UNAVAILABLE"));
		mvc.perform(post("/internal/sounds/search").contentType(MediaType.APPLICATION_JSON)
			.content("{\"kind\":\"SFX\",\"text\":\"axis 1\"}")).andExpect(status().isUnauthorized());
	}

}
