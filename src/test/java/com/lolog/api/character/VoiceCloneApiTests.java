package com.lolog.api.character;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 내 목소리(#83, characters.md "내 목소리 (FR-CHAR-05)")를 실제 PostgreSQL·Redis·SeaweedFS로 확인한다.
// 큐 넣기를 켠 상태다(배포 기본은 꺼짐, 꺼진 상태는 VoiceCloneDisabledTests가 본다).
@SpringBootTest(properties = "lolog.voice-clone.queue-enabled=true")
@AutoConfigureMockMvc
@Testcontainers
class VoiceCloneApiTests {

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

	private static final AtomicLong USERS = new AtomicLong(83000);
	private static final String INTERNAL = "internal-test-token";
	private static final long WAIT_MS = 3000;
	private static final String CLONE = "/api/me/profile/character/voice-clone";

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
	void pushOn() {
		when(sender.enabled()).thenReturn(true);
	}

	// ---- 준비 ----

	static byte[] wav(int size) {
		byte[] b = new byte[size];
		System.arraycopy("RIFF".getBytes(), 0, b, 0, 4);
		System.arraycopy("WAVEfmt ".getBytes(), 0, b, 8, 8);
		b[size - 1] = 7;
		return b;
	}

	static byte[] m4a() {
		byte[] b = new byte[64];
		System.arraycopy("ftypM4A ".getBytes(), 0, b, 4, 8);
		return b;
	}

	private ResultActions call(MockHttpServletRequestBuilder request, long userId) throws Exception {
		return mvc.perform(request.header("Authorization", TestTokens.bearer(userId)));
	}

	private JsonNode json(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	// 닉네임과 폰 토큰이 있는 회원. character가 true면 쓸 수 있는 프로필 캐릭터 그림도 있다.
	private record User(long id, String token) {
	}

	private User user(boolean character) throws Exception {
		long id = USERS.incrementAndGet();
		String token = "fcm-" + UUID.randomUUID();
		jdbc.update("insert into device_tokens (user_id, token) values (?, ?)", id, token);
		call(patch("/api/me/profile").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"목소리" + id + "\"}"),
				id)
			.andExpect(status().isOk());
		if (character) {
			jdbc.update("""
					update user_profiles set character_status = 'READY', character_key = 'users/%d/character/avatar.png',
					  character_thumb_key = 'users/%d/character/avatar_thumb.png', character_appearance = 'me'
					where user_id = ?
					""".formatted(id, id), id);
		}
		return new User(id, token);
	}

	private ResultActions upload(long userId, byte[] audio, String consent) throws Exception {
		return upload(userId, audio, consent, null);
	}

	// text는 예전 앱처럼 문장을 보내 보는 경우에만 쓴다(서버는 받지 않고 정해진 문장을 저장한다).
	private ResultActions upload(long userId, byte[] audio, String consent, String text) throws Exception {
		MockMultipartHttpServletRequestBuilder request = multipart(HttpMethod.PUT, CLONE);
		if (audio != null) {
			request.file(new MockMultipartFile("audio", "me.wav", "audio/wav", audio));
		}
		if (consent != null) {
			request.param("consent", consent);
		}
		if (text != null) {
			request.param("text", text);
		}
		return mvc.perform(request.header("Authorization", TestTokens.bearer(userId)));
	}

	private ResultActions preview(long userId, byte[] wav) throws Exception {
		return mvc.perform(multipart(HttpMethod.PUT, "/internal/users/" + userId + "/voice-clone/preview")
			.file(new MockMultipartFile("file", "preview.wav", "audio/wav", wav))
			.header("X-Internal-Token", INTERNAL));
	}

	private ResultActions sample(long userId) throws Exception {
		return mvc.perform(get("/internal/users/" + userId + "/voice-clone/sample").header("X-Internal-Token", INTERNAL));
	}

	private ResultActions chooseVoice(long userId, String voice) throws Exception {
		return call(patch("/api/me/profile/character/voice").contentType(MediaType.APPLICATION_JSON)
			.content(voice == null ? "{\"voice\":null}" : "{\"voice\":\"" + voice + "\"}"), userId);
	}

	private JsonNode lastMessage(long userId) {
		return redisTemplate.opsForStream().range("lolog:video", Range.unbounded()).stream()
			.map(r -> objectMapper.readTree((String) r.getValue().get("payload")))
			.filter(n -> n.has("kind") && "voice_clone".equals(n.get("kind").asString()) && n.get("userId").asLong() == userId)
			.reduce((first, second) -> second)
			.orElseThrow();
	}

	private boolean stored(String key) {
		try {
			storage.get(key);
			return true;
		}
		catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
			return false;
		}
	}

	// 녹음 올리기 → 워커가 미리 듣기 보내기까지 해서 READY로 만든다.
	private void makeVoice(User me) throws Exception {
		upload(me.id(), wav(64), "true").andExpect(status().isAccepted());
		preview(me.id(), wav(32)).andExpect(status().isNoContent());
	}

	// ---- 테스트 ----

	@Test
	void 녹음을_올리면_만드는_중이고_큐에_voice로_들어가고_미리_듣기를_받으면_READY다() throws Exception {
		User me = user(true);
		upload(me.id(), wav(64), "true").andExpect(status().isAccepted())
			.andExpect(jsonPath("$.voiceClone.status").value("PROCESSING"))
			.andExpect(jsonPath("$.voiceClone.previewUrl").isEmpty())
			.andExpect(jsonPath("$.voiceClone.shareWithFriends").value(false));
		JsonNode message = lastMessage(me.id());
		long jobId = message.get("jobId").asLong();
		assertThat(jdbc.queryForObject("select job_type from jobs where id = ?", String.class, jobId)).isEqualTo("VOICE_CLONE");
		assertThat(message.size()).isEqualTo(4);
		assertThat(message.get("text").asString()).isEqualTo(VoiceCloneService.RECORD_SENTENCE);
		assertThat(jdbc.queryForObject("select voice_clone_text from user_profiles where user_id = ?", String.class,
				me.id())).isEqualTo("오늘 친구랑 카페에 갔는데, 주문을 잘못해서 둘이 한참 웃었어요. 그래도 덕분에 즐거운 하루였어요.");
		assertThat(jdbc.queryForObject("select voice_clone_updated_at is not null from user_profiles where user_id = ?",
				Boolean.class, me.id())).isTrue();
		assertThat(jdbc.queryForObject("select voice_clone_consented_at is not null from user_profiles where user_id = ?",
				Boolean.class, me.id())).isTrue();

		// 만드는 중에는 다시 올리거나 고를 수 없다.
		upload(me.id(), wav(64), "true").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VOICE_CLONE_IN_PROGRESS"));
		chooseVoice(me.id(), "self").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("VOICE_CLONE_NOT_READY"));

		// 워커가 원본을 받고 미리 듣기를 보낸다.
		sample(me.id()).andExpect(status().isOk())
			.andExpect(content().contentType("audio/wav"))
			.andExpect(content().bytes(wav(64)));
		preview(me.id(), new byte[] { 1, 2, 3 }).andExpect(status().isBadRequest());
		preview(me.id(), wav(32)).andExpect(status().isNoContent());
		verify(sender, timeout(WAIT_MS)).send(eq(List.of(me.token())), eq(Map.of("type", "VOICE_CLONE_READY")));
		assertThat(jdbc.queryForObject("select status from jobs where id = ?", String.class, jobId)).isEqualTo("DONE");
		// 이미 끝났으면 미리 듣기를 다시 받지 않는다.
		preview(me.id(), wav(32)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("VOICE_CLONE_NOT_FOUND"));

		call(get("/api/me/profile"), me.id()).andExpect(status().isOk())
			.andExpect(jsonPath("$.character.voiceClone.status").value("READY"))
			.andExpect(jsonPath("$.character.voiceClone.previewUrl").isNotEmpty())
			.andExpect(jsonPath("$.character.voiceClone.failReason").isEmpty());
		chooseVoice(me.id(), "self").andExpect(status().isNoContent());
		call(get("/api/me/profile"), me.id()).andExpect(jsonPath("$.character.voice").value("self"));
		// 만든 뒤에도 원본은 보관하고 워커가 받을 수 있다(쇼츠 참조 음성).
		sample(me.id()).andExpect(status().isOk());
		assertThat(stored(ImageStorage.voiceClonePreviewKey(me.id()))).isTrue();
	}

	@Test
	void 녹음_검사() throws Exception {
		User none = user(false);
		upload(none.id(), wav(64), "true").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CHARACTER_NOT_FOUND"));
		User me = user(true);
		upload(me.id(), wav(64), null).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("consent"));
		upload(me.id(), wav(64), "false").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("consent"));
		upload(me.id(), null, "true").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("audio"));
		upload(me.id(), new byte[] { 'I', 'D', '3', 0, 0, 0, 0, 0, 0, 0, 0, 0 }, "true").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("audio"));
		upload(me.id(), wav((int) VoiceCloneService.MAX_AUDIO_BYTES + 1), "true").andExpect(status().isPayloadTooLarge());
		sample(me.id()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("VOICE_CLONE_NOT_FOUND"));

		// m4a도 받고 Content-Type을 그대로 돌려준다.
		upload(me.id(), m4a(), "true").andExpect(status().isAccepted());
		sample(me.id()).andExpect(status().isOk()).andExpect(content().contentType("audio/mp4"));
		assertThat(jdbc.queryForObject("select voice_clone_key from user_profiles where user_id = ?", String.class, me.id()))
			.isEqualTo("users/" + me.id() + "/voice/sample.m4a");
	}

	@Test
	void 앱이_문장을_보내도_정해진_녹음_문장을_저장한다() throws Exception {
		User me = user(true);
		upload(me.id(), wav(64), "true", "다른 문장").andExpect(status().isAccepted());
		assertThat(jdbc.queryForObject("select voice_clone_text from user_profiles where user_id = ?", String.class,
				me.id())).isEqualTo(VoiceCloneService.RECORD_SENTENCE);
	}

	@Test
	void 대화_녹음_중에는_만들_수_없다() throws Exception {
		User me = user(true);
		long recordingId = jdbc.queryForObject("""
				insert into recordings (user_id, client_recording_id, started_at) values (?, ?, now() - interval '70 minutes')
				returning id
				""", Long.class, me.id(), UUID.randomUUID().toString());
		jdbc.update("insert into recording_parts (recording_id, part_no, is_last, duration_sec) values (?, 1, false, 3600)",
				recordingId);
		upload(me.id(), wav(64), "true").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RECORDING_IN_PROGRESS"));

		// 마지막 묶음이 오면 녹음이 끝난 것이다.
		jdbc.update("insert into recording_parts (recording_id, part_no, is_last, duration_sec) values (?, 2, true, 60)",
				recordingId);
		upload(me.id(), wav(64), "true").andExpect(status().isAccepted());

		// 최장 녹음 시간(3시간)이 지난 녹음은 끝난 것으로 본다.
		User other = user(true);
		jdbc.update("""
				insert into recordings (user_id, client_recording_id, started_at) values (?, ?, now() - interval '4 hours')
				""", other.id(), UUID.randomUUID().toString());
		upload(other.id(), wav(64), "true").andExpect(status().isAccepted());
	}

	@Test
	void 실패하면_FAILED와_이유가_남고_녹음을_지우고_알린다() throws Exception {
		User me = user(true);
		upload(me.id(), wav(64), "true").andExpect(status().isAccepted());
		long jobId = lastMessage(me.id()).get("jobId").asLong();
		mvc.perform(post("/internal/jobs/" + jobId + "/fail").header("X-Internal-Token", INTERNAL)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"VOICE_TOO_QUIET\"}")).andExpect(status().isNoContent());
		verify(sender, timeout(WAIT_MS)).send(eq(List.of(me.token())), eq(Map.of("type", "VOICE_CLONE_FAILED")));
		call(get("/api/me/profile"), me.id()).andExpect(jsonPath("$.character.voiceClone.status").value("FAILED"))
			.andExpect(jsonPath("$.character.voiceClone.failReason").value("VOICE_TOO_QUIET"));
		sample(me.id()).andExpect(status().isNotFound());
		assertThat(stored(ImageStorage.voiceCloneSampleKey(me.id(), "wav"))).isFalse();
		// 다시 만들 수 있다.
		upload(me.id(), wav(64), "true").andExpect(status().isAccepted())
			.andExpect(jsonPath("$.voiceClone.failReason").isEmpty());
	}

	@Test
	void 친구_쇼츠_동의만_바꾸고_지우면_고른_목소리도_빠진다() throws Exception {
		User me = user(true);
		call(patch(CLONE).contentType(MediaType.APPLICATION_JSON).content("{\"shareWithFriends\":true}"), me.id())
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("VOICE_CLONE_NOT_FOUND"));
		makeVoice(me);
		call(patch(CLONE).contentType(MediaType.APPLICATION_JSON).content("{}"), me.id()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("shareWithFriends"));
		call(patch(CLONE).contentType(MediaType.APPLICATION_JSON).content("{\"shareWithFriends\":true}"), me.id())
			.andExpect(status().isNoContent());
		call(get("/api/me/profile"), me.id()).andExpect(jsonPath("$.character.voiceClone.shareWithFriends").value(true))
			.andExpect(jsonPath("$.character.voiceClone.status").value("READY"));
		chooseVoice(me.id(), "self").andExpect(status().isNoContent());

		call(delete(CLONE), me.id()).andExpect(status().isNoContent());
		call(delete(CLONE), me.id()).andExpect(status().isNoContent());
		call(get("/api/me/profile"), me.id()).andExpect(jsonPath("$.character.voiceClone").isEmpty())
			.andExpect(jsonPath("$.character.voice").isEmpty());
		assertThat(stored(ImageStorage.voiceCloneSampleKey(me.id(), "wav"))).isFalse();
		assertThat(stored(ImageStorage.voiceClonePreviewKey(me.id()))).isFalse();
		assertThat(jdbc.queryForObject("select voice_clone_share_friends from user_profiles where user_id = ?",
				Boolean.class, me.id())).isFalse();
	}

	@Test
	void 만드는_중에_지우면_작업을_멈추고_워커_요청은_404다() throws Exception {
		User me = user(true);
		upload(me.id(), wav(64), "true").andExpect(status().isAccepted());
		long jobId = lastMessage(me.id()).get("jobId").asLong();
		call(delete(CLONE), me.id()).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select status from jobs where id = ?", String.class, jobId)).isEqualTo("FAILED");
		sample(me.id()).andExpect(status().isNotFound());
		preview(me.id(), wav(32)).andExpect(status().isNotFound());
	}

	@Test
	void 프로필_캐릭터를_지우거나_탈퇴하면_내_목소리도_지운다() throws Exception {
		User me = user(true);
		makeVoice(me);
		chooseVoice(me.id(), "self").andExpect(status().isNoContent());
		call(delete("/api/me/profile/character"), me.id()).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select voice_clone_status from user_profiles where user_id = ?", String.class,
				me.id())).isNull();
		assertThat(stored(ImageStorage.voiceCloneSampleKey(me.id(), "wav"))).isFalse();

		User other = user(true);
		makeVoice(other);
		mvc.perform(delete("/internal/users/" + other.id()).header("X-Internal-Token", INTERNAL))
			.andExpect(status().isNoContent());
		assertThat(stored(ImageStorage.voiceCloneSampleKey(other.id(), "wav"))).isFalse();
		assertThat(stored(ImageStorage.voiceClonePreviewKey(other.id()))).isFalse();
	}

}
