package com.lolog.api.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import com.lolog.api.card.Card;
import com.lolog.api.card.CardRepository;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
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
import tools.jackson.databind.ObjectMapper;

// 푸시 토큰 등록·지우기와 카드·만화 알림(#8)을 실제 PostgreSQL·Redis·SeaweedFS로 확인한다. Docker가 켜져 있어야 한다.
// FCM에는 보내지 않고 PushSender를 가짜로 바꿔 무엇을 보냈는지 본다. 푸시는 따로 보내므로 timeout으로 기다린다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class PushApiTests {

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

	private static final AtomicLong USERS = new AtomicLong(5000);
	private static final String INTERNAL = "internal-test-token";
	private static final long WAIT_MS = 5000;

	private static final String CARD = """
			{"kind":"웃김","summary":"치킨 주문하다 생긴 일","startMs":1000,"endMs":2000,"speakers":["화자1","화자2"],
			 "lines":[{"lineNo":1,"speaker":"화자1","text":"양념 반 후라이드 반","startMs":1000,"endMs":1500},
			          {"lineNo":2,"speaker":"화자2","text":"또 반반이야?","startMs":null,"endMs":null}]}
			""";

	@MockitoBean
	PushSender sender;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Autowired
	CardRepository cards;

	@Autowired
	ObjectMapper objectMapper;

	@BeforeEach
	void pushOn() {
		when(sender.enabled()).thenReturn(true);
	}

	private static long newUser() {
		return USERS.incrementAndGet();
	}

	private static String newToken() {
		return "fcm-" + UUID.randomUUID();
	}

	private ResultActions putToken(long userId, String token) throws Exception {
		return mvc.perform(put("/api/devices/fcm-token").header("Authorization", TestTokens.bearer(String.valueOf(userId)))
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("token", token))));
	}

	private ResultActions deleteToken(long userId, String token) throws Exception {
		return mvc.perform(delete("/api/devices/fcm-token").header("Authorization", TestTokens.bearer(String.valueOf(userId)))
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("token", token))));
	}

	private List<Long> ownersOf(String token) {
		return jdbc.queryForList("select user_id from device_tokens where token = ?", Long.class, token);
	}

	// 녹음 하나(마지막 묶음 하나)를 올리고 감지 작업 jobId를 돌려준다.
	private long[] uploadOnePart(long userId) throws Exception {
		String json = """
				{"clientRecordingId":"%s","partNo":1,"isLast":true,"startedAt":"2026-11-10T12:30:00+09:00","durationSec":60,
				 "sentences":[{"startMs":1000,"endMs":2000,"text":"앞 이야기"}]}
				""".formatted(UUID.randomUUID());
		String body = mvc.perform(post("/api/recordings").header("Authorization", TestTokens.bearer(String.valueOf(userId)))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		long recordingId = objectMapper.readTree(body).get("recordingId").asLong();
		long jobId = redisTemplate.opsForStream().range("lolog:detect", Range.unbounded()).stream()
			.map(r -> objectMapper.readTree((String) r.getValue().get("payload")))
			.filter(n -> n.get("recordingId").asLong() == recordingId)
			.findFirst().orElseThrow().get("jobId").asLong();
		return new long[] { recordingId, jobId };
	}

	private ResultActions detectResult(long recordingId, long jobId, String cardsJson) throws Exception {
		return mvc.perform(post("/internal/recordings/" + recordingId + "/cards").header("X-Internal-Token", INTERNAL)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"jobId\":%d,\"partNo\":1,\"carryFromMs\":null,\"cards\":[%s]}".formatted(jobId, cardsJson)));
	}

	private record Made(long cardId, long comicId, long jobId) {
	}

	// 카드를 만들고 앱처럼 만화를 요청한다.
	private Made comic(long userId) throws Exception {
		Card card = new Card(null, userId, "웃김", "치킨 주문하다 생긴 일", 1000, 9000,
				OffsetDateTime.parse("2026-11-10T12:30:01+09:00"));
		card.addSpeaker("화자1");
		card.addLine(1, "화자1", "양념 반 후라이드 반", null, null);
		long cardId = cards.save(card).getId();
		String body = mvc.perform(post("/api/cards/" + cardId + "/comics").header("Authorization", TestTokens.bearer(String.valueOf(userId))))
			.andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		long comicId = objectMapper.readTree(body).get("comicId").asLong();
		long jobId = redisTemplate.opsForStream().range("lolog:comic", Range.unbounded()).stream()
			.map(r -> objectMapper.readTree((String) r.getValue().get("payload")))
			.filter(n -> n.get("comicId").asLong() == comicId)
			.findFirst().orElseThrow().get("jobId").asLong();
		return new Made(cardId, comicId, jobId);
	}

	private static byte[] png() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		ImageIO.write(new BufferedImage(800, 3152, BufferedImage.TYPE_INT_RGB), "png", out);
		return out.toByteArray();
	}

	@Test
	void 토큰을_등록하면_204이고_같은_토큰을_다시_보내도_한_줄이다() throws Exception {
		long me = newUser();
		String token = newToken();

		putToken(me, token).andExpect(status().isNoContent());
		putToken(me, token).andExpect(status().isNoContent());

		assertThat(ownersOf(token)).containsExactly(me);
	}

	@Test
	void 같은_토큰을_다른_사용자가_등록하면_그_사용자로_옮긴다() throws Exception {
		long before = newUser();
		long after = newUser();
		String token = newToken();
		putToken(before, token).andExpect(status().isNoContent());

		putToken(after, token).andExpect(status().isNoContent());

		assertThat(ownersOf(token)).containsExactly(after);
	}

	@Test
	void 지우기는_내_토큰만_지우고_없거나_남의_토큰이면_204로_아무것도_안_한다() throws Exception {
		long me = newUser();
		long other = newUser();
		String token = newToken();
		putToken(me, token).andExpect(status().isNoContent());

		deleteToken(other, token).andExpect(status().isNoContent());
		assertThat(ownersOf(token)).containsExactly(me);
		deleteToken(me, newToken()).andExpect(status().isNoContent());

		deleteToken(me, token).andExpect(status().isNoContent());
		assertThat(ownersOf(token)).isEmpty();
	}

	@Test
	void 토큰이_비었거나_512자를_넘으면_400이고_로그인하지_않으면_401이다() throws Exception {
		long me = newUser();
		putToken(me, " ").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("token"));
		putToken(me, "a".repeat(513)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("token"));
		deleteToken(me, "").andExpect(status().isBadRequest());

		mvc.perform(put("/api/devices/fcm-token").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"x\"}"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void 감지_결과에_새_카드가_있으면_CARDS_CREATED를_한_번_보낸다() throws Exception {
		long me = newUser();
		String phone1 = newToken();
		String phone2 = newToken();
		putToken(me, phone1);
		putToken(me, phone2);
		long[] rec = uploadOnePart(me);

		detectResult(rec[0], rec[1], CARD + "," + CARD).andExpect(status().isNoContent());
		// 워커가 같은 결과를 다시 보내도 두 번 가지 않는다.
		detectResult(rec[0], rec[1], CARD).andExpect(status().isNoContent());

		Map<String, String> data = Map.of("type", "CARDS_CREATED", "recordingId", String.valueOf(rec[0]), "cardCount", "2");
		verify(sender, timeout(WAIT_MS)).send(List.of(phone1, phone2), data);
		verify(sender, after(500).times(1)).send(anyList(), any());
	}

	@Test
	void 새_카드가_없으면_보내지_않는다() throws Exception {
		long me = newUser();
		putToken(me, newToken());
		long[] rec = uploadOnePart(me);

		detectResult(rec[0], rec[1], "").andExpect(status().isNoContent());

		verify(sender, after(1000).never()).send(anyList(), any());
	}

	@Test
	void 만화가_완성되면_COMIC_DONE을_보낸다() throws Exception {
		long me = newUser();
		String token = newToken();
		putToken(me, token);
		Made m = comic(me);

		mvc.perform(multipart("/internal/comics/" + m.comicId() + "/image")
			.file(new MockMultipartFile("file", "comic.png", "image/png", png()))
			.header("X-Internal-Token", INTERNAL)).andExpect(status().isNoContent());

		verify(sender, timeout(WAIT_MS)).send(List.of(token),
				Map.of("type", "COMIC_DONE", "comicId", String.valueOf(m.comicId()), "cardId", String.valueOf(m.cardId())));
	}

	@Test
	void 만화가_실패하면_COMIC_FAILED를_보내고_카드를_지웠으면_cardId는_빈_문자열이다() throws Exception {
		long me = newUser();
		String token = newToken();
		putToken(me, token);
		Made m = comic(me);
		// 만드는 중인 만화의 카드는 앱에서 지울 수 없으므로 DB에서 직접 끊는다.
		jdbc.update("update comics set card_id = null where id = ?", m.comicId());

		mvc.perform(post("/internal/jobs/" + m.jobId() + "/fail").header("X-Internal-Token", INTERNAL)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"콘티 검사 실패\"}")).andExpect(status().isNoContent());

		verify(sender, timeout(WAIT_MS)).send(List.of(token),
				Map.of("type", "COMIC_FAILED", "comicId", String.valueOf(m.comicId()), "cardId", ""));
	}

	@Test
	void FCM이_무효라고_답한_토큰은_지운다() throws Exception {
		long me = newUser();
		String good = newToken();
		String gone = newToken();
		putToken(me, good);
		putToken(me, gone);
		when(sender.send(argThat(t -> t.contains(gone)), any())).thenReturn(List.of(gone));
		long[] rec = uploadOnePart(me);

		detectResult(rec[0], rec[1], CARD).andExpect(status().isNoContent());

		verify(sender, timeout(WAIT_MS)).send(eq(List.of(good, gone)), any());
		Thread.sleep(300);
		assertThat(ownersOf(gone)).isEmpty();
		assertThat(ownersOf(good)).containsExactly(me);
	}

	@Test
	void 보내기가_실패해도_워커는_204를_받고_토큰은_남는다() throws Exception {
		long me = newUser();
		String token = newToken();
		putToken(me, token);
		when(sender.send(argThat(t -> t.contains(token)), any())).thenThrow(new IOException("FCM 연결 실패"));
		long[] rec = uploadOnePart(me);

		detectResult(rec[0], rec[1], CARD).andExpect(status().isNoContent());

		verify(sender, timeout(WAIT_MS)).send(eq(List.of(token)), any());
		assertThat(ownersOf(token)).containsExactly(me);
		assertThat(cards.findByRecordingIdOrderById(rec[0])).hasSize(1);
	}

	@Test
	void 토큰이_없는_사용자에게는_보내지_않는다() throws Exception {
		long me = newUser();
		long[] rec = uploadOnePart(me);

		detectResult(rec[0], rec[1], CARD).andExpect(status().isNoContent());

		verify(sender, after(1000).never()).send(anyList(), any());
	}

}
