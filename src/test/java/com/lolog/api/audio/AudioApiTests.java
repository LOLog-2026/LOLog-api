package com.lolog.api.audio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import com.lolog.api.storage.ImageStorage;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
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

// 음성 저장(#42, audio.md)을 실제 PostgreSQL·Redis·SeaweedFS로 확인한다. Docker가 켜져 있어야 한다.
// 음성 파일은 16kHz ADTS 조각(64ms)을 직접 만들어 쓴다. 조각마다 내용 바이트가 달라 어느 조각이 잘렸는지 알 수 있다.
@SpringBootTest(properties = "lolog.audio.retention-initial-delay-ms=3600000")
@AutoConfigureMockMvc
@Testcontainers
class AudioApiTests {

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

	private static final AtomicLong USERS = new AtomicLong(8000);
	private static final String INTERNAL = "internal-test-token";
	private static final long WAIT_MS = 5000;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ImageStorage storage;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Autowired
	AudioRetentionJob retention;

	@Autowired
	ObjectMapper objectMapper;

	// ---- 음성 파일 만들기 ----

	// 16kHz 모노 AAC LC ADTS 조각 하나(머리말 7바이트 + 내용 3바이트). 내용은 파일 번호와 조각 번호다.
	private static byte[] frame(int fileNo, int index) {
		int length = 10;
		return new byte[] { (byte) 0xFF, (byte) 0xF1, (byte) 0x60, (byte) (0x40 | (length >> 11)),
				(byte) ((length >> 3) & 0xFF), (byte) (((length & 0x07) << 5) | 0x1F), (byte) 0xFC, (byte) fileNo,
				(byte) (index >> 8), (byte) index };
	}

