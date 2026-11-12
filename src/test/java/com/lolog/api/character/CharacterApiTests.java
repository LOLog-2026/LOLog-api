package com.lolog.api.character;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import com.lolog.api.notification.PushSender;
import com.lolog.api.storage.ImageStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 기본 화자·프로필 캐릭터·화자에 그림 붙이기(#44, characters.md)를 실제 PostgreSQL·Redis·SeaweedFS로 확인한다.
// Docker가 켜져 있어야 한다. FCM에는 보내지 않고 PushSender를 가짜로 바꿔 무엇을 보냈는지 본다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CharacterApiTests {

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

	private static final AtomicLong USERS = new AtomicLong(9000);
	private static final String INTERNAL = "internal-test-token";
	private static final long WAIT_MS = 3000;

	@MockitoBean
	PushSender sender;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ImageStorage storage;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Autowired
	ObjectMapper objectMapper;

	@BeforeEach
	void setUp() throws Exception {
		when(sender.enabled()).thenReturn(true);
		putDefault("young-f-02", 2, true).andExpect(status().isNoContent());
		putDefault("kid-m-01", 1, true).andExpect(status().isNoContent());
	}

	// ---- 준비 ----

	private static byte[] png(int size) throws Exception {
		BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
		image.setRGB(size / 2, size / 2, 0xFF0000);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(image, "png", out);
		return out.toByteArray();
	}

	private ResultActions putDefault(String id, int sortOrder, boolean withImages) throws Exception {
		var request = multipart(HttpMethod.PUT, "/internal/characters/defaults/" + id).header("X-Internal-Token", INTERNAL)
			.param("ageGroup", id.startsWith("kid") ? "CHILD" : "YOUNG")
			.param("gender", id.contains("-f-") ? "F" : "M")
			.param("glasses", "true")
			.param("label", "설명 " + id)
			.param("appearance", "a person " + id)
			.param("sortOrder", String.valueOf(sortOrder));
		if (withImages) {
			request.file(new MockMultipartFile("image", id + ".png", "image/png", png(8)))
				.file(new MockMultipartFile("thumbnail", id + "_thumb.webp", "image/webp", "webp".getBytes()));
		}
		return mvc.perform(request);
	}

	// 닉네임을 정한 회원(푸시 받을 폰 토큰도 하나)
	private record User(long id, String code, String token) {
	}

	private User user(String nickname) throws Exception {
		long id = USERS.incrementAndGet();
		String token = "fcm-" + UUID.randomUUID();
		jdbc.update("insert into device_tokens (user_id, token) values (?, ?)", id, token);
		JsonNode profile = json(mvc.perform(patch("/api/me/profile").header("Authorization", TestTokens.bearer(id))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"nickname\":\"" + nickname + "\"}")).andExpect(status().isOk()));
		return new User(id, profile.get("friendCode").asString(), token);
	}

	private void makeFriends(User a, User b) throws Exception {
		long id = json(mvc.perform(post("/api/friend-requests").header("Authorization", TestTokens.bearer(a.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"friendCode\":\"" + b.code() + "\"}")).andExpect(status().isCreated())).get("id").asLong();
		mvc.perform(post("/api/friend-requests/" + id + "/accept").header("Authorization", TestTokens.bearer(b.id())))
			.andExpect(status().isOk());
	}

	private ResultActions uploadPhoto(long userId, byte[] photo) throws Exception {
		return mvc.perform(multipart(HttpMethod.PUT, "/api/me/profile/character")
			.file(new MockMultipartFile("photo", "me.png", "image/png", photo))
			.header("Authorization", TestTokens.bearer(userId)));
	}

	private ResultActions sendAvatar(long userId, byte[] file, String appearance) throws Exception {
		var request = multipart("/internal/users/" + userId + "/character/avatar")
			.file(new MockMultipartFile("file", "avatar.png", "image/png", file))
			.header("X-Internal-Token", INTERNAL);
		if (appearance != null) {
			request.param("appearance", appearance);
		}
		return mvc.perform(request);
	}

	// 사진을 올리고 워커처럼 그림을 보내 READY로 만든다.
	private void makeCharacter(long userId) throws Exception {
		uploadPhoto(userId, png(32)).andExpect(status().isAccepted());
		sendAvatar(userId, png(768), "a person " + userId).andExpect(status().isNoContent());
	}

	private long avatarJobId(long userId) {
		return redisTemplate.opsForStream().range("lolog:comic", Range.unbounded()).stream()
			.map(r -> objectMapper.readTree((String) r.getValue().get("payload")))
			.filter(n -> n.has("kind") && n.get("userId").asLong() == userId)
			.reduce((first, second) -> second).orElseThrow().get("jobId").asLong();
	}

	private long insertCard(long userId) {
		long cardId = jdbc.queryForObject("""
				insert into cards (user_id, kind, summary, start_ms, end_ms, recorded_at)
				values (?, '웃김', '요약', 0, 1000, now()) returning id
				""", Long.class, userId);
		for (String key : List.of("화자1", "화자2")) {
			jdbc.update("insert into card_speakers (card_id, speaker_key) values (?, ?)", cardId, key);
		}
		jdbc.update("insert into card_lines (card_id, line_no, speaker_key, text) values (?, 1, '화자1', '대사')", cardId);
		jdbc.update("insert into card_lines (card_id, line_no, speaker_key, text) values (?, 2, '화자2', '대사')", cardId);
		return cardId;
	}

	private ResultActions patchSpeakers(long userId, long cardId, String speakersJson) throws Exception {
		return mvc.perform(patch("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(userId))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"speakers\":" + speakersJson + "}"));
	}

	private String speakerCharacter(long cardId, String key) {
		return jdbc.queryForObject("select coalesce(character_type || ':' || character_id, '') from card_speakers "
				+ "where card_id = ? and speaker_key = ?", String.class, cardId, key);
	}

	private JsonNode json(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	// ---- 기본 화자 ----

	@Test
	void 기본_화자를_넣으면_목록에_보이고_외모_글은_보내지_않는다() throws Exception {
		JsonNode list = json(mvc.perform(get("/api/characters/defaults").header("Authorization", TestTokens.bearer(1)))
			.andExpect(status().isOk()));
		JsonNode first = list.get("characters").get(0);
		assertThat(first.get("id").asString()).isEqualTo("kid-m-01");
		assertThat(first.get("ageGroup").asString()).isEqualTo("CHILD");
		assertThat(first.get("glasses").asBoolean()).isTrue();
		assertThat(first.get("thumbnailUrl").asString()).contains("defaults/characters/kid-m-01_thumb.webp");
		assertThat(first.has("appearance")).isFalse();
		assertThat(storage.get(ImageStorage.defaultCharacterKey("young-f-02"))).isEqualTo(png(8));

		// 그림 없이 다시 넣으면 목록만 바뀌고 그림은 그대로
		putDefault("young-f-02", 0, false).andExpect(status().isNoContent());
		mvc.perform(get("/api/characters/defaults").header("Authorization", TestTokens.bearer(1)))
			.andExpect(jsonPath("$.characters[0].id").value("young-f-02"));
		assertThat(storage.get(ImageStorage.defaultCharacterKey("young-f-02"))).isEqualTo(png(8));
	}

	@Test
	void 기본_화자_넣기는_내부_토큰이_필요하고_값을_검사한다() throws Exception {
		mvc.perform(multipart(HttpMethod.PUT, "/internal/characters/defaults/x").param("label", "x"))
			.andExpect(status().isUnauthorized());
		mvc.perform(multipart(HttpMethod.PUT, "/internal/characters/defaults/young-f-09")
			.header("X-Internal-Token", INTERNAL)
			.param("ageGroup", "OLD").param("gender", "F").param("glasses", "false").param("label", "x")
			.param("appearance", "x").param("sortOrder", "1"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("ageGroup"));
	}

	// ---- 프로필 캐릭터 ----

	@Test
	void 사진을_올리면_만드는_중이고_그림을_받으면_READY가_되고_사진을_지운다() throws Exception {
		User me = user("민수");
		uploadPhoto(me.id(), png(32)).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PROCESSING"))
			.andExpect(jsonPath("$.thumbnailUrl").isEmpty())
			.andExpect(jsonPath("$.updatedAt").isNotEmpty());
		long jobId = avatarJobId(me.id());
		assertThat(jdbc.queryForObject("select job_type from jobs where id = ?", String.class, jobId)).isEqualTo("AVATAR");

		uploadPhoto(me.id(), png(32)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CHARACTER_IN_PROGRESS"));
		mvc.perform(get("/internal/users/" + me.id() + "/character/photo").header("X-Internal-Token", INTERNAL))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/png"))
			.andExpect(content().bytes(png(32)));

		sendAvatar(me.id(), png(100), "x").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file"));
		sendAvatar(me.id(), png(768), " ").andExpect(jsonPath("$.field").value("appearance"));
		sendAvatar(me.id(), png(768), "a young man with glasses").andExpect(status().isNoContent());

		verify(sender, timeout(WAIT_MS)).send(eq(List.of(me.token())), eq(Map.of("type", "PROFILE_CHARACTER_READY")));
		mvc.perform(get("/api/me/profile").header("Authorization", TestTokens.bearer(me.id())))
			.andExpect(jsonPath("$.character.status").value("READY"))
			.andExpect(jsonPath("$.character.thumbnailUrl").isNotEmpty());
		BufferedImage thumb = ImageIO.read(new ByteArrayInputStream(storage.get(ImageStorage.profileCharacterThumbKey(me.id()))));
		assertThat(thumb.getWidth()).isEqualTo(256);
		assertThat(jdbc.queryForObject("select status from jobs where id = ?", String.class, jobId)).isEqualTo("DONE");
		// 원본 사진은 지워져 워커가 다시 받으면 404, 그림을 또 보내도 404
		mvc.perform(get("/internal/users/" + me.id() + "/character/photo").header("X-Internal-Token", INTERNAL))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHARACTER_NOT_FOUND"));
		assertThatThrownBy(() -> storage.get(ImageStorage.profilePhotoKey(me.id()))).isInstanceOf(NoSuchKeyException.class);
		sendAvatar(me.id(), png(768), "again").andExpect(status().isNotFound());
	}

	@Test
	void 다시_만들다_실패하면_FAILED이고_예전_그림은_그대로_쓰며_실패는_횟수에_세지_않는다() throws Exception {
		User me = user("민수");
		makeCharacter(me.id());
		uploadPhoto(me.id(), png(32)).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("PROCESSING"))
			.andExpect(jsonPath("$.thumbnailUrl").isNotEmpty());

		long jobId = avatarJobId(me.id());
		mvc.perform(post("/internal/jobs/" + jobId + "/fail").header("X-Internal-Token", INTERNAL)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"그리기 실패\"}")).andExpect(status().isNoContent());

		verify(sender, timeout(WAIT_MS)).send(eq(List.of(me.token())), eq(Map.of("type", "PROFILE_CHARACTER_FAILED")));
		mvc.perform(get("/api/me/profile").header("Authorization", TestTokens.bearer(me.id())))
			.andExpect(jsonPath("$.character.status").value("FAILED"))
			.andExpect(jsonPath("$.character.thumbnailUrl").isNotEmpty());
		assertThatThrownBy(() -> storage.get(ImageStorage.profilePhotoKey(me.id()))).isInstanceOf(NoSuchKeyException.class);

		// 성공 1번 + 실패 1번 → 2번 더 만들 수 있다
		makeCharacter(me.id());
		makeCharacter(me.id());
		uploadPhoto(me.id(), png(32)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHARACTER_LIMIT"));
	}

	@Test
	void 사진_검사() throws Exception {
		long me = USERS.incrementAndGet();
		uploadPhoto(me, "gif?".getBytes()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("photo"));
		byte[] big = new byte[(int) ProfileCharacterService.MAX_PHOTO_BYTES + 1];
		big[0] = (byte) 0xFF;
		big[1] = (byte) 0xD8;
		big[2] = (byte) 0xFF;
		uploadPhoto(me, big).andExpect(status().isPayloadTooLarge());
		mvc.perform(multipart(HttpMethod.PUT, "/api/me/profile/character").header("Authorization", TestTokens.bearer(me)))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("photo"));
		// 닉네임이 없어도 만들 수 있다
		uploadPhoto(me, png(16)).andExpect(status().isAccepted());
	}

	// ---- 화자에 붙이기 ----

	@Test
	void 화자에_기본_화자와_친구_캐릭터를_붙이고_만화_원본에_그림이_간다() throws Exception {
		User me = user("나");
		User friend = user("지원");
		makeFriends(me, friend);
		makeCharacter(friend.id());
		long cardId = insertCard(me.id());

		patchSpeakers(me.id(), cardId, """
				[{"key":"화자1","name":"지원","character":{"type":"PROFILE","id":"%d"}},
				 {"key":"화자2","character":{"type":"DEFAULT","id":"young-f-02"}}]
				""".formatted(friend.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.speakers[0].character.type").value("PROFILE"))
			.andExpect(jsonPath("$.speakers[0].character.id").value(String.valueOf(friend.id())))
			.andExpect(jsonPath("$.speakers[0].character.label").value("지원"))
			.andExpect(jsonPath("$.speakers[0].character.thumbnailUrl").isNotEmpty())
			.andExpect(jsonPath("$.speakers[1].name").isEmpty())
			.andExpect(jsonPath("$.speakers[1].character.label").value("설명 young-f-02"));

		// 이름만 바꾸면 그림은 그대로, null이면 뗀다
		patchSpeakers(me.id(), cardId, "[{\"key\":\"화자2\",\"name\":\"수아\"}]")
			.andExpect(jsonPath("$.speakers[1].character.id").value("young-f-02"));

		long comicId = json(mvc.perform(post("/api/cards/" + cardId + "/comics").header("Authorization",
				TestTokens.bearer(me.id()))).andExpect(status().isAccepted())).get("comicId").asLong();
		JsonNode source = json(mvc.perform(get("/internal/comics/" + comicId + "/source").header("X-Internal-Token", INTERNAL))
			.andExpect(status().isOk()));
		JsonNode speaker1 = source.get("speakers").get(0).get("character");
		assertThat(speaker1.get("ref").asString()).isEqualTo("profile:" + friend.id());
		assertThat(speaker1.get("appearance").asString()).isEqualTo("a person " + friend.id());
		assertThat(speaker1.get("imageUrl").asString()).contains("character/avatar.png");
		assertThat(source.get("speakers").get(1).get("character").get("ref").asString()).isEqualTo("default:young-f-02");

		// 친구 목록에도 그림이 보인다
		mvc.perform(get("/api/friends").header("Authorization", TestTokens.bearer(me.id())))
			.andExpect(jsonPath("$.friends[0].character.thumbnailUrl").isNotEmpty());
	}

	@Test
	void 붙이기_오류() throws Exception {
		User me = user("나");
		User stranger = user("남");
		User friend = user("친구");
		makeFriends(me, friend);
		makeCharacter(stranger.id());
		long cardId = insertCard(me.id());

		patchSpeakers(me.id(), cardId, "[{\"key\":\"화자1\",\"character\":{\"type\":\"DEFAULT\",\"id\":\"nope\"}}]")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("UNKNOWN_CHARACTER"))
			.andExpect(jsonPath("$.field").value("speakers[0].character"));
		patchSpeakers(me.id(), cardId, "[{\"key\":\"화자1\",\"character\":{\"type\":\"PROFILE\",\"id\":\"%d\"}}]"
			.formatted(stranger.id())).andExpect(jsonPath("$.code").value("UNKNOWN_CHARACTER"));
		patchSpeakers(me.id(), cardId, "[{\"key\":\"화자1\",\"character\":{\"type\":\"PROFILE\",\"id\":\"%d\"}}]"
			.formatted(friend.id())).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CHARACTER_NOT_READY"));
		patchSpeakers(me.id(), cardId, "[{\"key\":\"화자1\",\"character\":{\"type\":\"PROFILE\",\"id\":\"%d\"}}]"
			.formatted(me.id())).andExpect(jsonPath("$.code").value("CHARACTER_NOT_READY"));
		patchSpeakers(me.id(), cardId, "[{\"key\":\"화자1\",\"character\":{\"type\":\"OTHER\",\"id\":\"x\"}}]")
			.andExpect(jsonPath("$.field").value("speakers[0].character.type"));

		patchSpeakers(me.id(), cardId, "[{\"key\":\"화자1\",\"character\":{\"type\":\"DEFAULT\",\"id\":\"kid-m-01\"}}]")
			.andExpect(status().isOk());
		patchSpeakers(me.id(), cardId, "[{\"key\":\"화자2\",\"character\":{\"type\":\"DEFAULT\",\"id\":\"kid-m-01\"}}]")
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("DUPLICATE_CHARACTER"))
			.andExpect(jsonPath("$.field").value("speakers[0].character"));
		// 한 요청에서 서로 바꾸는 것은 된다
		patchSpeakers(me.id(), cardId, """
				[{"key":"화자1","character":{"type":"DEFAULT","id":"young-f-02"}},
				 {"key":"화자2","character":{"type":"DEFAULT","id":"kid-m-01"}}]
				""").andExpect(status().isOk());
		patchSpeakers(me.id(), cardId, "[{\"key\":\"화자1\",\"character\":null}]")
			.andExpect(jsonPath("$.speakers[0].character").isEmpty());
	}

	@Test
	void 캐릭터를_지우거나_친구를_끊거나_탈퇴하면_카드_화자에서_빠진다() throws Exception {
		User me = user("나");
		User a = user("가");
		User b = user("나나");
		User c = user("다");
		makeFriends(me, a);
		makeFriends(me, b);
		makeFriends(me, c);
		makeCharacter(me.id());
		makeCharacter(a.id());
		makeCharacter(b.id());
		makeCharacter(c.id());
		long myCard = insertCard(me.id());
		long aCard = insertCard(a.id());
		patchSpeakers(me.id(), myCard, "[{\"key\":\"화자1\",\"character\":{\"type\":\"PROFILE\",\"id\":\"%d\"}},"
				.formatted(a.id()) + "{\"key\":\"화자2\",\"character\":{\"type\":\"PROFILE\",\"id\":\"%d\"}}]"
				.formatted(me.id())).andExpect(status().isOk());
		patchSpeakers(a.id(), aCard, "[{\"key\":\"화자1\",\"character\":{\"type\":\"PROFILE\",\"id\":\"%d\"}}]"
				.formatted(me.id())).andExpect(status().isOk());

		// 끊으면 서로의 카드에서 빠지고, 내 카드의 내 캐릭터는 그대로
		mvc.perform(delete("/api/friends/" + a.id()).header("Authorization", TestTokens.bearer(me.id())))
			.andExpect(status().isNoContent());
		assertThat(speakerCharacter(myCard, "화자1")).isEmpty();
		assertThat(speakerCharacter(aCard, "화자1")).isEmpty();
		assertThat(speakerCharacter(myCard, "화자2")).isEqualTo("PROFILE:" + me.id());

		// 친구를 차단해도 친구 관계가 끊기며 빠진다
		long bCard = insertCard(b.id());
		patchSpeakers(b.id(), bCard, "[{\"key\":\"화자1\",\"character\":{\"type\":\"PROFILE\",\"id\":\"%d\"}}]"
				.formatted(me.id())).andExpect(status().isOk());
		mvc.perform(post("/api/blocks").header("Authorization", TestTokens.bearer(me.id()))
			.contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + b.id() + "}")).andExpect(status().isNoContent());
		assertThat(speakerCharacter(bCard, "화자1")).isEmpty();

		// 내 캐릭터를 지우면 친구 카드에서도 빠지고 파일도 지운다
		long cCard = insertCard(c.id());
		patchSpeakers(c.id(), cCard, "[{\"key\":\"화자1\",\"character\":{\"type\":\"PROFILE\",\"id\":\"%d\"}}]"
				.formatted(me.id())).andExpect(status().isOk());
		mvc.perform(delete("/api/me/profile/character").header("Authorization", TestTokens.bearer(me.id())))
			.andExpect(status().isNoContent());
		assertThat(speakerCharacter(cCard, "화자1")).isEmpty();
		assertThat(speakerCharacter(myCard, "화자2")).isEmpty();
		mvc.perform(get("/api/me/profile").header("Authorization", TestTokens.bearer(me.id())))
			.andExpect(jsonPath("$.character").isEmpty());
		long until = System.currentTimeMillis() + WAIT_MS;
		boolean gone = false;
		while (!gone && System.currentTimeMillis() < until) {
			try {
				storage.get(ImageStorage.profileCharacterKey(me.id()));
				Thread.sleep(50);
			}
			catch (NoSuchKeyException e) {
				gone = true;
			}
		}
		assertThat(gone).isTrue();
		mvc.perform(delete("/api/me/profile/character").header("Authorization", TestTokens.bearer(me.id())))
			.andExpect(status().isNoContent());

		// 탈퇴하면 친구 카드에서 빠진다
		patchSpeakers(me.id(), myCard, "[{\"key\":\"화자1\",\"character\":{\"type\":\"PROFILE\",\"id\":\"%d\"}}]"
				.formatted(c.id())).andExpect(status().isOk());
		mvc.perform(delete("/internal/users/" + c.id()).header("X-Internal-Token", INTERNAL)).andExpect(status().isNoContent());
		assertThat(speakerCharacter(myCard, "화자1")).isEmpty();
	}

}
