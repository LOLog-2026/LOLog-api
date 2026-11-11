package com.lolog.api.friend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import com.lolog.api.notification.PushSender;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 프로필·친구 요청·친구·차단 API(#40, friends.md)를 실제 PostgreSQL·Redis로 확인한다. Docker가 켜져 있어야 한다.
// FCM에는 보내지 않고 PushSender를 가짜로 바꿔 무엇을 보냈는지 본다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class FriendApiTests {

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
	}

	private static final AtomicLong USERS = new AtomicLong(7000);
	private static final long WAIT_MS = 3000;

	@MockitoBean
	PushSender sender;

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@BeforeEach
	void pushOn() {
		when(sender.enabled()).thenReturn(true);
	}

	// 닉네임을 정한 회원. 푸시를 받을 폰 토큰도 하나 둔다.
	private record User(long id, String code, String token) {
	}

	private User user(String nickname) throws Exception {
		long id = USERS.incrementAndGet();
		String token = "fcm-" + UUID.randomUUID();
		jdbc.update("insert into device_tokens (user_id, token) values (?, ?)", id, token);
		JsonNode profile = json(patchNickname(id, nickname).andExpect(status().isOk()));
		return new User(id, profile.get("friendCode").asString(), token);
	}

	private ResultActions patchNickname(long userId, String nickname) throws Exception {
		return mvc.perform(patch("/api/me/profile").header("Authorization", TestTokens.bearer(userId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("nickname", nickname))));
	}

	private ResultActions request(long from, String code) throws Exception {
		return mvc.perform(post("/api/friend-requests").header("Authorization", TestTokens.bearer(from))
			.contentType(MediaType.APPLICATION_JSON)
			.content(objectMapper.writeValueAsString(Map.of("friendCode", code))));
	}

	private ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder,
			long userId) throws Exception {
		return mvc.perform(builder.header("Authorization", TestTokens.bearer(userId)));
	}

	private ResultActions block(long me, long userId) throws Exception {
		return mvc.perform(post("/api/blocks").header("Authorization", TestTokens.bearer(me))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\":" + userId + "}"));
	}

	private JsonNode json(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	private List<Long> friendIds(long userId) throws Exception {
		JsonNode friends = json(call(get("/api/friends"), userId).andExpect(status().isOk())).get("friends");
		return friends.valueStream().map(f -> f.get("userId").asLong()).toList();
	}

	private long requestId(long from, User to) throws Exception {
		return json(request(from, to.code()).andExpect(status().isCreated())).get("id").asLong();
	}

	private void makeFriends(User a, User b) throws Exception {
		long id = requestId(a.id(), b);
		call(post("/api/friend-requests/" + id + "/accept"), b.id()).andExpect(status().isOk());
	}

	@Test
	void 친구_코드는_닉네임을_정한_뒤에_보이고_바뀌지_않는다() throws Exception {
		long id = USERS.incrementAndGet();
		call(get("/api/me/profile"), id).andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(id))
			.andExpect(jsonPath("$.nickname").isEmpty())
			.andExpect(jsonPath("$.friendCode").isEmpty())
			.andExpect(jsonPath("$.character").isEmpty());
		// 서버 안에는 처음 볼 때 발급해 둔다
		String issued = jdbc.queryForObject("select friend_code from user_profiles where user_id = ?", String.class, id);
		assertThat(issued).matches("[ABCDEFGHJKMNPQRSTUVWXYZ2-9]{8}");

		patchNickname(id, "  민수  ").andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(id))
			.andExpect(jsonPath("$.nickname").value("민수"))
			.andExpect(jsonPath("$.friendCode").value(issued));
		patchNickname(id, "지원").andExpect(jsonPath("$.friendCode").value(issued));
		call(get("/api/me/profile"), id).andExpect(jsonPath("$.friendCode").value(issued));
	}

	@Test
	void 닉네임을_정하지_않은_사람의_코드로는_요청할_수_없다() throws Exception {
		User a = user("민수");
		long b = USERS.incrementAndGet();
		call(get("/api/me/profile"), b).andExpect(status().isOk());
		String hidden = jdbc.queryForObject("select friend_code from user_profiles where user_id = ?", String.class, b);

		request(a.id(), hidden).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("FRIEND_CODE_NOT_FOUND"));
		patchNickname(b, "지원").andExpect(status().isOk());
		request(a.id(), hidden).andExpect(status().isCreated());
	}

	@Test
	void 닉네임이_비었거나_10자를_넘으면_400이다() throws Exception {
		long id = USERS.incrementAndGet();
		patchNickname(id, "   ").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("nickname"));
		patchNickname(id, "가나다라마바사아자차카").andExpect(status().isBadRequest());
		patchNickname(id, "가나다라마바사아자차").andExpect(status().isOk());
		mvc.perform(patch("/api/me/profile").header("Authorization", TestTokens.bearer(id))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("nickname"));
	}

	@Test
	void 요청하고_수락하면_서로의_친구_목록에_보인다() throws Exception {
		User a = user("민수");
		User b = user("지원");

		JsonNode sent = json(request(a.id(), b.code().toLowerCase()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.direction").value("SENT"))
			.andExpect(jsonPath("$.status").value("PENDING"))
			.andExpect(jsonPath("$.user.userId").value(b.id()))
			.andExpect(jsonPath("$.user.nickname").value("지원"))
			.andExpect(jsonPath("$.user.friendCode").value(b.code())));
		long id = sent.get("id").asLong();
		verify(sender, timeout(WAIT_MS)).send(eq(List.of(b.token())),
				eq(Map.of("type", "FRIEND_REQUEST", "requestId", String.valueOf(id), "nickname", "민수")));

		call(get("/api/friend-requests?direction=received"), b.id()).andExpect(status().isOk())
			.andExpect(jsonPath("$.requests[0].id").value(id))
			.andExpect(jsonPath("$.requests[0].direction").value("RECEIVED"))
			.andExpect(jsonPath("$.requests[0].user.nickname").value("민수"));
		call(get("/api/friend-requests?direction=sent"), a.id()).andExpect(jsonPath("$.requests[0].id").value(id));
		call(get("/api/friend-requests?direction=received"), a.id()).andExpect(jsonPath("$.requests").isEmpty());

		// 보낸 사람은 수락할 수 없다
		call(post("/api/friend-requests/" + id + "/accept"), a.id()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("FRIEND_REQUEST_NOT_FOUND"));
		call(post("/api/friend-requests/" + id + "/accept"), b.id()).andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(a.id()))
			.andExpect(jsonPath("$.nickname").value("민수"))
			.andExpect(jsonPath("$.friendsSince").isNotEmpty())
			.andExpect(jsonPath("$.character").isEmpty());

		assertThat(friendIds(a.id())).containsExactly(b.id());
		assertThat(friendIds(b.id())).containsExactly(a.id());
		call(get("/api/friend-requests?direction=received"), b.id()).andExpect(jsonPath("$.requests").isEmpty());
		request(a.id(), b.code()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_FRIENDS"));
		request(b.id(), a.code()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ALREADY_FRIENDS"));
	}

	@Test
	void 친구_목록은_최근에_친구가_된_사람이_위다() throws Exception {
		User me = user("나");
		User first = user("첫째");
		User second = user("둘째");
		makeFriends(me, first);
		makeFriends(second, me);
		assertThat(friendIds(me.id())).containsExactly(second.id(), first.id());
	}

	@Test
	void 상대가_이미_나에게_요청했으면_바로_친구가_되고_푸시는_없다() throws Exception {
		User a = user("민수");
		User b = user("지원");
		long id = requestId(a.id(), b);

		request(b.id(), a.code()).andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(id))
			.andExpect(jsonPath("$.status").value("ACCEPTED"))
			.andExpect(jsonPath("$.user.userId").value(a.id()));
		assertThat(friendIds(a.id())).containsExactly(b.id());
		verify(sender, after(500).never()).send(eq(List.of(a.token())), argThat(d -> "FRIEND_REQUEST".equals(d.get("type"))));
	}

	@Test
	void 요청_보내기_오류() throws Exception {
		User a = user("민수");
		User b = user("지원");
		long noNickname = USERS.incrementAndGet();

		request(noNickname, b.code()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PROFILE_REQUIRED"));
		request(a.id(), a.code()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("CANNOT_FRIEND_SELF"));
		request(a.id(), "ZZZZZZZZ").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("FRIEND_CODE_NOT_FOUND"));
		request(a.id(), "ABC").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("friendCode"));
		mvc.perform(post("/api/friend-requests").header("Authorization", TestTokens.bearer(a.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("friendCode"));

		requestId(a.id(), b);
		request(a.id(), b.code()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("FRIEND_REQUEST_EXISTS"));

		call(get("/api/friend-requests"), a.id()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("direction"));
		call(get("/api/friend-requests?direction=RECEIVED"), a.id()).andExpect(status().isBadRequest());
	}

	@Test
	void 거절_취소_끊기_뒤에도_다시_요청할_수_있다() throws Exception {
		User a = user("민수");
		User b = user("지원");

		long id = requestId(a.id(), b);
		call(delete("/api/friend-requests/" + id), b.id()).andExpect(status().isNoContent());
		call(get("/api/friend-requests?direction=sent"), a.id()).andExpect(jsonPath("$.requests").isEmpty());
		call(delete("/api/friend-requests/" + id), b.id()).andExpect(status().isNotFound());

		id = requestId(a.id(), b);
		// 남의 요청은 지울 수 없다
		User c = user("남");
		call(delete("/api/friend-requests/" + id), c.id()).andExpect(status().isNotFound());
		call(delete("/api/friend-requests/" + id), a.id()).andExpect(status().isNoContent());

		makeFriends(a, b);
		call(delete("/api/friends/" + b.id()), a.id()).andExpect(status().isNoContent());
		assertThat(friendIds(a.id())).isEmpty();
		assertThat(friendIds(b.id())).isEmpty();
		call(delete("/api/friends/" + b.id()), a.id()).andExpect(status().isNoContent());
		requestId(b.id(), a);
	}

	@Test
	void 차단하면_요청이_지워지고_차단된_사람은_없는_코드로_보인다() throws Exception {
		User a = user("민수");
		User b = user("지원");
		makeFriends(a, b);

		// 친구는 바로 차단하지 않는다(초안). 끊기를 거친다
		block(a.id(), b.id()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("UNFRIEND_FIRST"));
		assertThat(friendIds(a.id())).hasSize(1);
		call(delete("/api/friends/" + b.id()), a.id()).andExpect(status().isNoContent());
		requestId(b.id(), a);

		block(a.id(), b.id()).andExpect(status().isNoContent());
		block(a.id(), b.id()).andExpect(status().isNoContent());
		call(get("/api/friend-requests?direction=sent"), b.id()).andExpect(jsonPath("$.requests").isEmpty());
		call(get("/api/blocks"), a.id()).andExpect(jsonPath("$.blocks[0].userId").value(b.id()))
			.andExpect(jsonPath("$.blocks[0].nickname").value("지원"))
			.andExpect(jsonPath("$.blocks[0].friendCode").value(b.code()))
			.andExpect(jsonPath("$.blocks[0].blockedAt").isNotEmpty())
			.andExpect(jsonPath("$.blocks[0].hideInRooms").value(false));

		// 방 숨기기 설정 바꾸기. 차단한 사람이 아니면 404
		call(patch("/api/blocks/" + b.id()).contentType(MediaType.APPLICATION_JSON).content("{\"hideInRooms\":true}"), a.id())
			.andExpect(status().isNoContent());
		call(get("/api/blocks"), a.id()).andExpect(jsonPath("$.blocks[0].hideInRooms").value(true));
		call(patch("/api/blocks/" + a.id()).contentType(MediaType.APPLICATION_JSON).content("{\"hideInRooms\":true}"), b.id())
			.andExpect(status().isNotFound());
		call(patch("/api/blocks/" + b.id()).contentType(MediaType.APPLICATION_JSON).content("{}"), a.id())
			.andExpect(status().isBadRequest());

		request(b.id(), a.code()).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("FRIEND_CODE_NOT_FOUND"));
		request(a.id(), b.code()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("USER_BLOCKED"));

		// 풀어도 친구로 돌아가지 않는다
		call(delete("/api/blocks/" + b.id()), a.id()).andExpect(status().isNoContent());
		call(delete("/api/blocks/" + b.id()), a.id()).andExpect(status().isNoContent());
		call(get("/api/blocks"), a.id()).andExpect(jsonPath("$.blocks").isEmpty());
		assertThat(friendIds(a.id())).isEmpty();
		requestId(b.id(), a);
	}

	@Test
	void 받은_요청에서_차단할_수_있고_모르는_사람과_나는_차단할_수_없다() throws Exception {
		User a = user("민수");
		User b = user("지원");
		User stranger = user("남");
		requestId(b.id(), a);

		block(a.id(), b.id()).andExpect(status().isNoContent());
		call(get("/api/friend-requests?direction=sent"), b.id()).andExpect(jsonPath("$.requests").isEmpty());
		block(a.id(), stranger.id()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
		block(a.id(), a.id()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CANNOT_FRIEND_SELF"));
		mvc.perform(post("/api/blocks").header("Authorization", TestTokens.bearer(a.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("userId"));
	}

	@Test
	void 친구가_100명이면_요청과_수락을_막는다() throws Exception {
		User full = user("인싸");
		User other = user("민수");
		for (int i = 0; i < FriendService.FRIEND_LIMIT; i++) {
			jdbc.update("insert into friendships (requester_id, addressee_id, status) values (?, ?, 'ACCEPTED')", full.id(),
					1_000_000 + full.id() * 1000 + i);
		}
		request(other.id(), full.code()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FRIEND_LIMIT"));
		request(full.id(), other.code()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("FRIEND_LIMIT"));

		User third = user("지원");
		long id = requestId(third.id(), other);
		jdbc.update("delete from friendships where requester_id = ? and addressee_id >= 1000000", full.id());
		makeFriends(full, other);
		for (int i = 0; i < FriendService.FRIEND_LIMIT - 1; i++) {
			jdbc.update("insert into friendships (requester_id, addressee_id, status) values (?, ?, 'ACCEPTED')", other.id(),
					2_000_000 + other.id() * 1000 + i);
		}
		call(post("/api/friend-requests/" + id + "/accept"), other.id()).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("FRIEND_LIMIT"));
	}

	@Test
	void 친구_코드는_1분에_10번까지_넣을_수_있다() throws Exception {
		User a = user("민수");
		// 형식이 틀린 입력은 코드를 찾기 전에 400이라 세지 않는다
		for (int i = 0; i < FriendCodeAttemptLimiter.LIMIT + 2; i++) {
			request(a.id(), "ABC").andExpect(status().isBadRequest());
		}
		for (int i = 0; i < FriendCodeAttemptLimiter.LIMIT; i++) {
			request(a.id(), "ZZZZZZZZ").andExpect(status().isNotFound());
		}
		request(a.id(), "ZZZZZZZZ").andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("TOO_MANY_REQUESTS"));
	}

	@Test
	void 탈퇴하면_프로필과_친구_관계_요청_차단을_지운다() throws Exception {
		User gone = user("탈퇴");
		User friend = user("친구");
		User asked = user("요청");
		User blocker = user("차단");
		makeFriends(gone, friend);
		requestId(gone.id(), asked);
		requestId(gone.id(), blocker);
		block(blocker.id(), gone.id()).andExpect(status().isNoContent());

		mvc.perform(delete("/internal/users/" + gone.id()).header("X-Internal-Token", "internal-test-token"))
			.andExpect(status().isNoContent());

		assertThat(friendIds(friend.id())).isEmpty();
		call(get("/api/friend-requests?direction=received"), asked.id()).andExpect(jsonPath("$.requests").isEmpty());
		call(get("/api/blocks"), blocker.id()).andExpect(jsonPath("$.blocks").isEmpty());
		assertThat(jdbc.queryForObject("select count(*) from user_profiles where user_id = ?", Long.class, gone.id()))
			.isZero();
		request(friend.id(), gone.code()).andExpect(status().isNotFound());
	}

}