	// 조각 count개(64ms씩)
	private static byte[] aac(int fileNo, int count) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = 0; i < count; i++) {
			out.writeBytes(frame(fileNo, i));
		}
		return out.toByteArray();
	}

	// fileNo 파일의 조각 from~to(포함)를 이은 것
	private static byte[] frames(int fileNo, int from, int to) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (int i = from; i <= to; i++) {
			out.writeBytes(frame(fileNo, i));
		}
		return out.toByteArray();
	}

	private static byte[] concat(byte[] a, byte[] b) {
		byte[] result = Arrays.copyOf(a, a.length + b.length);
		System.arraycopy(b, 0, result, a.length, b.length);
		return result;
	}

	// ---- 요청 ----

	private long upload(long userId, String clientId, int partNo, boolean isLast, int durationSec, Boolean audioSaved)
			throws Exception {
		String saved = audioSaved == null ? "" : ",\"audioSaved\":" + audioSaved;
		String body = """
				{"clientRecordingId":"%s","partNo":%d,"isLast":%s,"startedAt":"2026-11-10T12:30:00+09:00","durationSec":%d,
				 "sentences":[{"startMs":1000,"endMs":2000,"text":"대화 글"}]%s}
				""".formatted(clientId, partNo, isLast, durationSec, saved);
		String response = mvc.perform(post("/api/recordings").header("Authorization", TestTokens.bearer(userId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("recordingId").asLong();
	}

	private long newRecording(long userId, boolean isLast) throws Exception {
		return upload(userId, UUID.randomUUID().toString(), 1, isLast, 60, true);
	}

	private ResultActions putAudio(long userId, long recordingId, Object seq, byte[] file, Object startMs, Object endMs)
			throws Exception {
		var request = multipart(HttpMethod.PUT, "/api/recordings/" + recordingId + "/audio/" + seq)
			.file(new MockMultipartFile("file", "audio.aac", "audio/aac", file))
			.header("Authorization", TestTokens.bearer(userId));
		if (startMs != null) {
			request.param("startMs", String.valueOf(startMs));
		}
		if (endMs != null) {
			request.param("endMs", String.valueOf(endMs));
		}
		return mvc.perform(request);
	}

	private long insertCard(long userId, long recordingId, long startMs, long endMs) {
		return jdbc.queryForObject("""
				insert into cards (recording_id, user_id, kind, summary, start_ms, end_ms, recorded_at)
				values (?, ?, '웃김', '요약', ?, ?, now()) returning id
				""", Long.class, recordingId, userId, startMs, endMs);
	}

	private String audioKey(long cardId) {
		return jdbc.queryForObject("select audio_key from cards where id = ?", String.class, cardId);
	}

	private long audioDuration(long cardId) {
		return jdbc.queryForObject("select audio_duration_ms from cards where id = ?", Long.class, cardId);
	}

	// 따로 자르니 잘릴 때까지 기다린다.
	private String awaitAudio(long cardId) throws InterruptedException {
		long until = System.currentTimeMillis() + WAIT_MS;
		while (System.currentTimeMillis() < until) {
			String key = audioKey(cardId);
			if (key != null) {
				return key;
			}
			Thread.sleep(50);
		}
		throw new AssertionError("카드 " + cardId + "의 구간 음성이 잘리지 않았습니다.");
	}

	// 감지 작업 jobId를 큐에서 찾아 워커처럼 카드 하나를 결과로 보낸다.
	private long detectCard(long recordingId, long startMs, long endMs) throws Exception {
		long jobId = redisTemplate.opsForStream().range("lolog:detect", Range.unbounded()).stream()
			.map(r -> objectMapper.readTree((String) r.getValue().get("payload")))
			.filter(n -> n.get("recordingId").asLong() == recordingId)
			.findFirst().orElseThrow().get("jobId").asLong();
		String card = """
				{"kind":"웃김","summary":"치킨 주문","startMs":%d,"endMs":%d,"speakers":["화자1"],
				 "lines":[{"lineNo":1,"speaker":"화자1","text":"양념 반","startMs":null,"endMs":null}]}
				""".formatted(startMs, endMs);
		mvc.perform(post("/internal/recordings/" + recordingId + "/cards").header("X-Internal-Token", INTERNAL)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"jobId\":%d,\"partNo\":1,\"carryFromMs\":null,\"cards\":[%s]}".formatted(jobId, card)))
			.andExpect(status().isNoContent());
		return jdbc.queryForObject("select id from cards where recording_id = ?", Long.class, recordingId);
	}

	// ---- 테스트 ----

	@Test
	void 카드가_있으면_음성을_올릴_때_앞뒤_0점5초를_더해_조각_단위로_자른다() throws Exception {
		long user = USERS.incrementAndGet();
		long recording = newRecording(user, false);
		long card = insertCard(user, recording, 1000, 2000);

		putAudio(user, recording, 1, aac(1, 100), 0, 6400).andExpect(status().isNoContent());

		String key = awaitAudio(card);
		assertThat(key).isEqualTo(ImageStorage.cardAudioKey(user, card));
		// 500~2500ms에 걸치는 조각: 7번(448~512)부터 39번(2496~2560)까지 33개
		assertThat(storage.get(key)).isEqualTo(frames(1, 7, 39));
		assertThat(audioDuration(card)).isEqualTo(33 * 64);
		assertThat(storage.get(ImageStorage.recordingAudioKey(user, recording, 1))).isEqualTo(aac(1, 100));
	}

	@Test
	void 여러_파일에_걸친_카드는_파일이_다_오면_이어_붙인다() throws Exception {
		long user = USERS.incrementAndGet();
		long recording = newRecording(user, false);
		long card = insertCard(user, recording, 6000, 7000);

		putAudio(user, recording, 1, aac(1, 100), 0, 6400).andExpect(status().isNoContent());
		Thread.sleep(500);
		assertThat(audioKey(card)).as("뒤 파일이 오기 전에는 자르지 않는다").isNull();

		putAudio(user, recording, 2, aac(2, 100), 6400, 12800).andExpect(status().isNoContent());
		String key = awaitAudio(card);
		// 5500~7500ms: 1번 파일 85~99번 조각(5440~6400) + 2번 파일 0~17번 조각(6400~7552)
		assertThat(storage.get(key)).isEqualTo(concat(frames(1, 85, 99), frames(2, 0, 17)));
		assertThat(audioDuration(card)).isEqualTo(33 * 64);
	}

	@Test
	void 음성이_먼저_오고_감지_결과가_나중에_와도_자르고_끝난_녹음은_끝에서_줄인다() throws Exception {
		long user = USERS.incrementAndGet();
		long recording = newRecording(user, true);
		putAudio(user, recording, 1, aac(1, 100), 0, 6400).andExpect(status().isNoContent());

		long card = detectCard(recording, 6000, 6300);

		String key = awaitAudio(card);
		// 5500~6400ms(끝은 마지막 파일 끝으로 줄임): 85~99번 조각
		assertThat(storage.get(key)).isEqualTo(frames(1, 85, 99));
	}

	@Test
	void 같은_번호를_다시_올리면_덮어쓴다() throws Exception {
		long user = USERS.incrementAndGet();
		long recording = newRecording(user, false);
		putAudio(user, recording, 1, aac(1, 10), 0, 640).andExpect(status().isNoContent());
		putAudio(user, recording, 1, aac(9, 20), 0, 1280).andExpect(status().isNoContent());

		assertThat(jdbc.queryForObject("select count(*) from recording_audio_files where recording_id = ?", Long.class,
				recording)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select end_ms from recording_audio_files where recording_id = ?", Long.class,
				recording)).isEqualTo(1280);
		assertThat(storage.get(ImageStorage.recordingAudioKey(user, recording, 1))).isEqualTo(aac(9, 20));
	}

	@Test
	void 음성_올리기_오류() throws Exception {
		long user = USERS.incrementAndGet();
		long recording = newRecording(user, false);
		long off = upload(user, UUID.randomUUID().toString(), 1, false, 60, null);
		byte[] ok = aac(1, 10);

		putAudio(user, off, 1, ok, 0, 640).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		putAudio(user + 1000, recording, 1, ok, 0, 640).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
		putAudio(user, 999_999_999, 1, ok, 0, 640).andExpect(status().isNotFound());
		putAudio(user, recording, 0, ok, 0, 640).andExpect(jsonPath("$.field").value("seq"));
		putAudio(user, recording, "x", ok, 0, 640).andExpect(jsonPath("$.field").value("seq"));
		putAudio(user, recording, 1, ok, 640, 640).andExpect(jsonPath("$.field").value("endMs"));
		putAudio(user, recording, 1, ok, -1, 640).andExpect(jsonPath("$.field").value("startMs"));
		putAudio(user, recording, 1, ok, null, 640).andExpect(jsonPath("$.field").value("startMs"));
		putAudio(user, recording, 1, ok, 0, null).andExpect(jsonPath("$.field").value("endMs"));
		putAudio(user, recording, 1, "not aac".getBytes(), 0, 640).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("file"));
		putAudio(user, recording, 1, Arrays.copyOf(ok, ok.length - 3), 0, 640).andExpect(jsonPath("$.field").value("file"));
		byte[] big = aac(1, (int) (AudioUploadService.MAX_BYTES / 10) + 1);
		putAudio(user, recording, 1, big, 0, 640).andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
		mvc.perform(multipart(HttpMethod.PUT, "/api/recordings/" + recording + "/audio/1").param("startMs", "0")
			.param("endMs", "640")
			.header("Authorization", TestTokens.bearer(user))).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("file"));
		assertThat(jdbc.queryForObject("select count(*) from recording_audio_files where recording_id = ?", Long.class,
				recording)).isZero();
	}

	@Test
	void 녹음_끝_시각은_묶음마다_고쳐_적고_음성_저장_여부는_첫_묶음_값이다() throws Exception {
		long user = USERS.incrementAndGet();
		String clientId = UUID.randomUUID().toString();
		long recording = upload(user, clientId, 1, false, 60, true);
		upload(user, clientId, 2, false, 30, false);

		OffsetDateTime started = OffsetDateTime.parse("2026-11-10T12:30:00+09:00");
		assertThat(jdbc.queryForObject("select ended_at from recordings where id = ?", OffsetDateTime.class, recording)
			.toInstant()).isEqualTo(started.plusSeconds(90).toInstant());
		assertThat(jdbc.queryForObject("select audio_saved from recordings where id = ?", Boolean.class, recording))
			.isTrue();
	}

	@Test
	void 한_시간마다_못_자른_카드를_다시_자르고_7일_지난_전체_파일을_지운다() throws Exception {
		long user = USERS.incrementAndGet();
		long recording = newRecording(user, true);
		putAudio(user, recording, 1, aac(1, 100), 0, 6400).andExpect(status().isNoContent());
		Thread.sleep(300);
		// 음성이 올라온 뒤 다른 길로 생긴 카드(자르기가 불리지 않음)
		long card = insertCard(user, recording, 1000, 2000);
		assertThat(audioKey(card)).isNull();

		jdbc.update("update recordings set ended_at = now() - interval '8 days' where id = ?", recording);
		retention.run();

		assertThat(audioKey(card)).as("지우기 전에 먼저 자른다").isNotNull();
		assertThat(storage.get(audioKey(card))).isEqualTo(frames(1, 7, 39));
		assertThat(jdbc.queryForObject("select count(*) from recording_audio_files where recording_id = ?", Long.class,
				recording)).isZero();
		assertThatThrownBy(() -> storage.get(ImageStorage.recordingAudioKey(user, recording, 1)))
			.isInstanceOf(NoSuchKeyException.class);
	}

	@Test
	void 칠일이_안_지난_전체_파일은_남긴다() throws Exception {
		long user = USERS.incrementAndGet();
		long recording = newRecording(user, true);
		putAudio(user, recording, 1, aac(1, 10), 0, 640).andExpect(status().isNoContent());
		jdbc.update("update recordings set ended_at = now() - interval '6 days' where id = ?", recording);

		retention.run();

		assertThat(storage.get(ImageStorage.recordingAudioKey(user, recording, 1))).isEqualTo(aac(1, 10));
	}

	@Test
	void 카드를_지우면_구간_음성도_지우고_탈퇴하면_음성을_모두_지운다() throws Exception {
		long user = USERS.incrementAndGet();
		long recording = newRecording(user, false);
		long gone = insertCard(user, recording, 1000, 2000);
		long kept = insertCard(user, recording, 3000, 4000);
		putAudio(user, recording, 1, aac(1, 100), 0, 6400).andExpect(status().isNoContent());
		String goneKey = awaitAudio(gone);
		String keptKey = awaitAudio(kept);

		mvc.perform(delete("/api/cards/" + gone).header("Authorization", TestTokens.bearer(user)))
			.andExpect(status().isNoContent());
		long until = System.currentTimeMillis() + WAIT_MS;
		boolean deleted = false;
		while (!deleted && System.currentTimeMillis() < until) {
			try {
				storage.get(goneKey);
				Thread.sleep(50);
			}
			catch (NoSuchKeyException e) {
				deleted = true;
			}
		}
		assertThat(deleted).isTrue();
		assertThat(storage.get(keptKey)).isNotEmpty();

		mvc.perform(delete("/internal/users/" + user).header("X-Internal-Token", INTERNAL))
			.andExpect(status().isNoContent());
		assertThatThrownBy(() -> storage.get(keptKey)).isInstanceOf(NoSuchKeyException.class);
		assertThatThrownBy(() -> storage.get(ImageStorage.recordingAudioKey(user, recording, 1)))
			.isInstanceOf(NoSuchKeyException.class);
		assertThat(jdbc.queryForObject("select count(*) from recording_audio_files where recording_id = ?", Long.class,
				recording)).isZero();
	}

}
