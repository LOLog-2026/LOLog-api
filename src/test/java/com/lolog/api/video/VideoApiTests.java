package com.lolog.api.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import com.lolog.api.notification.PushSender;
import java.util.List;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockMultipartFile;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 카드 영상(#67, video.md)을 실제 PostgreSQL·Redis·SeaweedFS로 확인한다. Docker가 켜져 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class VideoApiTests {

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

	private static final AtomicLong USERS = new AtomicLong(51000);
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

	private long card(long userId) {
		long cardId = jdbc.queryForObject("""
				insert into cards (user_id, kind, summary, start_ms, end_ms, recorded_at)
				values (?, '웃김', '치킨 주문', 0, 1000, now()) returning id
				""", Long.class, userId);
		jdbc.update("insert into card_speakers (card_id, speaker_key, name) values (?, '화자1', '민수')", cardId);
		jdbc.update("insert into card_lines (card_id, line_no, speaker_key, text) values (?, 1, '화자1', '양념 반 후라이드 반')", cardId);
		return cardId;
	}

	private ResultActions call(MockHttpServletRequestBuilder request, long userId) throws Exception {
		return mvc.perform(request.header("Authorization", TestTokens.bearer(userId)));
	}

	private ResultActions internal(org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder<?> request)
			throws Exception {
		return mvc.perform(request.header("X-Internal-Token", INTERNAL));
	}

	private JsonNode json(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private long request(long userId, long cardId) throws Exception {
		return json(call(post("/api/cards/" + cardId + "/videos"), userId).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("QUEUED"))).get("videoId").asLong();
	}

	private List<JsonNode> messages(String stream, long videoId) {
		return redisTemplate.opsForStream().range(stream, Range.unbounded()).stream()
			.map(r -> objectMapper.readTree((String) r.getValue().get("payload")))
			.filter(n -> n.has("videoId") && n.get("videoId").asLong() == videoId)
			.toList();
	}

	private static byte[] wav() {
		byte[] b = new byte[64];
		System.arraycopy("RIFF".getBytes(), 0, b, 0, 4);
		System.arraycopy("WAVEfmt ".getBytes(), 0, b, 8, 8);
		return b;
	}

	private static byte[] mp4() {
		byte[] b = new byte[64];
		System.arraycopy("ftypisom".getBytes(), 0, b, 4, 8);
		return b;
	}

	private static byte[] jpeg() {
		return new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00, 0x01 };
	}

	private String board(long videoId, long cardId, String ref) {
		return """
				{"version":1,"videoId":%d,"cardId":%d,"durationMs":28400,
				 "characters":[{"key":"화자1","name":"민수","ref":%s,"voice":"young-m-01","imageUrl":"http://old"}],
				 "scenes":[{"index":1,"startMs":0,"endMs":5200,"lines":[{"speaker":"화자1","text":"양념 반 후라이드 반","startMs":300,"endMs":2400}]}]}
				""".formatted(videoId, cardId, ref == null ? "null" : "\"" + ref + "\"");
	}

	private ResultActions audio(long videoId, byte[] bytes) throws Exception {
		return internal(multipart("/internal/videos/" + videoId + "/audio")
			.file(new MockMultipartFile("file", "audio.wav", "audio/wav", bytes)));
	}

	private ResultActions video(long videoId, byte[] mp4, byte[] thumb, String durationMs) throws Exception {
		var request = multipart("/internal/videos/" + videoId + "/video")
			.file(new MockMultipartFile("file", "video.mp4", "video/mp4", mp4))
			.file(new MockMultipartFile("thumbnail", "thumb.jpg", "image/jpeg", thumb));
		if (durationMs != null) {
			request.param("durationMs", durationMs);
		}
		return internal(request);
	}

	private long jobOf(long videoId, String type) {
		return jdbc.queryForObject("select id from jobs where video_id = ? and job_type = ?", Long.class, videoId, type);
	}

	private String phone(long userId) throws Exception {
		String token = "fcm-" + java.util.UUID.randomUUID();
		call(put("/api/devices/fcm-token").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"),
				userId).andExpect(status().isNoContent());
		return token;
	}

	@Test
	void 영상을_요청하면_3호_2호를_거쳐_완성되고_푸시가_간다() throws Exception {
		long me = USERS.incrementAndGet();
		String token = phone(me);
		jdbc.update("""
				insert into default_characters (id, age_group, gender, glasses, label, appearance, image_key, thumb_key, voice_key, sort_order)
				values ('young-m-91', 'YOUNG', 'M', false, '설명', 'a young man', 'defaults/characters/young-m-91.png',
				  'defaults/characters/young-m-91_thumb.webp', 'defaults/characters/young-m-91_voice.wav', 91)
				on conflict (id) do nothing
				""");
		long cardId = card(me);
		jdbc.update("update card_speakers set character_type = 'DEFAULT', character_id = 'young-m-91' where card_id = ?", cardId);
		long videoId = request(me, cardId);
		call(post("/api/cards/" + cardId + "/videos"), me).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VIDEO_IN_PROGRESS"));
		assertThat(messages("lolog:video", videoId)).singleElement()
			.satisfies(m -> assertThat(m.get("jobId").asLong()).isEqualTo(jobOf(videoId, "VIDEO_VOICE")));
		call(get("/api/cards/" + cardId), me).andExpect(jsonPath("$.videos[0].id").value(videoId))
			.andExpect(jsonPath("$.videos[0].status").value("QUEUED"));
		// 목소리 단계 동안 카드는 고칠 수 없다
		call(delete("/api/cards/" + cardId), me).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CARD_LOCKED"));

		// 3호: source → storyboard → audio
		internal(get("/internal/videos/" + videoId + "/source")).andExpect(status().isOk())
			.andExpect(jsonPath("$.videoId").value(videoId))
			.andExpect(jsonPath("$.cardId").value(cardId))
			.andExpect(jsonPath("$.speakers[0].character.ref").value("default:young-m-91"))
			.andExpect(jsonPath("$.speakers[0].character.voice").value("young-m-91"))
			.andExpect(jsonPath("$.lines[0].text").value("양념 반 후라이드 반"));
		call(get("/api/videos/" + videoId), me).andExpect(jsonPath("$.status").value("VOICE"))
			.andExpect(jsonPath("$.videoUrl").isEmpty());
		audio(videoId, wav()).andExpect(status().isBadRequest());
		internal(post("/internal/videos/" + videoId + "/storyboard").contentType(MediaType.APPLICATION_JSON)
			.content(board(videoId, cardId + 1, "default:young-m-91"))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("cardId"));
		internal(post("/internal/videos/" + videoId + "/storyboard").contentType(MediaType.APPLICATION_JSON)
			.content(board(videoId, cardId, "default:young-m-91"))).andExpect(status().isNoContent());
		audio(videoId, "nope".getBytes()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("file"));
		audio(videoId, wav()).andExpect(status().isNoContent());
		audio(videoId, wav()).andExpect(status().isNoContent());
		call(get("/api/videos/" + videoId), me).andExpect(jsonPath("$.status").value("DRAWING"));
		assertThat(messages("lolog:comic", videoId)).singleElement().satisfies(m -> {
			assertThat(m.get("kind").asString()).isEqualTo("video");
			assertThat(m.get("cardId").asLong()).isEqualTo(cardId);
			assertThat(m.get("jobId").asLong()).isEqualTo(jobOf(videoId, "VIDEO_DRAW"));
		});
		assertThat(jdbc.queryForObject("select status from jobs where id = ?", String.class, jobOf(videoId, "VIDEO_VOICE")))
			.isEqualTo("DONE");
		// 그림 단계에서는 카드를 지울 수 있다(영상은 남음)

		// 2호: storyboard(새 imageUrl) · audio → video
		JsonNode got = json(internal(get("/internal/videos/" + videoId + "/storyboard")).andExpect(status().isOk()));
		assertThat(got.get("characters").get(0).get("imageUrl").asString()).contains("young-m-91.png")
			.contains("X-Amz-Expires=600");
		assertThat(got.get("characters").get(0).get("voice").asString()).isEqualTo("young-m-01");
		internal(get("/internal/videos/" + videoId + "/audio")).andExpect(status().isOk())
			.andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().bytes(wav()));
		video(videoId, "x".repeat(20).getBytes(), jpeg(), "28400").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("file"));
		video(videoId, mp4(), "x".getBytes(), "28400").andExpect(jsonPath("$.field").value("thumbnail"));
		video(videoId, mp4(), jpeg(), null).andExpect(jsonPath("$.field").value("durationMs"));
		video(videoId, mp4(), jpeg(), "28400").andExpect(status().isNoContent());

		call(get("/api/videos/" + videoId), me).andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.videoUrl").value(org.hamcrest.Matchers.containsString("video.mp4")))
			.andExpect(jsonPath("$.thumbnailUrl").value(org.hamcrest.Matchers.containsString("thumb.jpg")))
			.andExpect(jsonPath("$.durationMs").value(28400));
		assertThat(jdbc.queryForObject("select audio_key from videos where id = ?", String.class, videoId)).isNull();
		verify(sender, timeout(5000)).send(List.of(token),
				Map.of("type", "VIDEO_DONE", "videoId", String.valueOf(videoId), "cardId", String.valueOf(cardId)));
		// 끝난 영상: 받는 쪽은 404, 보내는 쪽은 204로 무시
		internal(get("/internal/videos/" + videoId + "/source")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("VIDEO_NOT_FOUND"));
		internal(get("/internal/videos/" + videoId + "/storyboard")).andExpect(status().isNotFound());
		video(videoId, mp4(), jpeg(), "1").andExpect(status().isNoContent());

		// 목록(만화 목록과 같은 모양: cardSummary, sort), 다른 사람은 못 봄
		call(get("/api/videos"), me).andExpect(jsonPath("$.videos[0].id").value(videoId))
			.andExpect(jsonPath("$.videos[0].cardSummary").value("치킨 주문"))
			.andExpect(jsonPath("$.nextCursor").isEmpty());
		long second = request(me, cardId);
		call(get("/api/videos").param("sort", "oldest").param("size", "1"), me)
			.andExpect(jsonPath("$.videos[0].id").value(videoId))
			.andExpect(jsonPath("$.nextCursor").value(String.valueOf(videoId)));
		call(get("/api/videos").param("sort", "oldest").param("cursor", String.valueOf(videoId)), me)
			.andExpect(jsonPath("$.videos[0].id").value(second));
		call(get("/api/videos").param("size", "1"), me).andExpect(jsonPath("$.videos[0].id").value(second));
		call(get("/api/videos").param("sort", "x"), me).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("sort"));
		call(get("/api/videos/" + videoId), me + 1000).andExpect(status().isNotFound());
	}

	@Test
	void 카드_하나에_실패가_아닌_영상_2개까지이고_실패하면_횟수가_돌아온다() throws Exception {
		long me = USERS.incrementAndGet();
		String token = phone(me);
		long cardId = card(me);
		long first = request(me, cardId);
		internal(post("/internal/jobs/" + jobOf(first, "VIDEO_VOICE") + "/fail").contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"TTS 실패\"}")).andExpect(status().isNoContent());
		internal(post("/internal/jobs/" + jobOf(first, "VIDEO_VOICE") + "/fail").contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"TTS 실패\"}")).andExpect(status().isNoContent());
		call(get("/api/videos/" + first), me).andExpect(jsonPath("$.status").value("FAILED"));
		verify(sender, timeout(5000)).send(List.of(token),
				Map.of("type", "VIDEO_FAILED", "videoId", String.valueOf(first), "cardId", String.valueOf(cardId)));
		assertThat(jdbc.queryForObject("select video_count from cards where id = ?", Integer.class, cardId)).isZero();
		call(get("/api/cards/" + cardId), me).andExpect(jsonPath("$.videoRemaining").value(2));

		long second = request(me, cardId);
		call(get("/api/cards/" + cardId), me).andExpect(jsonPath("$.videoRemaining").value(1));
		jdbc.update("update videos set status = 'DONE' where id = ?", second);
		long third = request(me, cardId);
		jdbc.update("update videos set status = 'DONE' where id = ?", third);
		call(post("/api/cards/" + cardId + "/videos"), me).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VIDEO_LIMIT"));
		// 지워도 횟수는 돌아오지 않는다
		call(delete("/api/videos/" + third), me).andExpect(status().isNoContent());
		call(delete("/api/videos/" + third), me).andExpect(status().isNotFound());
		// 카드 자세히의 남은 횟수도 지운 쇼츠를 센다. videos[]에는 지운 쇼츠가 없어서(실패 1 + 완성 1) 앱이 세면 1이 남아 보인다
		call(get("/api/cards/" + cardId), me).andExpect(jsonPath("$.videoRemaining").value(0))
			.andExpect(jsonPath("$.videos.length()").value(2));
		call(post("/api/cards/" + cardId + "/videos"), me).andExpect(status().isConflict());
		call(post("/api/cards/999999999/videos"), me).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CARD_NOT_FOUND"));
	}

	// 내 목소리(#83): "self"는 다 만들었을 때만, 친구가 만드는 쇼츠면 친구 쇼츠 동의를 했을 때만 보낸다. 아니면 null(워커가 기본 화자 목소리)
	@Test
	void 내_목소리는_READY이고_친구면_동의했을_때만_self로_준다() throws Exception {
		long me = USERS.incrementAndGet();
		long friend = USERS.incrementAndGet();
		for (long id : new long[] { me, friend }) {
			call(patch("/api/me/profile").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"목소리\"}"), id)
				.andExpect(status().isOk());
			jdbc.update("""
					update user_profiles set character_status = 'READY', character_key = 'users/%d/character.png',
					  character_thumb_key = 'users/%d/character_thumb.png', character_appearance = 'me', character_voice = 'self',
					  voice_clone_status = 'READY', voice_clone_key = 'users/%d/voice/sample.wav'
					where user_id = ?
					""".formatted(id, id, id), id);
		}
		jdbc.update("insert into friendships (requester_id, addressee_id, status) values (?, ?, 'ACCEPTED')", me, friend);

		// 내 카드에 내 캐릭터
		long mine = card(me);
		jdbc.update("update card_speakers set character_type = 'PROFILE', character_id = ? where card_id = ?",
				String.valueOf(me), mine);
		long myVideo = request(me, mine);
		internal(get("/internal/videos/" + myVideo + "/source"))
			.andExpect(jsonPath("$.speakers[0].character.voice").value("self"));

		// 내 카드에 친구 캐릭터: 친구가 동의하지 않았으면 null
		long withFriend = card(me);
		jdbc.update("update card_speakers set character_type = 'PROFILE', character_id = ? where card_id = ?",
				String.valueOf(friend), withFriend);
		long friendVideo = request(me, withFriend);
		internal(get("/internal/videos/" + friendVideo + "/source"))
			.andExpect(jsonPath("$.speakers[0].character.ref").value("profile:" + friend))
			.andExpect(jsonPath("$.speakers[0].character.voice").isEmpty());
		jdbc.update("update user_profiles set voice_clone_share_friends = true where user_id = ?", friend);
		internal(get("/internal/videos/" + friendVideo + "/source"))
			.andExpect(jsonPath("$.speakers[0].character.voice").value("self"));

		// 다시 만드는 중이면 null
		jdbc.update("update user_profiles set voice_clone_status = 'PROCESSING' where user_id = ?", me);
		internal(get("/internal/videos/" + myVideo + "/source"))
			.andExpect(jsonPath("$.speakers[0].character.voice").isEmpty());
	}

	@Test
	void 만드는_중에_지우면_워커의_다음_요청은_404이고_프로필_캐릭터는_고른_목소리를_준다() throws Exception {
		long me = USERS.incrementAndGet();
		call(patch("/api/me/profile").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"나\"}"), me)
			.andExpect(status().isOk());
		jdbc.update("""
				update user_profiles set character_status = 'READY', character_key = 'users/%d/character.png',
				  character_thumb_key = 'users/%d/character_thumb.webp', character_appearance = 'me', character_voice = 'kid-f-04'
				where user_id = ?
				""".formatted(me, me), me);
		long cardId = card(me);
		jdbc.update("update card_speakers set character_type = 'PROFILE', character_id = ? where card_id = ?",
				String.valueOf(me), cardId);
		long videoId = request(me, cardId);
		internal(get("/internal/videos/" + videoId + "/source")).andExpect(jsonPath("$.speakers[0].character.ref")
			.value("profile:" + me)).andExpect(jsonPath("$.speakers[0].character.voice").value("kid-f-04"));
		internal(post("/internal/videos/" + videoId + "/storyboard").contentType(MediaType.APPLICATION_JSON)
			.content(board(videoId, cardId, "profile:" + me))).andExpect(status().isNoContent());
		audio(videoId, wav()).andExpect(status().isNoContent());

		call(delete("/api/videos/" + videoId), me).andExpect(status().isNoContent());
		internal(get("/internal/videos/" + videoId + "/storyboard")).andExpect(status().isNotFound());
		internal(get("/internal/videos/" + videoId + "/audio")).andExpect(status().isNotFound());
		video(videoId, mp4(), jpeg(), "1").andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select count(*) from jobs where video_id = ?", Long.class, videoId)).isZero();

		// 탈퇴하면 영상도 지운다
		long other = request(me, card(me));
		mvc.perform(delete("/internal/users/" + me).header("X-Internal-Token", INTERNAL)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from videos where id = ?", Long.class, other)).isZero();
	}

}
