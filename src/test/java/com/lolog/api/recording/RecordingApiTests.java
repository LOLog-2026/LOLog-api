package com.lolog.api.recording;

import com.lolog.api.auth.TestTokens;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 실제 PostgreSQL과 Redis로 POST /api/recordings 규칙을 확인한다. Docker가 켜져 있어야 한다.
// 회원은 테스트 액세스 토큰(TestTokens)의 sub다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class RecordingApiTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Container
	@ServiceConnection(name = "redis")
	@SuppressWarnings("resource")
	static GenericContainer<?> redis = new GenericContainer<>("redis:latest").withExposedPorts(6379);

	@Autowired
	MockMvc mvc;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Autowired
	RecordingPartRepository parts;

	@MockitoSpyBean
	RecordingService service;

	@Autowired
	ObjectMapper objectMapper;

	private static final String ONE = "[{\"startMs\":1000,\"endMs\":2000,\"text\":\"야 너 어제 그거 봤어\"}]";

	private static String body(String id, int partNo, boolean last, String sentences) {
		return """
				{"clientRecordingId":"%s","partNo":%d,"isLast":%b,"startedAt":"2026-11-10T12:30:00+09:00","durationSec":3600,"sentences":%s}
				""".formatted(id, partNo, last, sentences);
	}

	private org.springframework.test.web.servlet.ResultActions upload(String user, String json) throws Exception {
		return mvc.perform(post("/api/recordings").header("Authorization", TestTokens.bearer(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	private List<JsonNode> messagesFor(long recordingId) {
		List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
			.range(DetectPublisher.STREAM, Range.unbounded());
		return records.stream()
			.map(r -> objectMapper.readTree((String) r.getValue().get("payload")))
			.filter(n -> n.get("recordingId").asLong() == recordingId)
			.toList();
	}

	private long recordingId(String user, String id, int partNo, boolean last, String sentences) throws Exception {
		String response = upload(user, body(id, partNo, last, sentences)).andExpect(status().isAccepted())
			.andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("recordingId").asLong();
	}

	@Test
	void 첫_묶음을_저장하고_큐에_넣는다() throws Exception {
		String id = UUID.randomUUID().toString();
		upload("1", body(id, 1, false, ONE)).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("DETECTING"));

		long recordingId = objectMapper.readTree(upload("1", body(id, 1, false, ONE)).andReturn().getResponse().getContentAsString())
			.get("recordingId").asLong();
		List<JsonNode> messages = messagesFor(recordingId);
		assertThat(messages).hasSize(1);
		JsonNode message = messages.getFirst();
		assertThat(message.get("jobId").asLong()).isPositive();
		assertThat(message.get("partNo").asInt()).isEqualTo(1);
		assertThat(message.get("isLast").asBoolean()).isFalse();
		assertThat(message.get("sentences").get(0).get("text").asString()).isEqualTo("야 너 어제 그거 봤어");
	}

	@Test
	void 재시도는_다시_저장하지_않고_내용이_달라도_처음_것을_유지한다() throws Exception {
		String id = UUID.randomUUID().toString();
		long recordingId = recordingId("2", id, 1, false, ONE);
		upload("2", body(id, 1, false, "[{\"startMs\":1,\"endMs\":2,\"text\":\"다른 글\"}]"))
			.andExpect(status().isAccepted())
			.andExpect(jsonPath("$.recordingId").value(recordingId));

		assertThat(parts.findByRecordingIdOrderByPartNo(recordingId)).hasSize(1);
		assertThat(messagesFor(recordingId)).hasSize(1);
		assertThat(messagesFor(recordingId).getFirst().toString()).contains("야 너 어제");
	}

	@Test
	void 번호를_건너뛰면_409다() throws Exception {
		String id = UUID.randomUUID().toString();
		upload("3", body(id, 2, false, ONE)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PART_OUT_OF_ORDER"));
	}

	@Test
	void 끝난_녹음에_새_묶음이_오면_409다() throws Exception {
		String id = UUID.randomUUID().toString();
		recordingId("4", id, 1, true, ONE);
		upload("4", body(id, 2, false, ONE)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("RECORDING_CLOSED"));
	}

	@Test
	void 빈_마지막_묶음은_감지_없이_녹음을_끝낸다() throws Exception {
		String id = UUID.randomUUID().toString();
		upload("5", body(id, 1, true, "[]")).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.status").value("DONE"));
	}

	@Test
	void 앞_묶음이_끝나야_다음_묶음을_넘겨받은_글과_함께_넣는다() throws Exception {
		String id = UUID.randomUUID().toString();
		String first = "[{\"startMs\":1000,\"endMs\":2000,\"text\":\"앞\"},{\"startMs\":3000,\"endMs\":4000,\"text\":\"넘길 글\"}]";
		long recordingId = recordingId("6", id, 1, false, first);
		recordingId("6", id, 2, true, "[{\"startMs\":5000,\"endMs\":6000,\"text\":\"뒤\"}]");
		assertThat(messagesFor(recordingId)).hasSize(1);

		// 워커가 첫 묶음을 끝내고 3000ms부터 넘긴 상황
		RecordingPart part = parts.findByRecordingIdOrderByPartNo(recordingId).getFirst();
		part.setStatus(RecordingStatus.DONE);
		part.setCarryFromMs(3000L);
		parts.saveAndFlush(part);
		service.dispatchNext(recordingId);

		List<JsonNode> messages = messagesFor(recordingId);
		assertThat(messages).hasSize(2);
		JsonNode sentences = messages.get(1).get("sentences");
		assertThat(sentences).hasSize(2);
		assertThat(sentences.get(0).get("text").asString()).isEqualTo("넘길 글");
		assertThat(sentences.get(1).get("text").asString()).isEqualTo("뒤");
		assertThat(messages.get(1).get("isLast").asBoolean()).isTrue();
	}

	@Test
	void 형식이_틀리면_400이다() throws Exception {
		String id = UUID.randomUUID().toString();
		upload("7", body(id, 1, false, "[{\"startMs\":2000,\"endMs\":1000,\"text\":\"글\"}]"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("sentences[0].endMs"));
		upload("7", body(id, 1, false, "[{\"startMs\":1,\"endMs\":2,\"text\":\"글\"},{\"startMs\":1,\"endMs\":2,\"text\":\" \"}]"))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("sentences[1].text"));
		upload("7", "{\"partNo\":1}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").isNotEmpty());
		upload("7", body(id, 1, false, ONE).replace("\"partNo\":1", "\"partNo\":\"하나\""))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("partNo"));
	}

	@Test
	void 크기를_넘으면_413이다() throws Exception {
		String id = UUID.randomUUID().toString();
		StringBuilder many = new StringBuilder("[");
		for (int i = 0; i <= RecordingService.MAX_SENTENCES; i++) {
			many.append(i == 0 ? "" : ",").append("{\"startMs\":1,\"endMs\":2,\"text\":\"글\"}");
		}
		upload("8", body(id, 1, false, many.append("]").toString())).andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
		String huge = "[{\"startMs\":1,\"endMs\":2,\"text\":\"" + "글".repeat(400_000) + "\"}]";
		upload("8", body(id, 1, false, huge)).andExpect(status().isPayloadTooLarge());
	}

	@Test
	void 인증이_없으면_401이고_남의_녹음은_404다() throws Exception {
		mvc.perform(post("/api/recordings").contentType(MediaType.APPLICATION_JSON)
			.content(body(UUID.randomUUID().toString(), 1, false, ONE)))
			.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		long recordingId = recordingId("9", UUID.randomUUID().toString(), 1, false, ONE);
		mvc.perform(get("/api/recordings/" + recordingId).header("Authorization", TestTokens.bearer("9")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DETECTING"))
			.andExpect(jsonPath("$.cardCount").value(0));
		mvc.perform(get("/api/recordings/" + recordingId).header("Authorization", TestTokens.bearer("10")))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"))
			.andExpect(jsonPath("$.field").isEmpty());
	}

	@Test
	void 상태_응답은_recordingId_status_cardCount다() throws Exception {
		long recordingId = recordingId("11", UUID.randomUUID().toString(), 1, false, ONE);
		mvc.perform(get("/api/recordings/" + recordingId).header("Authorization", TestTokens.bearer("11")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$.recordingId").value(recordingId))
			.andExpect(jsonPath("$.status").value("DETECTING"))
			.andExpect(jsonPath("$.cardCount").value(0));
	}

	@Test
	void clientRecordingId가_UUID_형식이_아니면_400이다() throws Exception {
		for (String id : List.of("", "not-a-uuid", "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz", "7f3c".repeat(16))) {
			upload("12", body(id, 1, false, ONE)).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
		upload("12", body(UUID.randomUUID().toString(), 1, false, ONE)).andExpect(status().isAccepted());
	}

	@Test
	void 처리하지_못한_예외는_500이고_로그에_대화_글을_남기지_않는다(CapturedOutput output) throws Exception {
		String conversation = "야 너 어제 그거 봤어";
		doThrow(new IllegalStateException("바깥 " + conversation, new IllegalArgumentException("Failing row contains " + conversation)))
			.when(service).status(anyLong(), anyLong());

		mvc.perform(get("/api/recordings/1").header("Authorization", TestTokens.bearer("13")))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.message").value("서버 오류가 발생했습니다."));

		assertThat(output).contains("java.lang.IllegalStateException", "java.lang.IllegalArgumentException");
		assertThat(output).doesNotContain(conversation);
	}

	@Test
	void 주소의_id가_숫자가_아니면_400이다() throws Exception {
		for (String id : List.of("abc", "99999999999999999999")) {
			mvc.perform(get("/api/recordings/" + id).header("Authorization", TestTokens.bearer("14")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
	}

	@Test
	void 없는_주소는_500이_아니라_404다() throws Exception {
		mvc.perform(get("/api/no-such-path").header("Authorization", TestTokens.bearer("15"))).andExpect(status().isNotFound());
	}

}
