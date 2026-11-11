package com.lolog.api.room;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import com.lolog.api.notification.PushSender;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

	// FCM에는 보내지 않고 무엇을 보냈는지 본다. 푸시는 따로 보내므로 timeout으로 기다린다.
	@MockitoBean
	PushSender sender;

	@BeforeEach
	void pushOn() {
		when(sender.enabled()).thenReturn(true);
	}

	// ---- 준비 ----

	private String phone(long userId) throws Exception {
		String token = "fcm-" + UUID.randomUUID();
		call(put("/api/devices/fcm-token").contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + token + "\"}"), userId).andExpect(status().isNoContent());
		return token;
	}

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

	// 방을 만들고 초대받은 사람이 모두 수락한다.
	private long roomId(long me, List<Long> members) throws Exception {
		long roomId = json(createRoom(me, "과팀플", members).andExpect(status().isCreated())).get("id").asLong();
		for (long member : members) {
			call(post("/api/room-invites/" + inviteId(member, roomId) + "/accept"), member).andExpect(status().isOk());
		}
		return roomId;
	}

	private long inviteId(long userId, long roomId) throws Exception {
		for (JsonNode invite : json(call(get("/api/room-invites"), userId)).get("invites")) {
			if (invite.get("room").get("id").asLong() == roomId) {
				return invite.get("id").asLong();
			}
		}
		throw new AssertionError("초대가 없어요");
	}

	private ResultActions invite(long me, long roomId, List<Long> userIds) throws Exception {
		String ids = userIds.stream().map(String::valueOf).collect(Collectors.joining(","));
		return call(post("/api/rooms/" + roomId + "/invites").contentType(MediaType.APPLICATION_JSON)
			.content("{\"userIds\":[" + ids + "]}"), me);
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
	void 방을_만들면_고른_친구에게_초대가_가고_수락한_사람만_들어온다() throws Exception {
		long me = user("나");
		long a = user("준");
		long b = user("서연");
		friends(me, a);
		friends(b, me);

		JsonNode room = json(createRoom(me, "  과팀플  ", List.of(a, b)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("과팀플"))
			.andExpect(jsonPath("$.ownerId").value(me))
			.andExpect(jsonPath("$.members.length()").value(1))
			.andExpect(jsonPath("$.members[0].userId").value(me))
			.andExpect(jsonPath("$.members[0].isMe").value(true))
			.andExpect(jsonPath("$.members[0].isOwner").value(true))
			.andExpect(jsonPath("$.invites.length()").value(2))
			.andExpect(jsonPath("$.invites[0].invitedUser.nickname").value("준"))
			.andExpect(jsonPath("$.invites[1].invitedUser.userId").value(b))
			.andExpect(jsonPath("$.lastActivity.type").value("CREATED"))
			.andExpect(jsonPath("$.unread").value(0)));
		long id = room.get("id").asLong();

		// 초대받은 사람은 아직 멤버가 아니다
		call(get("/api/rooms"), a).andExpect(jsonPath("$.rooms").isEmpty());
		call(get("/api/rooms/" + id), a).andExpect(status().isNotFound());
		call(get("/api/room-invites"), a).andExpect(jsonPath("$.invites.length()").value(1))
			.andExpect(jsonPath("$.invites[0].room.name").value("과팀플"))
			.andExpect(jsonPath("$.invites[0].invitedBy.userId").value(me))
			.andExpect(jsonPath("$.invites[0].invitedBy.nickname").value("나"))
			.andExpect(jsonPath("$.invites[0].invitedUser").isEmpty());

		// 수락하면 들어오고, 들어온 직후 받지 않은 글은 0이다. 방장이 아니면 invites는 비어 있다
		call(post("/api/room-invites/" + inviteId(a, id) + "/accept"), a).andExpect(status().isOk())
			.andExpect(jsonPath("$.members.length()").value(2))
			.andExpect(jsonPath("$.members[1].isMe").value(true))
			.andExpect(jsonPath("$.members[1].isOwner").value(false))
			.andExpect(jsonPath("$.members[0].isOwner").value(true))
			.andExpect(jsonPath("$.invites").isEmpty())
			.andExpect(jsonPath("$.unread").value(0));
		call(get("/api/room-invites"), a).andExpect(jsonPath("$.invites").isEmpty());

		// 거절하면 초대가 없어진다(방장에게 알리지 않음)
		long bInvite = inviteId(b, id);
		call(delete("/api/room-invites/" + bInvite), b).andExpect(status().isNoContent());
		call(delete("/api/room-invites/" + bInvite), b).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ROOM_INVITE_NOT_FOUND"));
		call(post("/api/room-invites/" + bInvite + "/accept"), b).andExpect(status().isNotFound());
		call(get("/api/rooms/" + id), me).andExpect(jsonPath("$.invites").isEmpty())
			.andExpect(jsonPath("$.members.length()").value(2));

		long outsider = user("남");
		call(get("/api/rooms/" + id), outsider).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));
	}

	@Test
	void 방장만_초대하고_취소하며_인원은_초대까지_합쳐_센다() throws Exception {
		long me = user("나");
		long a = user("준");
		long b = user("서연");
		long stranger = user("남");
		friends(me, a);
		friends(me, b);
		friends(a, b);
		long id = roomId(me, List.of(a));

		invite(a, id, List.of(b)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROOM_OWNER_ONLY"));
		invite(me, id, List.of()).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("userIds"));
		invite(me, id, List.of(a)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("userIds[0]"));
		invite(me, id, List.of(stranger)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
		invite(stranger, id, List.of(b)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ROOM_NOT_FOUND"));

		JsonNode invites = json(invite(me, id, List.of(b)).andExpect(status().isCreated())
			.andExpect(jsonPath("$.invites.length()").value(1))
			.andExpect(jsonPath("$.invites[0].invitedUser.userId").value(b)));
		long inviteId = invites.get("invites").get(0).get("id").asLong();
		// 다시 초대해도 같은 초대를 돌려준다
		invite(me, id, List.of(b)).andExpect(status().isCreated()).andExpect(jsonPath("$.invites[0].id").value(inviteId));

		call(delete("/api/rooms/" + id + "/invites/" + inviteId), a).andExpect(status().isForbidden());
		call(delete("/api/rooms/" + id + "/invites/" + inviteId), me).andExpect(status().isNoContent());
		call(delete("/api/rooms/" + id + "/invites/" + inviteId), me).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ROOM_INVITE_NOT_FOUND"));
		call(get("/api/room-invites"), b).andExpect(jsonPath("$.invites").isEmpty());

		// 멤버 2 + 초대 4 = 6명까지. 하나 더는 409
		List<Long> more = new java.util.ArrayList<>();
		for (int i = 0; i < 5; i++) {
			long f = user("친구" + i);
			friends(me, f);
			more.add(f);
		}
		invite(me, id, more).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_MEMBER_LIMIT"));
		invite(me, id, more.subList(0, 4)).andExpect(status().isCreated());
		invite(me, id, more.subList(4, 5)).andExpect(status().isConflict());
	}

	@Test
	void 한_사람은_방_20개까지_들어간다() throws Exception {
		long me = user("나");
		long a = user("준");
		friends(me, a);
		for (int i = 0; i < 20; i++) {
			createRoom(a, "방" + i, List.of(me)).andExpect(status().isCreated());
		}
		long first = json(call(get("/api/room-invites"), me)).get("invites").get(0).get("id").asLong();
		for (JsonNode invite : json(call(get("/api/room-invites"), me)).get("invites")) {
			if (invite.get("id").asLong() != first) {
				call(post("/api/room-invites/" + invite.get("id").asLong() + "/accept"), me).andExpect(status().isOk());
			}
		}
		createRoom(me, "하나 더", List.of(a)).andExpect(status().isCreated());
		createRoom(me, "또 하나", List.of(a)).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ROOM_LIMIT"));
		call(post("/api/room-invites/" + first + "/accept"), me).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ROOM_LIMIT"));
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

		// 초대를 수락하기 전의 "방을 만들었어요"는 세지 않는다
		call(get("/api/rooms"), b).andExpect(jsonPath("$.rooms[0].unread").value(1))
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

		// 방장은 새 방장을 골라야 나갈 수 있다
		call(delete("/api/rooms/" + roomId + "/members/me"), a).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("NEW_OWNER_REQUIRED"));
		call(delete("/api/rooms/" + roomId + "/members/me").param("newOwnerId", String.valueOf(a)), a)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("newOwnerId"));
		call(delete("/api/rooms/" + roomId + "/members/me").param("newOwnerId", String.valueOf(b)), a)
			.andExpect(status().isNoContent());
		call(get("/api/rooms/" + roomId), a).andExpect(status().isNotFound());
		// 나간 사람이 올린 만화와 글은 "나간 멤버"로 남는다(초안)
		call(get("/api/rooms/" + roomId + "/posts"), b).andExpect(jsonPath("$.posts.length()").value(1))
			.andExpect(jsonPath("$.posts[0].author.userId").isEmpty())
			.andExpect(jsonPath("$.posts[0].author.nickname").isEmpty())
			.andExpect(jsonPath("$.posts[0].author.left").value(true));
		call(get("/api/rooms/" + roomId + "/messages"), b).andExpect(jsonPath("$.messages[0].member.left").value(true))
			.andExpect(jsonPath("$.nextCursor").isEmpty());
		call(get("/api/rooms"), b).andExpect(jsonPath("$.rooms[0].lastActivity.nickname").isEmpty());
		// 새 방장
		call(get("/api/rooms/" + roomId), b).andExpect(jsonPath("$.members.length()").value(1))
			.andExpect(jsonPath("$.ownerId").value(b))
			.andExpect(jsonPath("$.members[0].isOwner").value(true));

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

		long d = user("도윤");
		friends(a, d);
		invite(a, roomId, List.of(d)).andExpect(status().isCreated());
		mvc.perform(delete("/internal/users/" + c).header("X-Internal-Token", "internal-test-token"))
			.andExpect(status().isNoContent());
		// 방장이 탈퇴하면 가장 먼저 들어온 멤버가 방장이 되고, 탈퇴한 사람이 받은 초대는 지워진다
		mvc.perform(delete("/internal/users/" + a).header("X-Internal-Token", "internal-test-token"))
			.andExpect(status().isNoContent());
		call(get("/api/rooms/" + roomId), b).andExpect(jsonPath("$.ownerId").value(b));
		mvc.perform(delete("/internal/users/" + d).header("X-Internal-Token", "internal-test-token"))
			.andExpect(status().isNoContent());
		assertThat(jdbc.queryForObject("select count(*) from room_invites where user_id = ?", Long.class, d)).isZero();
		long a2 = b;
		call(get("/api/rooms/" + roomId), a2).andExpect(jsonPath("$.members.length()").value(1));
		call(get("/api/rooms/" + roomId + "/posts"), a2).andExpect(jsonPath("$.posts[0].likeCount").value(0));
		JsonNode messages = json(call(get("/api/rooms/" + roomId + "/messages"), a2)).get("messages");
		for (JsonNode m : messages) {
			assertThat(m.get("member").get("userId").asLong()).isNotEqualTo(c);
		}
	}

	@Test
	void 방장은_이름을_바꾸고_멤버를_내보내고_방을_해산한다() throws Exception {
		long a = user("준");
		long b = user("서연");
		long c = user("민지");
		friends(a, b);
		friends(a, c);
		long roomId = roomId(a, List.of(b, c));
		String cPhone = phone(c);
		String aPhone = phone(a);
		comment(b, roomId, "{\"text\":\"안녕\"}").andExpect(status().isCreated());

		String path = "/api/rooms/" + roomId;
		call(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"새 이름\"}"), b)
			.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROOM_OWNER_ONLY"));
		call(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"), a)
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.field").value("name"));
		call(patch(path).contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" 새 이름 \"}"), a)
			.andExpect(status().isOk()).andExpect(jsonPath("$.name").value("새 이름"));

		// 내보낸 사람의 글은 나간 멤버로 남고, 다시 초대할 수 있다
		call(delete(path + "/members/" + b), c).andExpect(status().isForbidden());
		call(delete(path + "/members/" + a), a).andExpect(status().isBadRequest());
		call(delete(path + "/members/" + b), a).andExpect(status().isNoContent());
		call(delete(path + "/members/" + b), a).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
		call(get(path), b).andExpect(status().isNotFound());
		call(get(path + "/messages"), c).andExpect(jsonPath("$.messages[1].member.left").value(true))
			.andExpect(jsonPath("$.messages[1].text").value("안녕"));
		invite(a, roomId, List.of(b)).andExpect(status().isCreated());
		call(post("/api/room-invites/" + inviteId(b, roomId) + "/accept"), b).andExpect(status().isOk());
		// 다시 들어오면 그 사람 이름으로 다시 보인다
		call(get(path + "/messages"), c).andExpect(jsonPath("$.messages[1].member.nickname").value("서연"));

		call(delete(path), c).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ROOM_OWNER_ONLY"));
		long mint = comic(a, "DONE", "만화");
		postComic(a, roomId, mint).andExpect(status().isCreated());
		call(delete(path), a).andExpect(status().isNoContent());
		call(get(path), c).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select count(*) from room_messages where room_id = ?", Long.class, roomId)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from comics where id = ?", Long.class, mint)).isOne();
		verify(sender, timeout(5000)).send(List.of(cPhone), Map.of("type", "ROOM_DISBANDED", "roomName", "새 이름"));
		verify(sender, after(500).never()).send(List.of(aPhone), Map.of("type", "ROOM_DISBANDED", "roomName", "새 이름"));
	}

	@Test
	void 초대받으면_ROOM_INVITE_내_만화에_댓글이_달리면_ROOM_COMMENT를_보낸다() throws Exception {
		long a = user("준");
		long b = user("서연");
		friends(a, b);
		String bPhone = phone(b);
		String aPhone = phone(a);
		long roomId = json(createRoom(a, "과팀플", List.of(b))).get("id").asLong();
		long inviteId = inviteId(b, roomId);
		verify(sender, timeout(5000)).send(List.of(bPhone), Map.of("type", "ROOM_INVITE", "inviteId",
				String.valueOf(inviteId), "roomId", String.valueOf(roomId), "roomName", "과팀플"));
		call(post("/api/room-invites/" + inviteId + "/accept"), b).andExpect(status().isOk());

		long postId = json(postComic(a, roomId, comic(a, "DONE", "만화"))).get("id").asLong();
		comment(a, roomId, "{\"text\":\"내 댓글\",\"postId\":" + postId + "}").andExpect(status().isCreated());
		comment(b, roomId, "{\"text\":\"방 전체\"}").andExpect(status().isCreated());
		comment(b, roomId, "{\"text\":\"재밌다\",\"postId\":" + postId + "}").andExpect(status().isCreated());
		verify(sender, timeout(5000)).send(List.of(aPhone),
				Map.of("type", "ROOM_COMMENT", "roomId", String.valueOf(roomId), "postId", String.valueOf(postId), "nickname",
						"서연"));
		verify(sender, after(500).times(1)).send(eqList(aPhone), any());
	}

	private static List<String> eqList(String token) {
		return org.mockito.ArgumentMatchers.eq(List.of(token));
	}

	@Test
	void 대화는_최근_50줄부터_주고_커서로_앞을_더_준다() throws Exception {
		long a = user("준");
		long b = user("서연");
		friends(a, b);
		long roomId = roomId(a, List.of(b));
		for (int i = 1; i <= 60; i++) {
			jdbc.update("""
					insert into room_messages (room_id, user_id, message_type, body, created_at)
					values (?, ?, 'COMMENT', ?, clock_timestamp())
					""", roomId, b, "댓글" + i);
		}
		JsonNode first = json(call(get("/api/rooms/" + roomId + "/messages"), a).andExpect(status().isOk())
			.andExpect(jsonPath("$.messages.length()").value(50))
			.andExpect(jsonPath("$.messages[0].text").value("댓글11"))
			.andExpect(jsonPath("$.messages[49].text").value("댓글60")));
		String cursor = first.get("nextCursor").asString();
		call(get("/api/rooms/" + roomId + "/messages").param("cursor", cursor), a)
			.andExpect(jsonPath("$.messages.length()").value(11))
			.andExpect(jsonPath("$.messages[0].type").value("CREATED"))
			.andExpect(jsonPath("$.messages[10].text").value("댓글10"))
			.andExpect(jsonPath("$.nextCursor").isEmpty());
		call(get("/api/rooms/" + roomId + "/messages").param("cursor", "x"), a).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("cursor"));
	}

	@Test
	void 같은_방_멤버는_친구가_아니어도_차단하고_글을_숨기고_친구_요청을_보낸다() throws Exception {
		long a = user("준");
		long b = user("서연");
		long c = user("민지");
		long outsider = user("남");
		friends(a, b);
		friends(a, c);
		long roomId = roomId(a, List.of(b, c));
		long bPost = json(postComic(b, roomId, comic(b, "DONE", "서연 만화"))).get("id").asLong();
		comment(b, roomId, "{\"text\":\"비밀\"}").andExpect(status().isCreated());

		// 친구 추가 버튼 상태
		call(get("/api/rooms/" + roomId), c).andExpect(jsonPath("$.members[0].friendState").value("FRIEND"))
			.andExpect(jsonPath("$.members[1].friendState").value("NONE"))
			.andExpect(jsonPath("$.members[2].friendState").value("ME"));

		// 같은 방 멤버에게 userId로 친구 요청. 방 밖의 사람은 404
		String body = "{\"userId\":" + b + "}";
		call(post("/api/friend-requests").contentType(MediaType.APPLICATION_JSON).content(body), c)
			.andExpect(status().isCreated()).andExpect(jsonPath("$.user.userId").value(b));
		call(get("/api/rooms/" + roomId), c).andExpect(jsonPath("$.members[1].friendState").value("REQUESTED"));
		call(get("/api/rooms/" + roomId), b).andExpect(jsonPath("$.members[2].friendState").value("RECEIVED"));
		call(post("/api/friend-requests").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + outsider + "}"), c)
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("FRIEND_CODE_NOT_FOUND"));
		call(post("/api/friend-requests").contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\":" + b + ",\"friendCode\":\"ABCDEFGH\"}"), c).andExpect(status().isBadRequest());
		call(post("/api/friend-requests").contentType(MediaType.APPLICATION_JSON).content("{}"), c)
			.andExpect(status().isBadRequest());

		// 요청을 지운 뒤에도 같은 방 멤버라 차단할 수 있다. 숨기기로 하면 그 사람 글이 "숨긴 글"이 된다
		jdbc.update("delete from friendships where requester_id = ? and addressee_id = ?", c, b);
		call(post("/api/blocks").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + b + ",\"hideInRooms\":true}"),
				c).andExpect(status().isNoContent());
		call(post("/api/blocks").contentType(MediaType.APPLICATION_JSON).content("{\"userId\":" + outsider + "}"), c)
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
		call(get("/api/rooms/" + roomId), c).andExpect(jsonPath("$.members[1].friendState").value("BLOCKED"))
			.andExpect(jsonPath("$.lastActivity.text").isEmpty());
		call(get("/api/rooms/" + roomId + "/posts"), c).andExpect(jsonPath("$.posts[0].hidden").value(true))
			.andExpect(jsonPath("$.posts[0].imageUrl").isEmpty())
			.andExpect(jsonPath("$.posts[0].title").isEmpty());
		call(get("/api/rooms/" + roomId + "/messages"), c).andExpect(jsonPath("$.messages[2].hidden").value(true))
			.andExpect(jsonPath("$.messages[2].text").isEmpty())
			.andExpect(jsonPath("$.messages[1].post").isEmpty());
		// 차단당한 사람과 다른 멤버에게는 그대로 보인다
		call(get("/api/rooms/" + roomId + "/messages"), a).andExpect(jsonPath("$.messages[2].text").value("비밀"))
			.andExpect(jsonPath("$.messages[2].hidden").value(false));
		call(get("/api/rooms/" + roomId + "/posts"), b).andExpect(jsonPath("$.posts[0].id").value(bPost))
			.andExpect(jsonPath("$.posts[0].hidden").value(false));

		// 그대로 보기로 바꾸면 다시 보인다
		call(patch("/api/blocks/" + b).contentType(MediaType.APPLICATION_JSON).content("{\"hideInRooms\":false}"), c)
			.andExpect(status().isNoContent());
		call(get("/api/rooms/" + roomId + "/messages"), c).andExpect(jsonPath("$.messages[2].text").value("비밀"));

		// 차단한 사람이 있는 방에 초대받으면 알려 준다
		long room2 = json(createRoom(a, "둘째 방", List.of(b))).get("id").asLong();
		call(post("/api/room-invites/" + inviteId(b, room2) + "/accept"), b).andExpect(status().isOk());
		invite(a, room2, List.of(c)).andExpect(status().isCreated());
		call(get("/api/room-invites"), c).andExpect(jsonPath("$.invites[0].hasBlockedMember").value(true));
	}

}
