package com.lolog.api.recording;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

// GET /api/recordings(내 녹음 목록)를 실제 PostgreSQL·Redis로 확인한다. Docker가 켜져 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RecordingListApiTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Container
	@ServiceConnection(name = "redis")
	@SuppressWarnings("resource")
	static GenericContainer<?> redis = new GenericContainer<>("redis:latest").withExposedPorts(6379);

	private static final AtomicLong USERS = new AtomicLong(5000);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	private record Uploaded(long recordingId, String clientRecordingId) {
	}

	// 마지막 묶음 하나짜리 녹음을 올린다. 글이 있으면 감지 중(DETECTING), 없으면 바로 DONE이 된다.
	private Uploaded upload(long userId, boolean withText) throws Exception {
		String id = UUID.randomUUID().toString();
		String sentences = withText ? "[{\"startMs\":1000,\"endMs\":2000,\"text\":\"글\"}]" : "[]";
		String body = """
				{"clientRecordingId":"%s","partNo":1,"isLast":true,"startedAt":"2026-11-10T12:30:00+09:00","durationSec":60,"sentences":%s}
				""".formatted(id, sentences);
		String response = mvc.perform(post("/api/recordings").header("Authorization", TestTokens.bearer(userId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
		return new Uploaded(objectMapper.readTree(response).get("recordingId").asLong(), id);
	}

	private void addCard(long userId, long recordingId) {
		jdbc.update("insert into cards (recording_id, user_id, kind, summary, start_ms, end_ms, recorded_at) "
				+ "values (?, ?, '웃김', '요약', 0, 1, now())", recordingId, userId);
	}

	@Test
	void 내_녹음만_최신순으로_나눠_주고_카드_수를_센다() throws Exception {
		long me = USERS.incrementAndGet();
		Uploaded first = upload(me, true);
		Uploaded second = upload(me, false);
		Uploaded third = upload(me, true);
		upload(USERS.incrementAndGet(), true);
		addCard(me, third.recordingId());
		addCard(me, third.recordingId());

		String cursor = mvc.perform(get("/api/recordings?size=2").header("Authorization", TestTokens.bearer(me)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.recordings.length()").value(2))
			.andExpect(jsonPath("$.recordings[0].length()").value(5))
			.andExpect(jsonPath("$.recordings[0].recordingId").value(third.recordingId()))
			.andExpect(jsonPath("$.recordings[0].clientRecordingId").value(third.clientRecordingId()))
			.andExpect(jsonPath("$.recordings[0].status").value("DETECTING"))
			.andExpect(jsonPath("$.recordings[0].startedAt").exists())
			.andExpect(jsonPath("$.recordings[0].cardCount").value(2))
			.andExpect(jsonPath("$.recordings[1].recordingId").value(second.recordingId()))
			.andExpect(jsonPath("$.recordings[1].status").value("DONE"))
			.andExpect(jsonPath("$.recordings[1].cardCount").value(0))
			.andReturn().getResponse().getContentAsString().replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

		mvc.perform(get("/api/recordings?size=2&cursor=" + cursor).header("Authorization", TestTokens.bearer(me)))
			.andExpect(jsonPath("$.recordings.length()").value(1))
			.andExpect(jsonPath("$.recordings[0].recordingId").value(first.recordingId()))
			.andExpect(jsonPath("$.nextCursor").isEmpty());
	}

	@Test
	void status로_분석_중인_녹음만_거를_수_있다() throws Exception {
		long me = USERS.incrementAndGet();
		Uploaded detecting = upload(me, true);
		upload(me, false);
		Uploaded received = upload(me, true);
		jdbc.update("update recordings set status = 'RECEIVED' where id = ?", received.recordingId());

		mvc.perform(get("/api/recordings?status=RECEIVED,DETECTING").header("Authorization", TestTokens.bearer(me)))
			.andExpect(jsonPath("$.recordings.length()").value(2))
			.andExpect(jsonPath("$.recordings[0].recordingId").value(received.recordingId()))
			.andExpect(jsonPath("$.recordings[1].recordingId").value(detecting.recordingId()));
		mvc.perform(get("/api/recordings?status=DONE").header("Authorization", TestTokens.bearer(me)))
			.andExpect(jsonPath("$.recordings.length()").value(1));
		mvc.perform(get("/api/recordings?status=FAILED").header("Authorization", TestTokens.bearer(me)))
			.andExpect(jsonPath("$.recordings.length()").value(0));
		mvc.perform(get("/api/recordings").header("Authorization", TestTokens.bearer(me)))
			.andExpect(jsonPath("$.recordings.length()").value(3));
	}

	@Test
	void 쿼리가_틀리면_400이고_토큰이_없으면_401이다() throws Exception {
		long me = USERS.incrementAndGet();
		mvc.perform(get("/api/recordings?status=RECEIVED,FOO").header("Authorization", TestTokens.bearer(me)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("status"));
		mvc.perform(get("/api/recordings?size=51").header("Authorization", TestTokens.bearer(me)))
			.andExpect(jsonPath("$.field").value("size"));
		mvc.perform(get("/api/recordings?cursor=x").header("Authorization", TestTokens.bearer(me)))
			.andExpect(jsonPath("$.field").value("cursor"));
		mvc.perform(get("/api/recordings")).andExpect(status().isUnauthorized());
	}

}
