package com.lolog.api.admin;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import java.util.List;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.data.redis.core.StringRedisTemplate;
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

// 관리자 API와 의견 보내기(#57, admin.md 초안)를 실제 PostgreSQL·Redis로 확인한다. Docker가 켜져 있어야 한다.
// 탈퇴할 때 저장소 파일도 지우므로 SeaweedFS를 같이 띄운다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminApiTests {

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

	private static final AtomicLong USERS = new AtomicLong(41000);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Autowired
	ObjectMapper objectMapper;

	private long user(String nickname) throws Exception {
		long id = USERS.incrementAndGet();
		call(patch("/api/me/profile").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"" + nickname + "\"}"),
				id).andExpect(status().isOk());
		return id;
	}

	private ResultActions call(MockHttpServletRequestBuilder request, long userId) throws Exception {
		return mvc.perform(request.header("Authorization", TestTokens.bearer(userId)));
	}

	private ResultActions admin(MockHttpServletRequestBuilder request) throws Exception {
		return mvc.perform(request.header("Authorization", TestTokens.adminBearer(1)));
	}

	private ResultActions json(MockHttpServletRequestBuilder request, String body, boolean asAdmin, long userId)
			throws Exception {
		MockHttpServletRequestBuilder r = request.contentType(MediaType.APPLICATION_JSON).content(body);
		return asAdmin ? admin(r) : call(r, userId);
	}

	private JsonNode read(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private ResultActions feedback(long me, String category, String text) throws Exception {
		return json(post("/api/feedback"), """
				{"category":"%s","text":"%s","appVersion":"1.2.0","deviceModel":"SM-S918N"}
				""".formatted(category, text), false, me);
	}

	@Test
	void 관리자가_아니면_관리자_주소는_403이다() throws Exception {
		long me = user("나");
		json(post("/api/admin/notices"), "{\"title\":\"t\",\"body\":\"b\"}", false, me).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		call(get("/api/admin/feedback"), me).andExpect(status().isForbidden());
		call(get("/api/admin/users/" + me), me).andExpect(status().isForbidden());
		mvc.perform(get("/api/admin/feedback")).andExpect(status().isUnauthorized());
	}

	@Test
	void 관리자는_공지사항을_쓰고_고치고_지운다() throws Exception {
		long me = user("나");
		JsonNode notice = read(json(post("/api/admin/notices"), "{\"title\":\" 점검 안내 \",\"body\":\"밤 10시\"}", true, 0)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("점검 안내"))
			.andExpect(jsonPath("$.createdAt").isNotEmpty()));
		long id = notice.get("id").asLong();
		json(post("/api/admin/notices"), "{\"title\":\" \",\"body\":\"b\"}", true, 0).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("title"));
		json(post("/api/admin/notices"), "{\"title\":\"" + "가".repeat(101) + "\",\"body\":\"b\"}", true, 0)
			.andExpect(status().isBadRequest());
		json(post("/api/admin/notices"), "{\"title\":\"t\"}", true, 0).andExpect(jsonPath("$.field").value("body"));

		json(patch("/api/admin/notices/" + id), "{\"body\":\"밤 11시\"}", true, 0).andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("점검 안내"))
			.andExpect(jsonPath("$.body").value("밤 11시"));
		json(patch("/api/admin/notices/999999"), "{\"body\":\"x\"}", true, 0).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOTICE_NOT_FOUND"));
		// 사용자의 공지사항 탭에 보인다
		call(get("/api/inbox").param("tab", "NOTICE"), me).andExpect(jsonPath("$.items[0].notice.body").value("밤 11시"));

		admin(delete("/api/admin/notices/" + id)).andExpect(status().isNoContent());
		admin(delete("/api/admin/notices/" + id)).andExpect(status().isNotFound());
	}

	@Test
	void 의견은_하루_10번까지_보내고_관리자가_목록을_보고_처리한다() throws Exception {
		jdbc.update("delete from feedback");
		long me = user("나");
		feedback(me, "BUG", "  녹음이 멈춰요 ").andExpect(status().isCreated())
			.andExpect(jsonPath("$.userId").value(me))
			.andExpect(jsonPath("$.text").value("녹음이 멈춰요"))
			.andExpect(jsonPath("$.status").value("OPEN"));
		feedback(me, "NOPE", "x").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("category"));
		feedback(me, "OPINION", " ").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("text"));
		json(post("/api/feedback"), "{\"category\":\"BUG\",\"text\":\"x\"}", false, me)
			.andExpect(jsonPath("$.field").value("appVersion"));
		for (int i = 2; i <= 10; i++) {
			feedback(me, "OPINION", "의견 " + i).andExpect(status().isCreated());
		}
		feedback(me, "OPINION", "11번째").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
		long other = user("남");
		for (int i = 1; i <= 15; i++) {
			jdbc.update("insert into feedback (user_id, category, text, app_version, device_model) values (?, 'BUG', ?, '1', 'x')",
					other, "버그 " + i);
		}

		JsonNode page = read(admin(get("/api/admin/feedback")).andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(20))
			.andExpect(jsonPath("$.items[0].text").value("버그 15")));
		admin(get("/api/admin/feedback").param("cursor", page.get("nextCursor").asString()))
			.andExpect(jsonPath("$.items.length()").value(5))
			.andExpect(jsonPath("$.items[4].text").value("녹음이 멈춰요"))
			.andExpect(jsonPath("$.nextCursor").isEmpty());
		long first = read(admin(get("/api/admin/feedback").param("cursor", page.get("nextCursor").asString())))
			.get("items").get(4).get("id").asLong();

		json(patch("/api/admin/feedback/" + first), "{\"status\":\"DONE\"}", true, 0).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("DONE"));
		json(patch("/api/admin/feedback/" + first), "{\"status\":\"X\"}", true, 0).andExpect(status().isBadRequest());
		json(patch("/api/admin/feedback/999999"), "{\"status\":\"DONE\"}", true, 0).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("FEEDBACK_NOT_FOUND"));
		admin(get("/api/admin/feedback").param("status", "DONE")).andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].id").value(first));
		admin(get("/api/admin/feedback").param("status", "X")).andExpect(status().isBadRequest());

		// 탈퇴해도 글은 남고 보낸 사람만 비워진다
		mvc.perform(delete("/internal/users/" + me).header("X-Internal-Token", "internal-test-token"))
			.andExpect(status().isNoContent());
		admin(get("/api/admin/feedback").param("status", "DONE")).andExpect(jsonPath("$.items[0].userId").isEmpty())
			.andExpect(jsonPath("$.items[0].text").value("녹음이 멈춰요"))
			.andExpect(jsonPath("$.items[0].deviceModel").value("SM-S918N"));
	}

	@Test
	void 관리자는_회원의_닉네임과_친구_코드를_본다() throws Exception {
		long me = user("나");
		admin(get("/api/admin/users/" + me)).andExpect(status().isOk()).andExpect(jsonPath("$.nickname").value("나"))
			.andExpect(jsonPath("$.friendCode").isNotEmpty());
		admin(get("/api/admin/users/999999999")).andExpect(jsonPath("$.nickname").isEmpty())
			.andExpect(jsonPath("$.friendCode").isEmpty());
	}

	@Test
	void 정지되면_토큰이_막히고_방_멤버에_정지된_계정으로_보인다() throws Exception {
		long a = user("준");
		long b = user("서연");
		jdbc.update("insert into friendships (requester_id, addressee_id, status) values (?, ?, 'ACCEPTED')", a, b);
		long roomId = read(json(post("/api/rooms"), "{\"name\":\"방\",\"memberIds\":[" + b + "]}", false, a)).get("id")
			.asLong();
		long inviteId = read(call(get("/api/room-invites"), b)).get("invites").get(0).get("id").asLong();
		call(post("/api/room-invites/" + inviteId + "/accept"), b).andExpect(status().isOk());

		mvc.perform(put("/internal/users/" + b + "/suspension").header("X-Internal-Token", "internal-test-token")
			.contentType(MediaType.APPLICATION_JSON).content("{\"suspendedUntil\":null}")).andExpect(status().isNoContent());
		call(get("/api/rooms/" + roomId), a).andExpect(jsonPath("$.members[1].suspended").value(true))
			.andExpect(jsonPath("$.members[0].suspended").value(false));
		// 기한이 지나면 정지 아님
		mvc.perform(put("/internal/users/" + b + "/suspension").header("X-Internal-Token", "internal-test-token")
			.contentType(MediaType.APPLICATION_JSON).content("{\"suspendedUntil\":\"2020-01-01T00:00:00Z\"}"))
			.andExpect(status().isNoContent());
		call(get("/api/rooms/" + roomId), a).andExpect(jsonPath("$.members[1].suspended").value(false));
		mvc.perform(put("/internal/users/" + b + "/suspension").header("X-Internal-Token", "internal-test-token")
			.contentType(MediaType.APPLICATION_JSON).content("{\"suspendedUntil\":\"2099-01-01T00:00:00Z\"}"))
			.andExpect(status().isNoContent());
		call(get("/api/rooms/" + roomId), a).andExpect(jsonPath("$.members[1].suspended").value(true));
		mvc.perform(delete("/internal/users/" + b + "/suspension").header("X-Internal-Token", "internal-test-token"))
			.andExpect(status().isNoContent());
		call(get("/api/rooms/" + roomId), a).andExpect(jsonPath("$.members[1].suspended").value(false));
		mvc.perform(delete("/internal/users/" + b + "/suspension")).andExpect(status().isUnauthorized());

		// 인증 서비스가 남긴 정지 키가 있으면 받은 토큰도 401
		redisTemplate.opsForValue().set("lolog:auth:suspended:" + b, "1");
		call(get("/api/rooms"), b).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
		redisTemplate.delete("lolog:auth:suspended:" + b);
		call(get("/api/rooms"), b).andExpect(status().isOk());
	}

}
