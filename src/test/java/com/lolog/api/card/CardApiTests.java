package com.lolog.api.card;

import com.lolog.api.auth.TestTokens;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

// 카드 목록·자세히·고치기·지우기를 실제 PostgreSQL로 확인한다. Docker가 켜져 있어야 한다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CardApiTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Container
	@ServiceConnection(name = "redis")
	@SuppressWarnings("resource")
	static GenericContainer<?> redis = new GenericContainer<>("redis:latest").withExposedPorts(6379);

	// 테스트마다 다른 회원을 써서 서로의 카드가 섞이지 않게 한다.
	private static final AtomicLong USERS = new AtomicLong(1000);

	@Autowired
	MockMvc mvc;

	@Autowired
	CardRepository cards;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	TransactionTemplate tx;

	private long card(long userId, String summary) {
		Card card = new Card(null, userId, "웃김", summary, 1000, 9000, OffsetDateTime.parse("2026-11-10T12:30:01+09:00"));
		card.addSpeaker("화자1");
		card.addSpeaker("화자2");
		card.addLine(1, "화자1", "양념 반 후라이드 반", 1000L, 1500L);
		card.addLine(2, "화자2", "또 반반이야?", null, null);
		return cards.save(card).getId();
	}

	private long lineId(long cardId, int lineNo) {
		return jdbc.queryForObject("select id from card_lines where card_id = ? and line_no = ?", Long.class, cardId,
				lineNo);
	}

	private long comic(long cardId, long userId, String status) {
		return jdbc.queryForObject(
				"insert into comics (card_id, user_id, status, card_summary) values (?, ?, ?, '요약') returning id",
				Long.class, cardId, userId, status);
	}

	private ResultActions patchCard(long userId, long cardId, String json) throws Exception {
		return mvc.perform(patch("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(userId)))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json));
	}

	@Test
	void 목록은_내_카드만_최신순으로_나눠_준다() throws Exception {
		long me = USERS.incrementAndGet();
		long other = USERS.incrementAndGet();
		long first = card(me, "첫 카드");
		long second = card(me, "둘째 카드");
		long third = card(me, "셋째 카드");
		card(other, "남의 카드");
		comic(third, me, "DONE");
		comic(third, me, "FAILED");
		comic(third, me, "DRAWING");

		String cursor = mvc.perform(get("/api/cards?size=2").header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cards.length()").value(2))
			.andExpect(jsonPath("$.cards[0].id").value(third))
			.andExpect(jsonPath("$.cards[0].lineCount").value(2))
			.andExpect(jsonPath("$.cards[0].comicCount").value(2))
			.andExpect(jsonPath("$.cards[0].place").isEmpty())
			.andExpect(jsonPath("$.cards[0].recordedAt").exists())
			.andExpect(jsonPath("$.cards[1].id").value(second))
			.andExpect(jsonPath("$.nextCursor").isString())
			.andReturn().getResponse().getContentAsString().replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

		mvc.perform(get("/api/cards?size=2&cursor=" + cursor).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(jsonPath("$.cards.length()").value(1))
			.andExpect(jsonPath("$.cards[0].id").value(first))
			.andExpect(jsonPath("$.nextCursor").isEmpty());

		mvc.perform(get("/api/cards?size=51").header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("size"));
		mvc.perform(get("/api/cards?cursor=abc").header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("cursor"));
	}

	@Test
	void sort가_oldest면_오래된순으로_나눠_준다() throws Exception {
		long me = USERS.incrementAndGet();
		long first = card(me, "첫 카드");
		long second = card(me, "둘째 카드");
		long third = card(me, "셋째 카드");

		String cursor = mvc.perform(get("/api/cards?sort=oldest&size=2").header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.cards.length()").value(2))
			.andExpect(jsonPath("$.cards[0].id").value(first))
			.andExpect(jsonPath("$.cards[1].id").value(second))
			.andExpect(jsonPath("$.nextCursor").isString())
			.andReturn().getResponse().getContentAsString().replaceAll(".*\"nextCursor\":\"([^\"]+)\".*", "$1");

		mvc.perform(get("/api/cards?sort=oldest&size=2&cursor=" + cursor).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(jsonPath("$.cards.length()").value(1))
			.andExpect(jsonPath("$.cards[0].id").value(third))
			.andExpect(jsonPath("$.nextCursor").isEmpty());

		mvc.perform(get("/api/cards?sort=latest&size=1").header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(jsonPath("$.cards[0].id").value(third));
		mvc.perform(get("/api/cards?sort=popular").header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("sort"));
	}

	@Test
	void 자세히는_화자_대사_만화와_남은_횟수를_준다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me, "치킨 주문하다 생긴 일");
		long older = comic(cardId, me, "DONE");
		long newer = comic(cardId, me, "FAILED");
		jdbc.update("update cards set comic_count = 1 where id = ?", cardId);

		mvc.perform(get("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.recordingId").isEmpty())
			.andExpect(jsonPath("$.speakers[0].key").value("화자1"))
			.andExpect(jsonPath("$.speakers[0].name").isEmpty())
			.andExpect(jsonPath("$.lines[1].speaker").value("화자2"))
			.andExpect(jsonPath("$.lines[1].text").value("또 반반이야?"))
			.andExpect(jsonPath("$.comics[0].id").value(newer))
			.andExpect(jsonPath("$.comics[1].id").value(older))
			.andExpect(jsonPath("$.comics[1].status").value("DONE"))
			.andExpect(jsonPath("$.comicRemaining").value(2));

		mvc.perform(get("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(USERS.incrementAndGet()))))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CARD_NOT_FOUND"));
	}

	@Test
	void 고치기는_보낸_것만_바꾸고_공백을_뺀다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me, "카드");
		long line2 = lineId(cardId, 2);

		patchCard(me, cardId, """
				{"speakers":[{"key":"화자1","name":"  민수 "}],"lines":[{"id":%d,"speaker":"화자1","text":" 반반 무많이 "}],"place":" 치킨집 "}
				""".formatted(line2))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.speakers[0].name").value("민수"))
			.andExpect(jsonPath("$.speakers[1].name").isEmpty())
			.andExpect(jsonPath("$.lines[0].text").value("양념 반 후라이드 반"))
			.andExpect(jsonPath("$.lines[1].speaker").value("화자1"))
			.andExpect(jsonPath("$.lines[1].text").value("반반 무많이"))
			.andExpect(jsonPath("$.place").value("치킨집"));

		// 빠진 칸은 그대로, null은 지움
		patchCard(me, cardId, "{\"place\":null}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.place").isEmpty())
			.andExpect(jsonPath("$.speakers[0].name").value("민수"));
		patchCard(me, cardId, "{\"speakers\":[{\"key\":\"화자1\",\"name\":null}]}")
			.andExpect(jsonPath("$.speakers[0].name").isEmpty());
	}

	@Test
	void 고치기_규칙을_어기면_400이고_아무것도_바꾸지_않는다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me, "카드");
		long line1 = lineId(cardId, 1);
		long otherLine = lineId(card(me, "다른 카드"), 1);

		patchCard(me, cardId, "{}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		patchCard(me, cardId, "{\"speakers\":[],\"lines\":[]}").andExpect(status().isBadRequest());
		patchCard(me, cardId, "{\"speakers\":[{\"key\":\"화자3\",\"name\":\"민수\"}]}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNKNOWN_SPEAKER"))
			.andExpect(jsonPath("$.field").value("speakers[0].key"));
		patchCard(me, cardId, "{\"lines\":[{\"id\":%d,\"speaker\":\"화자9\"}]}".formatted(line1))
			.andExpect(jsonPath("$.code").value("UNKNOWN_SPEAKER"))
			.andExpect(jsonPath("$.field").value("lines[0].speaker"));
		patchCard(me, cardId, "{\"lines\":[{\"id\":%d,\"text\":\"남의 대사\"}]}".formatted(otherLine))
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("lines[0].id"));
		patchCard(me, cardId, "{\"speakers\":[{\"key\":\"화자1\",\"name\":\"가나다라마바사아자차카\"}]}")
			.andExpect(jsonPath("$.field").value("speakers[0].name"));
		patchCard(me, cardId, "{\"speakers\":[{\"key\":\"화자1\",\"name\":\"   \"}]}")
			.andExpect(jsonPath("$.field").value("speakers[0].name"));
		// name과 character가 둘 다 빠지면 그 화자 항목이 틀린 것(characters.md SpeakerUpdate)
		patchCard(me, cardId, "{\"speakers\":[{\"key\":\"화자1\"}]}")
			.andExpect(jsonPath("$.field").value("speakers[0]"));
		patchCard(me, cardId, "{\"place\":\"" + "가".repeat(21) + "\"}").andExpect(jsonPath("$.field").value("place"));
		patchCard(me, cardId, "{\"lines\":[{\"id\":%d,\"text\":\"%s\"}]}".formatted(line1, "가".repeat(201)))
			.andExpect(jsonPath("$.field").value("lines[0].text"));
		patchCard(me, cardId, "{\"lines\":[{\"id\":%d,\"text\":\"하나\"},{\"id\":%d,\"text\":\"둘\"}]}".formatted(line1, line1))
			.andExpect(jsonPath("$.field").value("lines[1].id"));
		// 앞의 화자는 맞고 뒤의 장소가 틀려도 화자 이름은 바뀌지 않는다.
		patchCard(me, cardId, "{\"speakers\":[{\"key\":\"화자1\",\"name\":\"민수\"}],\"place\":\" \"}")
			.andExpect(jsonPath("$.field").value("place"));

		mvc.perform(get("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(jsonPath("$.speakers[0].name").isEmpty())
			.andExpect(jsonPath("$.lines[0].text").value("양념 반 후라이드 반"))
			.andExpect(jsonPath("$.place").isEmpty());
	}

	@Test
	void 고치지_않은_긴_대사는_그대로_둔다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me, "카드");
		String longText = "가".repeat(250);
		jdbc.update("update card_lines set text = ? where card_id = ? and line_no = 2", longText, cardId);

		patchCard(me, cardId, "{\"lines\":[{\"id\":%d,\"speaker\":\"화자1\"}]}".formatted(lineId(cardId, 2)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.lines[1].text").value(longText));
	}

	@Test
	void 만화를_만드는_중이면_고치기와_지우기를_막는다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me, "카드");
		long comicId = comic(cardId, me, "STORYBOARD");

		patchCard(me, cardId, "{\"place\":\"집\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CARD_LOCKED"));
		mvc.perform(delete("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CARD_LOCKED"));

		jdbc.update("update comics set status = 'DONE' where id = ?", comicId);
		patchCard(me, cardId, "{\"place\":\"집\"}").andExpect(status().isOk());
	}

	@Test
	void 카드를_지워도_만화는_남는다() throws Exception {
		long me = USERS.incrementAndGet();
		long cardId = card(me, "카드");
		long comicId = comic(cardId, me, "DONE");

		mvc.perform(delete("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(USERS.incrementAndGet()))))
			.andExpect(status().isNotFound());
		mvc.perform(delete("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isNoContent());

		assertThat(cards.findById(cardId)).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from card_lines where card_id = ?", Long.class, cardId)).isZero();
		assertThat(jdbc.queryForObject("select card_id from comics where id = ?", Long.class, comicId)).isNull();
		assertThat(jdbc.queryForObject("select card_summary from comics where id = ?", String.class, comicId))
			.isEqualTo("요약");
		mvc.perform(get("/api/cards/" + cardId).header("Authorization", TestTokens.bearer(String.valueOf(me))))
			.andExpect(status().isNotFound());
	}

	@Test
	void V2는_만든_횟수를_0에서_3까지만_허용한다() {
		long me = USERS.incrementAndGet();
		long cardId = card(me, "카드");
		assertThat(cards.findById(cardId).orElseThrow().getComicCount()).isZero();
		org.assertj.core.api.Assertions
			.assertThatThrownBy(() -> jdbc.update("update cards set comic_count = 4 where id = ?", cardId))
			.hasMessageContaining("ck_cards_comic_count");
	}

}
