package com.lolog.api.recording;

import com.lolog.api.auth.TestTokens;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.card.CardRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 워커 내부 API(감지 결과, 실패)와 그 뒤의 흐름을 실제 PostgreSQL·Redis로 확인한다. Docker가 켜져 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class WorkerApiTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Container
	@ServiceConnection(name = "redis")
	@SuppressWarnings("resource")
	static GenericContainer<?> redis = new GenericContainer<>("redis:latest").withExposedPorts(6379);

	private static final String TOKEN = "internal-test-token";

	@Autowired
	MockMvc mvc;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	RecordingPartRepository parts;

	@Autowired
	RecordingRepository recordings;

	@Autowired
	CardRepository cards;

	@Autowired
	DetectResultService detectResults;

	@Autowired
	TransactionTemplate tx;

	private static final String TWO = "[{\"startMs\":1000,\"endMs\":2000,\"text\":\"앞 이야기\"},{\"startMs\":3000,\"endMs\":4000,\"text\":\"넘길 글\"}]";

	private static final String CARD = """
			{"kind":"웃김","summary":"치킨 주문하다 생긴 일","startMs":1000,"endMs":2000,"speakers":["화자1","화자2"],
			 "lines":[{"lineNo":1,"speaker":"화자1","text":"양념 반 후라이드 반","startMs":1000,"endMs":1500},
			          {"lineNo":2,"speaker":"화자2","text":"또 반반이야?","startMs":null,"endMs":null}]}
			""";

	private long upload(String user, String id, int partNo, boolean last, String sentences) throws Exception {
		String json = """
				{"clientRecordingId":"%s","partNo":%d,"isLast":%b,"startedAt":"2026-11-10T12:30:00+09:00","durationSec":3600,"sentences":%s}
				""".formatted(id, partNo, last, sentences);
		String response = mvc.perform(post("/api/recordings").header("Authorization", TestTokens.bearer(user))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return objectMapper.readTree(response).get("recordingId").asLong();
	}

	private List<JsonNode> messagesFor(long recordingId) {
		return redisTemplate.opsForStream().range(DetectPublisher.STREAM, Range.unbounded()).stream()
			.map(r -> objectMapper.readTree((String) r.getValue().get("payload")))
			.filter(n -> n.get("recordingId").asLong() == recordingId)
			.toList();
	}

	private long jobId(long recordingId, int index) {
		return messagesFor(recordingId).get(index).get("jobId").asLong();
	}

	private ResultActions result(long recordingId, long jobId, int partNo, String carry, String cardsJson)
			throws Exception {
		return mvc.perform(post("/internal/recordings/" + recordingId + "/cards").header("X-Internal-Token", TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"jobId\":%d,\"partNo\":%d,\"carryFromMs\":%s,\"cards\":[%s]}".formatted(jobId, partNo, carry,
					cardsJson)));
	}

	private ResultActions fail(long jobId) throws Exception {
		return mvc.perform(post("/internal/jobs/" + jobId + "/fail").header("X-Internal-Token", TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"LLM 응답 형식 오류\"}"));
	}

	private RecordingPart part(long recordingId, int partNo) {
		return parts.findByRecordingIdOrderByPartNo(recordingId).get(partNo - 1);
	}

	private String recordingStatus(long recordingId) {
		return recordings.findById(recordingId).orElseThrow().getStatus().name();
	}

	@Test
	void 내부_토큰이_없거나_틀리면_401이다() throws Exception {
		mvc.perform(post("/internal/jobs/1/fail").contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"x\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		mvc.perform(post("/internal/jobs/1/fail").header("X-Internal-Token", "wrong")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"x\"}")).andExpect(status().isUnauthorized());
		// 앱의 임시 인증으로는 /internal을 부를 수 없다.
		mvc.perform(post("/internal/jobs/1/fail").header("Authorization", TestTokens.bearer("1"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"x\"}")).andExpect(status().isUnauthorized());
	}

	@Test
	void 결과를_받으면_카드를_저장하고_다_쓴_글을_지우고_다음_묶음을_넘겨받은_글과_넣는다() throws Exception {
		String id = UUID.randomUUID().toString();
		long recordingId = upload("101", id, 1, false, TWO);
		upload("101", id, 2, true, "[{\"startMs\":5000,\"endMs\":6000,\"text\":\"뒤\"}]");

		result(recordingId, jobId(recordingId, 0), 1, "3000", CARD).andExpect(status().isNoContent());

		var saved = cards.findByRecordingIdOrderById(recordingId);
		assertThat(saved).hasSize(1);
		assertThat(saved.getFirst().getUserId()).isEqualTo(101L);
		assertThat(saved.getFirst().getRecordedAt()).isEqualTo(OffsetDateTime.parse("2026-11-10T12:30:01+09:00"));
		tx.executeWithoutResult(s -> {
			var card = cards.findById(saved.getFirst().getId()).orElseThrow();
			assertThat(card.getSpeakers()).extracting("speakerKey").containsExactly("화자1", "화자2");
			assertThat(card.getSpeakers()).extracting("name").containsOnlyNulls();
			assertThat(card.getLines()).extracting("text").containsExactly("양념 반 후라이드 반", "또 반반이야?");
		});

		// 첫 묶음에는 넘길 부분(3000ms 이후)만 남는다.
		assertThat(part(recordingId, 1).getSentences()).contains("넘길 글").doesNotContain("앞 이야기");
		assertThat(part(recordingId, 1).getCarryFromMs()).isEqualTo(3000L);

		List<JsonNode> messages = messagesFor(recordingId);
		assertThat(messages).hasSize(2);
		JsonNode sentences = messages.get(1).get("sentences");
		assertThat(sentences).hasSize(2);
		assertThat(sentences.get(0).get("text").asString()).isEqualTo("넘길 글");
		assertThat(sentences.get(1).get("text").asString()).isEqualTo("뒤");

		mvc.perform(get("/api/recordings/" + recordingId).header("Authorization", TestTokens.bearer("101")))
			.andExpect(jsonPath("$.status").value("DETECTING"))
			.andExpect(jsonPath("$.cardCount").value(1));

		// 마지막 묶음이 끝나면 carryFromMs는 무시하고 모든 글을 지우며 녹음이 끝난다.
		result(recordingId, jobId(recordingId, 1), 2, "5500", "").andExpect(status().isNoContent());
		assertThat(part(recordingId, 1).getSentences()).isNull();
		assertThat(part(recordingId, 2).getSentences()).isNull();
		assertThat(part(recordingId, 2).getCarryFromMs()).isNull();
		assertThat(recordingStatus(recordingId)).isEqualTo("DONE");
	}

	@Test
	void 앞_묶음_카드와_시간이_겹치는_카드는_버린다() throws Exception {
		String id = UUID.randomUUID().toString();
		long recordingId = upload("105", id, 1, false, TWO);
		upload("105", id, 2, true, "[{\"startMs\":5000,\"endMs\":6000,\"text\":\"뒤\"}]");
		// 1묶음: 1000~2000 카드
		result(recordingId, jobId(recordingId, 0), 1, "3000", CARD).andExpect(status().isNoContent());

		// 2묶음: 같은 이야기(1500~2500, 겹침)와 새 이야기(5000~6000), 끝이 맞닿기만 한 이야기(2000~3000)
		String overlap = CARD.replace("\"startMs\":1000,\"endMs\":2000", "\"startMs\":1500,\"endMs\":2500");
		String fresh = CARD.replace("\"startMs\":1000,\"endMs\":2000", "\"startMs\":5000,\"endMs\":6000");
		String touching = CARD.replace("\"startMs\":1000,\"endMs\":2000", "\"startMs\":2000,\"endMs\":3000");
		result(recordingId, jobId(recordingId, 1), 2, "null", overlap + "," + fresh + "," + touching)
			.andExpect(status().isNoContent());

		assertThat(cards.findByRecordingIdOrderById(recordingId)).extracting("startMs")
			.containsExactly(1000L, 5000L, 2000L);
	}

	@Test
	void 같은_jobId가_다시_오면_204로_받고_무시한다() throws Exception {
		long recordingId = upload("102", UUID.randomUUID().toString(), 1, true, TWO);
		long jobId = jobId(recordingId, 0);
		result(recordingId, jobId, 1, "null", CARD).andExpect(status().isNoContent());
		result(recordingId, jobId, 1, "null", CARD + "," + CARD).andExpect(status().isNoContent());
		fail(jobId).andExpect(status().isNoContent());

		assertThat(cards.findByRecordingIdOrderById(recordingId)).hasSize(1);
		assertThat(recordingStatus(recordingId)).isEqualTo("DONE");
	}

	@Test
	void 감지가_실패하면_글을_지우고_다음_묶음은_넘겨받을_것_없이_넣는다() throws Exception {
		String id = UUID.randomUUID().toString();
		long recordingId = upload("103", id, 1, false, TWO);
		upload("103", id, 2, true, "[{\"startMs\":5000,\"endMs\":6000,\"text\":\"뒤\"}]");

		fail(jobId(recordingId, 0)).andExpect(status().isNoContent());
		assertThat(part(recordingId, 1).getStatus()).isEqualTo(RecordingStatus.FAILED);
		assertThat(part(recordingId, 1).getSentences()).isNull();
		JsonNode second = messagesFor(recordingId).get(1);
		assertThat(second.get("sentences")).hasSize(1);
		assertThat(second.get("sentences").get(0).get("text").asString()).isEqualTo("뒤");

		// 하나라도 성공했으면 녹음은 DONE이다.
		result(recordingId, jobId(recordingId, 1), 2, "null", CARD).andExpect(status().isNoContent());
		assertThat(recordingStatus(recordingId)).isEqualTo("DONE");
	}

	@Test
	void 감지한_묶음이_모두_실패하면_녹음은_FAILED다() throws Exception {
		long recordingId = upload("104", UUID.randomUUID().toString(), 1, true, TWO);
		fail(jobId(recordingId, 0)).andExpect(status().isNoContent());
		assertThat(recordingStatus(recordingId)).isEqualTo("FAILED");
		mvc.perform(get("/api/recordings/" + recordingId).header("Authorization", TestTokens.bearer("104")))
			.andExpect(jsonPath("$.status").value("FAILED"));
	}

	@Test
	void 다음_묶음이_늦으면_넘겨_둔_부분만_마지막_묶음처럼_넣는다() throws Exception {
		String id = UUID.randomUUID().toString();
		long recordingId = upload("105", id, 1, false, TWO);
		result(recordingId, jobId(recordingId, 0), 1, "3000", "").andExpect(status().isNoContent());

		// 아직 기다릴 때가 아니면 넣지 않는다.
		detectResults.dispatchLateCarries(OffsetDateTime.now().minusMinutes(70));
		assertThat(messagesFor(recordingId)).hasSize(1);

		detectResults.dispatchLateCarries(OffsetDateTime.now().plusMinutes(1));
		List<JsonNode> messages = messagesFor(recordingId);
		assertThat(messages).hasSize(2);
		JsonNode carry = messages.get(1);
		assertThat(carry.get("jobId").asLong()).isNotEqualTo(messages.get(0).get("jobId").asLong());
		assertThat(carry.get("partNo").asInt()).isEqualTo(1);
		assertThat(carry.get("isLast").asBoolean()).isTrue();
		assertThat(carry.get("sentences")).hasSize(1);
		assertThat(carry.get("sentences").get(0).get("text").asString()).isEqualTo("넘길 글");

		// 녹음은 닫지 않는다. 결과가 오면 넘겨 둔 글을 모두 지운다.
		result(recordingId, carry.get("jobId").asLong(), 1, "3500", CARD).andExpect(status().isNoContent());
		assertThat(part(recordingId, 1).getSentences()).isNull();
		assertThat(part(recordingId, 1).getCarryFromMs()).isNull();
		assertThat(recordingStatus(recordingId)).isEqualTo("DETECTING");

		// 그 뒤에 온 다음 묶음은 넘겨받을 것 없이 처리한다.
		upload("105", id, 2, true, "[{\"startMs\":3700000,\"endMs\":3701000,\"text\":\"늦게 온 글\"}]");
		JsonNode next = messagesFor(recordingId).get(2);
		assertThat(next.get("partNo").asInt()).isEqualTo(2);
		assertThat(next.get("sentences")).hasSize(1);
		detectResults.dispatchLateCarries(OffsetDateTime.now().plusMinutes(1));
		assertThat(messagesFor(recordingId)).hasSize(3);
	}

	@Test
	void 넘겨_둔_시각_뒤에_글이_없으면_늦어도_큐에_넣지_않는다() throws Exception {
		String id = UUID.randomUUID().toString();
		long recordingId = upload("108", id, 1, false, TWO);
		// 마지막 글(3000~4000ms)보다 뒤를 넘겨 달라고 함
		result(recordingId, jobId(recordingId, 0), 1, "9000", CARD).andExpect(status().isNoContent());

		detectResults.dispatchLateCarries(OffsetDateTime.now().plusMinutes(1));
		assertThat(messagesFor(recordingId)).hasSize(1);
		assertThat(part(recordingId, 1).getCarryFromMs()).isNull();
		assertThat(part(recordingId, 1).getStatus()).isEqualTo(RecordingStatus.DONE);
	}

	@Test
	void 넘겨_둔_부분만_처리하던_작업이_실패해도_묶음은_FAILED가_아니다() throws Exception {
		String id = UUID.randomUUID().toString();
		long recordingId = upload("106", id, 1, false, TWO);
		result(recordingId, jobId(recordingId, 0), 1, "3000", CARD).andExpect(status().isNoContent());
		detectResults.dispatchLateCarries(OffsetDateTime.now().plusMinutes(1));

		fail(jobId(recordingId, 1)).andExpect(status().isNoContent());
		assertThat(part(recordingId, 1).getStatus()).isEqualTo(RecordingStatus.DONE);
		assertThat(part(recordingId, 1).getSentences()).isNull();
		assertThat(part(recordingId, 1).getCarryFromMs()).isNull();
	}

	@Test
	void 결과가_명세와_다르면_400과_틀린_칸이다() throws Exception {
		long recordingId = upload("107", UUID.randomUUID().toString(), 1, false, TWO);
		long jobId = jobId(recordingId, 0);

		result(recordingId, jobId, 1, "null", CARD.replace("[\"화자1\",\"화자2\"]", "[\"화자2\",\"화자1\"]"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("cards[0].speakers[0]"));
		result(recordingId, jobId, 1, "null", CARD.replace("\"speaker\":\"화자2\"", "\"speaker\":\"화자3\""))
			.andExpect(jsonPath("$.field").value("cards[0].lines[1].speaker"));
		result(recordingId, jobId, 1, "null", CARD.replace("\"kind\":\"웃김\"", "\"kind\":\"슬픔\""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("cards[0].kind"));
		result(recordingId, jobId, 2, "null", CARD).andExpect(jsonPath("$.field").value("partNo"));
		result(recordingId, 999_999, 1, "null", CARD).andExpect(jsonPath("$.field").value("jobId"));
		result(999_999, jobId, 1, "null", CARD).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("RECORDING_NOT_FOUND"));
		fail(999_999).andExpect(status().isBadRequest());

		// 틀린 요청은 아무것도 바꾸지 않는다.
		assertThat(cards.findByRecordingIdOrderById(recordingId)).isEmpty();
		assertThat(part(recordingId, 1).getStatus()).isEqualTo(RecordingStatus.DETECTING);
	}

}
