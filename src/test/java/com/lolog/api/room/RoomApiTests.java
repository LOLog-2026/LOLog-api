package com.lolog.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
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

// 친구와 같이 쓰는 방(#50, rooms.md 초안)을 실제 PostgreSQL·Redis로 확인한다. Docker가 켜져 있어야 한다.
// 탈퇴할 때 저장소 파일도 지우므로 SeaweedFS를 같이 띄운다.
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RoomApiTests {

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

	private static final AtomicLong USERS = new AtomicLong(11000);

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	// ---- 준비 ----

	private long user(String nickname) throws Exception {
		long id = USERS.incrementAndGet();
		call(patch("/api/me/profile").contentType(MediaType.APPLICATION_JSON).content("{\"nickname\":\"" + nickname + "\"}"),
				id).andExpect(status().isOk());
		return id;
	}

	private void friends(long a, long b) {
		jdbc.update("insert into friendships (requester_id, addressee_id, status) values (?, ?, 'ACCEPTED')", a, b);
	}

	private long comic(long userId, String status, String summary) {
		return jdbc.queryForObject("""
				insert into comics (user_id, status, card_summary, image_key) values (?, ?, ?, ?) returning id
				""", Long.class, userId, status, summary, "users/" + userId + "/comics/x.png");
	}

	private ResultActions call(MockHttpServletRequestBuilder request, long userId) throws Exception {
		return mvc.perform(request.header("Authorization", TestTokens.bearer(userId)));
	}

	private ResultActions createRoom(long me, String name, List<Long> members) throws Exception {
		String ids = members.stream().map(String::valueOf).collect(Collectors.joining(","));
		return call(post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"" + name + "\",\"memberIds\":[" + ids + "]}"), me);
	}

	private long roomId(long me, List<Long> members) throws Exception {
		return json(createRoom(me, "과팀플", members).andExpect(status().isCreated())).get("id").asLong();
	}

	private ResultActions postComic(long me, long roomId, long comicId) throws Exception {
		return call(post("/api/rooms/" + roomId + "/posts").contentType(MediaType.APPLICATION_JSON)
			.content("{\"comicId\":" + comicId + "}"), me);
	}

	private ResultActions comment(long me, long roomId, String body) throws Exception {
		return call(post("/api/rooms/" + roomId + "/messages").contentType(MediaType.APPLICATION_JSON).content(body), me);
	}

	private JsonNode json(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	// ---- 테스트 ----

	@Test
	void 친구를_골라_방을_만들면_나와_친구가_들어간다() throws Exception {
		long me = user("나");
		long a = user("준");
		long b = user("서연");
		friends(me, a);
		friends(b, me);

		createRoom(me, "  과팀플  ", List.of(a, b)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("과팀플"))
			.andExpect(jsonPath("$.members.length()").value(3))
			.andExpect(jsonPath("$.members[0].userId").value(me))
			.andExpect(jsonPath("$.members[0].isMe").value(true))
			.andExpect(jsonPath("$.members[1].nickname").value("준"))
			.andExpect(jsonPath("$.members[2].userId").value(b))
			.andExpect(jsonPath("$.lastActivity.type").value("CREATED"))
			.andExpect(jsonPath("$.lastActivity.isMe").value(true))
			.andExpect(jsonPath("$.unread").value(0));
		// 초대받은 사람에게는 "방을 만들었어요"가 받지 않은 글 1이다
		call(get("/api/rooms"), a).andExpect(jsonPath("$.rooms[0].unread").value(1))
			.andExpect(jsonPath("$.rooms[0].lastActivity.nickname").value("나"));
		call(get("/api/rooms"), a).andExpect(jsonPath("$.rooms[0].members[1].isMe").value(true));
		long outsider = user("남");
		long id = json(call(get("/api/rooms"), me)).get("rooms").get(0).get("id").asLong();
		call(get("/api/rooms/" + id), outsider).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
	}

	@Test
	void 방_만들기_오류() throws Exception {
		long me = user("나");
		long a = user("준");
		long stranger = user("남");
		friends(me, a);

		createRoom(me, "   ", List.of(a)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("name"));
		createRoom(me, "가나다라마바사아자차카타파하가나다라마바사", List.of(a)).andExpect(jsonPath("$.field").value("name"));
		createRoom(me, "방", List.of()).andExpect(jsonPath("$.field").value("memberIds"));
		createRoom(me, "방", List.of(me)).andExpect(jsonPath("$.field").value("memberIds[0]"));
		createRoom(me, "방", List.of(a, a)).andExpect(jsonPath("$.field").value("memberIds[1]"));
		createRoom(me, "방", List.of(a, stranger)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER_NOT_FOUND")).andExpect(jsonPath("$.field").value("memberIds[1]"));
		List<Long> six = new java.util.ArrayList<>();
		for (int i = 0; i < 6; i++) {
			long f = user("친구" + i);
			friends(me, f);
			six.add(f);
		}
		createRoom(me, "방", six).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_MEMBER_LIMIT"));
		createRoom(me, "방", six.subList(0, 5)).andExpect(status().isCreated());
		long noNickname = USERS.incrementAndGet();
		createRoom(noNickname, "방", List.of(a)).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("PROFILE_REQUIRED"));
	}

	@Test
	void 만화를_올리고_좋아요와_댓글을_남기면_대화와_받지_않은_글에_보인다() throws Exception {
		long a = user("준");
		long b = user("서연");
		friends(a, b);
		long roomId = roomId(a, List.of(b));
		long mint = comic(a, "DONE", "카페에 민트초코를 넣은 이유");

		JsonNode post = json(postComic(a, roomId, mint).andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("카페에 민트초코를 넣은 이유"))
			.andExpect(jsonPath("$.imageUrl").isNotEmpty())
			.andExpect(jsonPath("$.author.userId").value(a))
			.andExpect(jsonPath("$.likeCount").value(0)));
		long postId = post.get("id").asLong();
		postComic(a, roomId, mint).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_POST_EXISTS"));
		postComic(a, roomId, comic(b, "DONE", "남의 만화")).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COMIC_NOT_FOUND"));
		postComic(a, roomId, comic(a, "DRAWING", "그리는 중")).andExpect(status().isNotFound());

		call(get("/api/rooms"), b).andExpect(jsonPath("$.rooms[0].unread").value(2))
			.andExpect(jsonPath("$.rooms[0].lastActivity.type").value("POSTED"))
			.andExpect(jsonPath("$.rooms[0].lastActivity.nickname").value("준"))
			.andExpect(jsonPath("$.rooms[0].lastActivity.isMe").value(false));
		call(get("/api/rooms"), a).andExpect(jsonPath("$.rooms[0].unread").value(0))
			.andExpect(jsonPath("$.rooms[0].lastActivity.isMe").value(true));

		String likePath = "/api/rooms/" + roomId + "/posts/" + postId + "/like";
		call(put(likePath), b).andExpect(status().isOk()).andExpect(jsonPath("$.likeCount").value(1))
			.andExpect(jsonPath("$.likedByMe").value(true));
		call(put(likePath), b).andExpect(jsonPath("$.likeCount").value(1));
		comment(b, roomId, "{\"text\":\"  민트초코 최고 \",\"postId\":" + postId + "}").andExpect(status().isCreated())
			.andExpect(jsonPath("$.type").value("COMMENT"))
			.andExpect(jsonPath("$.text").value("민트초코 최고"))
			.andExpect(jsonPath("$.post.title").value("카페에 민트초코를 넣은 이유"))
			.andExpect(jsonPath("$.member.isMe").value(true));
		comment(b, roomId, "{\"text\":\"다들 뭐 해\"}").andExpect(jsonPath("$.postId").isEmpty());
		comment(b, roomId, "{\"text\":\" \"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("text"));
		comment(b, roomId, "{\"text\":\"x\",\"postId\":999999}").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ROOM_POST_NOT_FOUND"));

		call(get("/api/rooms"), a).andExpect(jsonPath("$.rooms[0].unread").value(3))
			.andExpect(jsonPath("$.rooms[0].lastActivity.type").value("COMMENTED"))
			.andExpect(jsonPath("$.rooms[0].lastActivity.text").value("다들 뭐 해"));
		call(get("/api/rooms/" + roomId + "/posts"), a).andExpect(jsonPath("$.posts[0].likeCount").value(1))
			.andExpect(jsonPath("$.posts[0].likedByMe").value(false))
			.andExpect(jsonPath("$.posts[0].commentCount").value(1));
		call(get("/api/rooms/" + roomId + "/messages"), a).andExpect(status().isOk())
			.andExpect(jsonPath("$.messages.length()").value(5))
			.andExpect(jsonPath("$.messages[0].type").value("CREATED"))
			.andExpect(jsonPath("$.messages[1].type").value("POSTED"))
			.andExpect(jsonPath("$.messages[1].member.isMe").value(true))
			.andExpect(jsonPath("$.messages[2].type").value("LIKED"))
			.andExpect(jsonPath("$.messages[3].text").value("민트초코 최고"))
			.andExpect(jsonPath("$.messages[3].post.id").value(postId))
			.andExpect(jsonPath("$.messages[4].post").isEmpty());
		call(get("/api/rooms"), a).andExpect(jsonPath("$.rooms[0].unread").value(0));

		// 좋아요를 취소하면 알림 줄도 빠진다
		call(delete(likePath), b).andExpect(jsonPath("$.likeCount").value(0)).andExpect(jsonPath("$.likedByMe").value(false));
		call(delete(likePath), b).andExpect(status().isOk());
		call(get("/api/rooms/" + roomId + "/messages"), b).andExpect(jsonPath("$.messages.length()").value(4));

		// 올린 사람만 내리고, 내리면 그 만화의 댓글도 지워진다
		call(delete("/api/rooms/" + roomId + "/posts/" + postId), b).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ROOM_POST_NOT_MINE"));
		call(delete("/api/rooms/" + roomId + "/posts/" + postId), a).andExpect(status().isNoContent());
		call(get("/api/rooms/" + roomId + "/posts"), a).andExpect(jsonPath("$.posts").isEmpty());
		call(get("/api/rooms/" + roomId + "/messages"), a).andExpect(jsonPath("$.messages.length()").value(2))
			.andExpect(jsonPath("$.messages[1].text").value("다들 뭐 해"));
		call(put(likePath), b).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ROOM_POST_NOT_FOUND"));
	}

	@Test
	void 마지막_한_명이_나가면_방을_지운다() throws Exception {
		long a = user("준");
		long b = user("서연");
		friends(a, b);
		long roomId = roomId(a, List.of(b));
		postComic(a, roomId, comic(a, "DONE", "만화")).andExpect(status().isCreated());

		call(delete("/api/rooms/" + roomId + "/members/me"), a).andExpect(status().isNoContent());
		call(get("/api/rooms/" + roomId), a).andExpect(status().isNotFound());
		// 나간 사람이 올린 만화는 남는다(초안)
		call(get("/api/rooms/" + roomId + "/posts"), b).andExpect(jsonPath("$.posts.length()").value(1))
			.andExpect(jsonPath("$.posts[0].author.nickname").value("준"));
		call(get("/api/rooms/" + roomId), b).andExpect(jsonPath("$.members.length()").value(1));

		call(delete("/api/rooms/" + roomId + "/members/me"), b).andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from rooms where id = ?", Long.class, roomId)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from room_posts where room_id = ?", Long.class, roomId)).isZero();
	}

	@Test
	void 만화를_지우거나_탈퇴하면_방에서도_내려간다() throws Exception {
		long a = user("준");
		long b = user("서연");
		long c = user("민지");
		friends(a, b);
		friends(a, c);
		long roomId = roomId(a, List.of(b, c));
		long gone = comic(a, "DONE", "지울 만화");
		postComic(a, roomId, gone).andExpect(status().isCreated());
		long bComic = comic(b, "DONE", "서연 만화");
		long bPost = json(postComic(b, roomId, bComic)).get("id").asLong();
		call(put("/api/rooms/" + roomId + "/posts/" + bPost + "/like"), c).andExpect(status().isOk());
		comment(c, roomId, "{\"text\":\"안녕\"}").andExpect(status().isCreated());

		call(delete("/api/comics/" + gone), a).andExpect(status().isNoContent());
		call(get("/api/rooms/" + roomId + "/posts"), b).andExpect(jsonPath("$.posts.length()").value(1));

		mvc.perform(delete("/internal/users/" + c).header("X-Internal-Token", "internal-test-token"))
			.andExpect(status().isNoContent());
		call(get("/api/rooms/" + roomId), a).andExpect(jsonPath("$.members.length()").value(2));
		call(get("/api/rooms/" + roomId + "/posts"), a).andExpect(jsonPath("$.posts[0].likeCount").value(0));
		JsonNode messages = json(call(get("/api/rooms/" + roomId + "/messages"), a)).get("messages");
		for (JsonNode m : messages) {
			assertThat(m.get("member").get("userId").asLong()).isNotEqualTo(c);
		}
	}

}
