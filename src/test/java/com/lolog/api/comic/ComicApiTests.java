package com.lolog.api.comic;

import com.lolog.api.auth.TestTokens;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.card.Card;
import com.lolog.api.card.CardRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 만화 요청과 만화 작업 실패를 실제 PostgreSQL·Redis로 확인한다. Docker가 켜져 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ComicApiTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Container
	@ServiceConnection(name = "redis")
	@SuppressWarnings("resource")
	static GenericContainer<?> redis = new GenericContainer<>("redis:latest").withExposedPorts(6379);

	private static final AtomicLong USERS = new AtomicLong(2000);

	@Autowired
	MockMvc mvc;

	@Autowired
	CardRepository cards;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Autowired
	ObjectMapper objectMapper;

	private long card(long userId) {
		Card card = new Card(null, userId, "웃김", "치킨 주문하다 생긴 일", 1000, 9000,
				OffsetDateTime.parse("2026-11-10T12:30:01+09:00"));
		card.addSpeaker("화자1");
		card.addLine(1, "화자1", "양념 반 후라이드 반", null, null);
		return cards.save(card).getId();
	}

	private ResultActions request(long userId, long cardId) throws Exception {
		return mvc.perform(post("/api/cards/" + cardId + "/comics").header("Authorization", TestTokens.bearer(String.valueOf(userId))));
	}

	private long comicId(long userId, long cardId) throws Exception {
		String body = request(userId, cardId).andExpect(status().isAccepted()).andReturn().getResponse()
			.getContentAsString();
		return objectMapper.readTree(body).get("comicId").asLong();
	}

	private List<JsonNode> messagesFor(long cardId) {
		return redisTemplate.opsForStream().range(ComicPublisher.STREAM, Range.unbounded()).stream()
			.map(r -> objectMapper.readTree((String) r.getValue().get("payload")))
			.filter(n -> n.get("cardId").asLong() == cardId)
			.toList();
	}

	private long jobOf(long comicId) {
		return jdbc.queryForObject("select id from jobs where comic_id = ?", Long.class, comicId);
	}

	private ResultActions fail(long jobId) throws Exception {
		return mvc.perform(post("/internal/jobs/" + jobId + "/fail").header("X-Internal-Token", "internal-test-token")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"콘티 검사 실패\"}"));
	}

	private int comicCount(long cardId) {
		return jdbc.queryForObject("select comic_count from cards where id = ?", Integer.class, cardId);
	}

	private void finish(long comicId) {
		jdbc.update("update comics set status = 'DONE' where id = ?", comicId);
	}

	@Test
	void 요청하면_만화와_작업을_만들고_큐에_넣는다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me);

		request(me, cardId).andExpect(status().isAccepted())
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$.comicId").isNumber())
			.andExpect(jsonPath("$.status").value("QUEUED"));

		long comicId = jdbc.queryForObject("select id from comics where card_id = ?", Long.class, cardId);
		assertThat(jdbc.queryForObject("select card_summary from comics where id = ?", String.class, comicId))
			.isEqualTo("치킨 주문하다 생긴 일");
		assertThat(comicCount(cardId)).isEqualTo(1);

		List<JsonNode> messages = messagesFor(cardId);
		assertThat(messages).hasSize(1);
		assertThat(messages.getFirst().get("jobId").asLong()).isEqualTo(jobOf(comicId));
		assertThat(messages.getFirst().get("comicId").asLong()).isEqualTo(comicId);
		assertThat(messages.getFirst().size()).isEqualTo(3);

		mvc.perform(get("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(jsonPath("$.comicRemaining").value(2))
			.andExpect(jsonPath("$.comics[0].status").value("QUEUED"));
		mvc.perform(post("/api/cards/" + cardId + "/comics").header("Authorization", TestTokens.bearer(String.valueOf(USERS.incrementAndGet()))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CARD_NOT_FOUND"));
	}

	@Test
	void 만드는_중이면_409_COMIC_IN_PROGRESS이고_횟수는_그대로다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me);
		comicId(me, cardId);

		request(me, cardId).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("COMIC_IN_PROGRESS"));
		assertThat(comicCount(cardId)).isEqualTo(1);
	}

	@Test
	void 세_번_만들면_409_COMIC_LIMIT이고_지워도_돌아오지_않는다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me);
		for (int i = 0; i < 3; i++) {
			finish(comicId(me, cardId));
		}
		request(me, cardId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("COMIC_LIMIT"));

		// 만화를 지워도(#7) 횟수는 그대로다. 여기서는 행을 직접 지운다.
		jdbc.update("delete from comics where card_id = ?", cardId);
		request(me, cardId).andExpect(jsonPath("$.code").value("COMIC_LIMIT"));
		assertThat(comicCount(cardId)).isEqualTo(3);
	}

	@Test
	void 세_번째를_만드는_중이면_COMIC_IN_PROGRESS가_먼저다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me);
		finish(comicId(me, cardId));
		finish(comicId(me, cardId));
		comicId(me, cardId);

		request(me, cardId).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("COMIC_IN_PROGRESS"));
	}

	@Test
	void 실패하면_만화는_FAILED가_되고_횟수를_한_번만_돌려준다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me);
		long comicId = comicId(me, cardId);
		long jobId = jobOf(comicId);

		fail(jobId).andExpect(status().isNoContent());
		fail(jobId).andExpect(status().isNoContent());

		assertThat(jdbc.queryForObject("select status from comics where id = ?", String.class, comicId))
			.isEqualTo("FAILED");
		assertThat(jdbc.queryForObject("select fail_reason from comics where id = ?", String.class, comicId))
			.isEqualTo("콘티 검사 실패");
		assertThat(jdbc.queryForObject("select status from jobs where id = ?", String.class, jobId))
			.isEqualTo("FAILED");
		assertThat(comicCount(cardId)).isZero();

		// 실패한 만화는 세지 않으므로 다시 만들 수 있다.
		request(me, cardId).andExpect(status().isAccepted());
		assertThat(comicCount(cardId)).isEqualTo(1);
	}

	@Test
	void 카드를_지운_뒤에_실패해도_204다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me);
		long comicId = comicId(me, cardId);
		finish(comicId);
		mvc.perform(delete("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isNoContent());
		jdbc.update("update comics set status = 'DRAWING' where id = ?", comicId);

		fail(jobOf(comicId)).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select status from comics where id = ?", String.class, comicId))
			.isEqualTo("FAILED");
	}

	@Test
	void 동시에_여러_번_눌러도_만화는_하나만_생긴다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me);
		List<Callable<Integer>> calls = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			calls.add(() -> request(me, cardId).andReturn().getResponse().getStatus());
		}
		List<Integer> statuses = new ArrayList<>();
		try (var pool = Executors.newFixedThreadPool(5)) {
			for (Future<Integer> f : pool.invokeAll(calls)) {
				statuses.add(f.get());
			}
		}
		assertThat(statuses).filteredOn(s -> s == 202).hasSize(1);
		assertThat(statuses).filteredOn(s -> s == 409).hasSize(4);
		assertThat(comicCount(cardId)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from comics where card_id = ?", Long.class, cardId)).isEqualTo(1);
	}

}
