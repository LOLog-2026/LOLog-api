package com.lolog.api.inbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import java.time.OffsetDateTime;
import java.util.List;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 알림 목록·공지사항·새 소식 표시(#56, inbox.md 초안)를 실제 PostgreSQL·Redis로 확인한다. Docker가 켜져 있어야 한다.
// 탈퇴할 때 저장소 파일도 지우므로 SeaweedFS를 같이 띄운다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class InboxApiTests {

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

	private static final AtomicLong USERS = new AtomicLong(31000);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	private long user(String nickname) throws Exception {
		long id = USERS.incrementAndGet();
		call(patch("/api/me/profile").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"" + nickname + "\"}"),
				id).andExpect(status().isOk());
		return id;
	}

	private String code(long userId) throws Exception {
		return json(call(get("/api/me/profile"), userId)).get("friendCode").asString();
	}

	private ResultActions call(MockHttpServletRequestBuilder request, long userId) throws Exception {
		return mvc.perform(request.header("Authorization", TestTokens.bearer(userId)));
	}

	private JsonNode json(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private long notice(String title, String at) {
		return jdbc.queryForObject("insert into notices (title, body, created_at) values (?, '본문', ?::timestamptz) returning id",
				Long.class, title, at);
	}

	private void notification(long userId, String type, String at) {
		jdbc.update("insert into notifications (user_id, type, data, created_at) values (?, ?, '{}'::jsonb, ?::timestamptz)",
				userId, type, at);
	}

	@Test
	void 푸시를_보내면_알림_목록에_남고_공지사항과_섞여_보인다() throws Exception {
		jdbc.update("delete from notices");
		long me = user("나");
		long friend = user("준");
		long noticeId = notice("점검 안내", "2026-01-01T00:00:00Z");
		// 푸시 키가 없어도 남는다
		call(post("/api/friend-requests").contentType(MediaType.APPLICATION_JSON)
			.content("{\"friendCode\":\"" + code(me) + "\"}"), friend).andExpect(status().isCreated());

		JsonNode all = json(call(get("/api/inbox"), me).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].kind").value("NOTIFICATION"))
			.andExpect(jsonPath("$.items[0].read").value(false))
			.andExpect(jsonPath("$.items[0].notice").isEmpty())
			.andExpect(jsonPath("$.items[0].notification.type").value("FRIEND_REQUEST"))
			.andExpect(jsonPath("$.items[0].notification.data.nickname").value("준"))
			.andExpect(jsonPath("$.items[0].notification.data.type").doesNotExist())
			.andExpect(jsonPath("$.items[1].kind").value("NOTICE"))
			.andExpect(jsonPath("$.items[1].id").value(noticeId))
			.andExpect(jsonPath("$.items[1].notice.title").value("점검 안내"))
			.andExpect(jsonPath("$.nextCursor").isEmpty()));
		long notificationId = all.get("items").get(0).get("id").asLong();
		call(get("/api/inbox").param("tab", "NOTICE"), me).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].kind").value("NOTICE"));
		call(get("/api/inbox").param("tab", "NOTIFICATION"), me).andExpect(jsonPath("$.items.length()").value(1));
		call(get("/api/inbox").param("tab", "X"), me).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("tab"));
		// 공지사항은 모든 사용자에게 보인다
		call(get("/api/inbox"), friend).andExpect(jsonPath("$.items.length()").value(1));

		call(get("/api/inbox/unread"), me).andExpect(jsonPath("$.hasUnread").value(true));
		call(post("/api/inbox/notification/" + notificationId + "/read"), me).andExpect(status().isNoContent());
		call(post("/api/inbox/notification/" + notificationId + "/read"), friend).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
		call(post("/api/inbox/notice/999999/read"), me).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOTICE_NOT_FOUND"));
		call(post("/api/inbox/x/1/read"), me).andExpect(status().isBadRequest());
		call(get("/api/inbox"), me).andExpect(jsonPath("$.items[0].read").value(true))
			.andExpect(jsonPath("$.items[1].read").value(false));
		call(get("/api/inbox/unread"), me).andExpect(jsonPath("$.hasUnread").value(true));
		call(post("/api/inbox/notice/" + noticeId + "/read"), me).andExpect(status().isNoContent());
		call(get("/api/inbox/unread"), me).andExpect(jsonPath("$.hasUnread").value(false));

		// 모두 읽음
		notice("새 공지", "2026-02-01T00:00:00Z");
		call(get("/api/inbox/unread"), friend).andExpect(jsonPath("$.hasUnread").value(true));
		call(post("/api/inbox/read"), friend).andExpect(status().isNoContent());
		call(get("/api/inbox/unread"), friend).andExpect(jsonPath("$.hasUnread").value(false));

		// 탈퇴하면 알림과 읽음 기록을 지운다
		mvc.perform(delete("/internal/users/" + me).header("X-Internal-Token", "internal-test-token"))
			.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ?", Long.class, me)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from notice_reads where user_id = ?", Long.class, me)).isZero();
	}

	@Test
	void 목록은_20개씩_커서로_나눠_주고_30일_지난_알림은_빼다() throws Exception {
		long me = USERS.incrementAndGet();
		jdbc.update("delete from notices");
		for (int i = 0; i < 25; i++) {
			notification(me, "CARDS_CREATED", OffsetDateTime.now().minusMinutes(i).toString());
		}
		notification(me, "COMIC_DONE", OffsetDateTime.now().minusDays(31).toString());
		JsonNode first = json(call(get("/api/inbox"), me).andExpect(jsonPath("$.items.length()").value(20)));
		String cursor = first.get("nextCursor").asString();
		call(get("/api/inbox").param("cursor", cursor), me).andExpect(jsonPath("$.items.length()").value(5))
			.andExpect(jsonPath("$.nextCursor").isEmpty());
		call(get("/api/inbox").param("cursor", "nope"), me).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("cursor"));

		new NotificationCleanupJob(jdbc).run();
		assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ?", Long.class, me)).isEqualTo(25);
	}

	@Test
	void 새_소식_표시는_화면을_열면_사라진다() throws Exception {
		jdbc.update("delete from notices");
		long me = user("나");
		long friend = user("준");
		call(get("/api/me/badges"), me).andExpect(jsonPath("$.cards").value(false))
			.andExpect(jsonPath("$.friends").value(false))
			.andExpect(jsonPath("$.rooms").value(false))
			.andExpect(jsonPath("$.inbox").value(false));

		notification(me, "CARDS_CREATED", OffsetDateTime.now().toString());
		notification(me, "COMIC_FAILED", OffsetDateTime.now().toString());
		notification(me, "ROOM_DISBANDED", OffsetDateTime.now().toString());
		call(post("/api/friend-requests").contentType(MediaType.APPLICATION_JSON)
			.content("{\"friendCode\":\"" + code(me) + "\"}"), friend).andExpect(status().isCreated());
		call(get("/api/me/badges"), me).andExpect(jsonPath("$.cards").value(true))
			.andExpect(jsonPath("$.comics").value(true))
			.andExpect(jsonPath("$.profileCharacter").value(false))
			.andExpect(jsonPath("$.friends").value(true))
			.andExpect(jsonPath("$.rooms").value(true))
			.andExpect(jsonPath("$.inbox").value(true));

		call(post("/api/me/badges/cards/read"), me).andExpect(status().isNoContent());
		call(post("/api/me/badges/rooms/read"), me).andExpect(status().isNoContent());
		call(post("/api/me/badges/friends/read"), me).andExpect(status().isNoContent());
		call(post("/api/me/badges/nope/read"), me).andExpect(status().isBadRequest());
		// 친구는 받은 요청이 남아 있는 동안 보인다
		call(get("/api/me/badges"), me).andExpect(jsonPath("$.cards").value(false))
			.andExpect(jsonPath("$.comics").value(true))
			.andExpect(jsonPath("$.rooms").value(false))
			.andExpect(jsonPath("$.friends").value(true));
		call(post("/api/inbox/read"), me).andExpect(status().isNoContent());
		call(get("/api/me/badges"), me).andExpect(jsonPath("$.inbox").value(false));
	}

}
