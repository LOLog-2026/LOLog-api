package com.lolog.api.room;

import com.lolog.api.common.ApiException;
import com.lolog.api.common.PageRequestParams;
import com.lolog.api.notification.PushService;
import com.lolog.api.room.RoomDtos.InviteList;
import com.lolog.api.room.RoomDtos.MessageList;
import com.lolog.api.room.RoomDtos.PostList;
import com.lolog.api.room.RoomDtos.RoomActivity;
import com.lolog.api.room.RoomDtos.RoomInvite;
import com.lolog.api.room.RoomDtos.RoomItem;
import com.lolog.api.room.RoomDtos.RoomList;
import com.lolog.api.room.RoomDtos.RoomMember;
import com.lolog.api.room.RoomDtos.RoomMessage;
import com.lolog.api.room.RoomDtos.RoomPost;
import com.lolog.api.room.RoomDtos.RoomPostSummary;
import com.lolog.api.room.RoomDtos.RoomRef;
import com.lolog.api.storage.ImageStorage;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 친구와 같이 쓰는 방(LOLog-docs rooms.md, DB는 db.md V8. 초안).
// 방은 친구끼리 만들지만 그 뒤로는 친구 관계와 따로 간다(끊거나 차단해도 방에 남음).
// 방장이 친구를 초대하고, 초대받은 사람이 수락해야 멤버가 된다. 인원은 멤버 + 답을 기다리는 초대로 센다.
// 방·올린 만화·좋아요·대화는 외래 키로 이어 두어, 방을 지우면 모두 지워지고 만화를 지우면 그 만화를 올린 기록이 지워진다.
@Service
public class RoomService {

	static final int NAME_MAX = 20;
	static final int MEMBER_MAX = 6;
	static final int COMMENT_MAX = 200;
	static final int MESSAGE_PAGE = 50;
	static final int ROOM_LIMIT = 20;

	private final JdbcTemplate jdbc;
	private final NamedParameterJdbcTemplate named;
	private final ImageStorage storage;
	private final PushService push;

	public RoomService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, ImageStorage storage, PushService push) {
		this.jdbc = jdbc;
		this.named = named;
		this.storage = storage;
		this.push = push;
	}

	// ---- 방 ----

	// 마지막 소식이 최근인 방이 위(소식이 없으면 만든 시각)
	@Transactional(readOnly = true)
	public RoomList rooms(long me) {
		List<Long> ids = jdbc.queryForList("""
				select r.id from rooms r join room_members m on m.room_id = r.id and m.user_id = ?
				order by coalesce(r.last_activity_at, r.created_at) desc, r.id desc
				""", Long.class, me);
		return new RoomList(items(ids, me));
	}

	// 방을 만들고 고른 친구에게 초대를 보낸다(바로 넣지 않음). 처음에는 나 혼자 멤버이고 방장이다.
	@Transactional
	public RoomItem create(long me, String name, List<Long> memberIds) {
		String trimmed = roomName(name);
		checkInvitees(me, memberIds, "memberIds", "친구를 한 명 이상 골라 주세요.");
		if (memberIds.size() + 1 > MEMBER_MAX) {
			throw memberLimit();
		}
		List<String> nickname = jdbc.queryForList("select nickname from user_profiles where user_id = ?", String.class, me);
		if (nickname.isEmpty() || nickname.getFirst() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "PROFILE_REQUIRED", "닉네임을 먼저 정해 주세요.");
		}
		requireFriends(me, memberIds, "memberIds");
		lockUser(me);
		checkRoomLimit(me);
		long roomId = jdbc.queryForObject("insert into rooms (name, created_by, owner_id) values (?, ?, ?) returning id",
				Long.class, trimmed, me, me);
		jdbc.update("insert into room_members (room_id, user_id, created_at) values (?, ?, clock_timestamp())", roomId, me);
		// "방을 만들었어요" 줄. 마지막 소식에도 보인다(앱 PR #53 화면과 같게).
		addMessage(roomId, me, "CREATED", null, null);
		sendInvites(me, roomId, trimmed, memberIds);
		return items(List.of(roomId), me).getFirst();
	}

	@Transactional(readOnly = true)
	public RoomItem room(long me, long roomId) {
		requireMember(me, roomId);
		return items(List.of(roomId), me).getFirst();
	}

	// 방 이름 바꾸기(방장)
	@Transactional
	public RoomItem rename(long me, long roomId, String name) {
		requireMember(me, roomId);
		requireOwner(me, roomId);
		String trimmed = roomName(name);
		jdbc.update("update rooms set name = ?, updated_at = current_timestamp where id = ?", trimmed, roomId);
		return items(List.of(roomId), me).getFirst();
	}

	// 방 해산(방장). 방 줄을 지우면 멤버·초대·올린 기록·대화가 외래 키로 함께 지워진다. 만화는 각자 보관함에 남는다.
	@Transactional
	public void disband(long me, long roomId) {
		requireMember(me, roomId);
		lockRoom(roomId);
		requireOwner(me, roomId);
		String name = jdbc.queryForObject("select name from rooms where id = ?", String.class, roomId);
		List<Long> others = jdbc.queryForList("select user_id from room_members where room_id = ? and user_id <> ?",
				Long.class, roomId, me);
		jdbc.update("delete from rooms where id = ?", roomId);
		others.forEach(userId -> push.notify(userId, PushService.roomDisbanded(name)));
	}

	// 멤버 내보내기(방장). 내보낸 사람의 글은 "나간 멤버"로 남고, 다시 초대할 수 있다.
	@Transactional
	public void kick(long me, long roomId, long userId) {
		requireMember(me, roomId);
		lockRoom(roomId);
		requireOwner(me, roomId);
		if (userId == me) {
			throw ApiException.invalid("userId", "방장은 내보낼 수 없어요. 나가기를 써 주세요.");
		}
		if (jdbc.update("delete from room_members where room_id = ? and user_id = ?", roomId, userId) == 0) {
			throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "이 방의 멤버가 아니에요.");
		}
	}

	// 나가도 내가 올린 만화와 남긴 글은 "나간 멤버"로 남는다. 마지막 한 명이 나가면 방과 올린 기록·대화를 모두 지운다.
	// 방장은 다른 멤버가 있으면 새 방장(newOwnerId)을 꼭 골라야 한다(초안).
	@Transactional
	public void leave(long me, long roomId, Long newOwnerId) {
		requireMember(me, roomId);
		lockRoom(roomId);
		Long owner = ownerOf(roomId);
		Long others = jdbc.queryForObject("select count(*) from room_members where room_id = ? and user_id <> ?", Long.class,
				roomId, me);
		if (owner != null && owner == me && others != null && others > 0) {
			if (newOwnerId == null) {
				throw new ApiException(HttpStatus.CONFLICT, "NEW_OWNER_REQUIRED", "나가기 전에 새 방장을 골라 주세요.");
			}
			Boolean member = jdbc.queryForObject(
					"select exists (select 1 from room_members where room_id = ? and user_id = ?)", Boolean.class, roomId,
					newOwnerId);
			if (newOwnerId == me || !Boolean.TRUE.equals(member)) {
				throw ApiException.invalid("newOwnerId", "새 방장은 이 방의 다른 멤버여야 해요.");
			}
			jdbc.update("update rooms set owner_id = ?, updated_at = current_timestamp where id = ?", newOwnerId, roomId);
		}
		jdbc.update("delete from room_members where room_id = ? and user_id = ?", roomId, me);
		removeEmptyOrHandOver(jdbc, roomId);
	}

	// 멤버가 없으면 방을 지우고, 방장이 멤버가 아니면 가장 먼저 들어온 멤버에게 넘긴다. 탈퇴할 때도 쓴다.
	public static void removeEmptyOrHandOver(JdbcTemplate jdbc, long roomId) {
		jdbc.update("delete from rooms r where r.id = ? and not exists (select 1 from room_members m where m.room_id = r.id)",
				roomId);
		jdbc.update("""
				update rooms r set owner_id = (select m.user_id from room_members m where m.room_id = r.id
				  order by m.created_at, m.user_id limit 1), updated_at = current_timestamp
				where r.id = ? and not exists (select 1 from room_members m where m.room_id = r.id and m.user_id = r.owner_id)
				""", roomId);
	}

	// ---- 초대 ----

	// 방장만 친구를 초대한다. 이미 답을 기다리는 초대가 있는 사람은 그 초대를 그대로 돌려준다.
	@Transactional
	public InviteList invite(long me, long roomId, List<Long> userIds) {
		requireMember(me, roomId);
		lockRoom(roomId);
		requireOwner(me, roomId);
		checkInvitees(me, userIds, "userIds", "초대할 친구를 한 명 이상 골라 주세요.");
		requireFriends(me, userIds, "userIds");
		for (int i = 0; i < userIds.size(); i++) {
			Boolean member = jdbc.queryForObject(
					"select exists (select 1 from room_members where room_id = ? and user_id = ?)", Boolean.class, roomId,
					userIds.get(i));
			if (Boolean.TRUE.equals(member)) {
				throw ApiException.invalid("userIds[" + i + "]", "이미 방에 있는 사람이에요.");
			}
		}
		List<Long> fresh = new ArrayList<>();
		for (Long userId : userIds) {
			Boolean invited = jdbc.queryForObject(
					"select exists (select 1 from room_invites where room_id = ? and user_id = ?)", Boolean.class, roomId,
					userId);
			if (!Boolean.TRUE.equals(invited)) {
				fresh.add(userId);
			}
		}
		if (headcount(roomId) + fresh.size() > MEMBER_MAX) {
			throw memberLimit();
		}
		String name = jdbc.queryForObject("select name from rooms where id = ?", String.class, roomId);
		sendInvites(me, roomId, name, fresh);
		List<RoomInvite> all = sentInvites(List.of(roomId), me).getOrDefault(roomId, List.of());
		return new InviteList(all.stream().filter(i -> userIds.contains(i.invitedUser().userId())).toList());
	}

	@Transactional
	public void cancelInvite(long me, long roomId, long inviteId) {
		requireMember(me, roomId);
		requireOwner(me, roomId);
		if (jdbc.update("delete from room_invites where id = ? and room_id = ?", inviteId, roomId) == 0) {
			throw inviteNotFound();
		}
	}

	// 내가 받은 초대(최신순)
	@Transactional(readOnly = true)
	public InviteList receivedInvites(long me) {
		record I(long id, long roomId, String roomName, long invitedBy, OffsetDateTime at, boolean hasBlocked) {
		}
		List<I> rows = jdbc.query("""
				select i.id, i.room_id, r.name, i.invited_by, i.created_at,
				       exists (select 1 from room_members m join user_blocks b on b.blocked_id = m.user_id and b.blocker_id = i.user_id
				               where m.room_id = i.room_id) as has_blocked
				from room_invites i join rooms r on r.id = i.room_id
				where i.user_id = ? order by i.created_at desc, i.id desc
				""", (rs, n) -> new I(rs.getLong("id"), rs.getLong("room_id"), rs.getString("name"), rs.getLong("invited_by"),
				rs.getObject("created_at", OffsetDateTime.class), rs.getBoolean("has_blocked")), me);
		Map<Long, RoomMember> people = people(rows.stream().map(I::invitedBy).toList(), me, null);
		return new InviteList(rows.stream()
			.map(i -> new RoomInvite(i.id(), new RoomRef(i.roomId(), i.roomName()), null, people.get(i.invitedBy()),
					i.hasBlocked(), i.at()))
			.toList());
	}

	// 수락하면 초대를 지우고 멤버로 넣는다. 들어온 직후 받지 않은 글은 0이다.
	@Transactional
	public RoomItem acceptInvite(long me, long inviteId) {
		long roomId = invitedRoom(me, inviteId);
		lockRoom(roomId);
		lockUser(me);
		// 잠그기 전에 방장이 취소했을 수 있어 다시 읽는다.
		invitedRoom(me, inviteId);
		checkRoomLimit(me);
		jdbc.update("delete from room_invites where id = ?", inviteId);
		jdbc.update("insert into room_members (room_id, user_id, created_at) values (?, ?, clock_timestamp())", roomId, me);
		return items(List.of(roomId), me).getFirst();
	}

	// 거절해도 방장에게 알리지 않는다.
	@Transactional
	public void declineInvite(long me, long inviteId) {
		if (jdbc.update("delete from room_invites where id = ? and user_id = ?", inviteId, me) == 0) {
			throw inviteNotFound();
		}
	}

	// ---- 올린 만화 ----

	@Transactional(readOnly = true)
	public PostList posts(long me, long roomId) {
		requireMember(me, roomId);
		return new PostList(posts(me, roomId, null));
	}

	// 내 만화 중 완성된 것만. 같은 만화를 같은 방에 두 번 올릴 수 없다.
	@Transactional
	public RoomPost postComic(long me, long roomId, Long comicId) {
		requireMember(me, roomId);
		if (comicId == null) {
			throw ApiException.invalid("comicId", "comicId가 필요해요.");
		}
		Boolean mine = jdbc.queryForObject(
				"select exists (select 1 from comics where id = ? and user_id = ? and status = 'DONE')", Boolean.class,
				comicId, me);
		if (!Boolean.TRUE.equals(mine)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "COMIC_NOT_FOUND", "올릴 수 있는 만화가 아니에요.");
		}
		List<Long> created = jdbc.queryForList("""
				insert into room_posts (room_id, comic_id, user_id) values (?, ?, ?)
				on conflict (room_id, comic_id) do nothing returning id
				""", Long.class, roomId, comicId, me);
		if (created.isEmpty()) {
			throw new ApiException(HttpStatus.CONFLICT, "ROOM_POST_EXISTS", "이미 이 방에 올린 만화예요.");
		}
		long postId = created.getFirst();
		addMessage(roomId, me, "POSTED", postId, null);
		return posts(me, roomId, postId).getFirst();
	}

	// 올린 사람만 내린다. 그 만화의 좋아요·대화 줄은 외래 키로 같이 지워진다.
	@Transactional
	public void removePost(long me, long roomId, long postId) {
		requireMember(me, roomId);
		long author = postAuthor(roomId, postId);
		if (author != me) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ROOM_POST_NOT_MINE", "올린 사람만 내릴 수 있어요.");
		}
		jdbc.update("delete from room_posts where id = ?", postId);
	}

	// 이미 눌렀어도 그대로 200
	@Transactional
	public RoomPost like(long me, long roomId, long postId) {
		requireMember(me, roomId);
		postAuthor(roomId, postId);
		int added = jdbc.update("insert into room_post_likes (post_id, user_id) values (?, ?) on conflict do nothing",
				postId, me);
		if (added > 0) {
			addMessage(roomId, me, "LIKED", postId, null);
		}
		return posts(me, roomId, postId).getFirst();
	}

	// 안 눌렀어도 그대로 200. 취소하면 그 좋아요 알림 줄도 뺀다.
	@Transactional
	public RoomPost unlike(long me, long roomId, long postId) {
		requireMember(me, roomId);
		postAuthor(roomId, postId);
		int removed = jdbc.update("delete from room_post_likes where post_id = ? and user_id = ?", postId, me);
		if (removed > 0) {
			jdbc.update("delete from room_messages where post_id = ? and user_id = ? and message_type = 'LIKED'", postId, me);
		}
		return posts(me, roomId, postId).getFirst();
	}

	// ---- 대화 ----

	// 오래된 줄이 위. 처음엔 최근 50줄을 주고, cursor(받은 것 중 가장 오래된 줄)를 주면 그 앞 50줄을 준다(초안).
	// 부르면 이 방의 받지 않은 글이 0이 된다.
	@Transactional
	public MessageList messages(long me, long roomId, String cursor) {
		requireMember(me, roomId);
		PageRequestParams page = PageRequestParams.of(cursor, MESSAGE_PAGE);
		List<Row> rows = new ArrayList<>(jdbc.query("""
				select x.id, x.user_id, x.message_type, x.post_id, x.body, x.created_at
				from room_messages x where x.room_id = ? and (?::bigint is null or x.id < ?) order by x.id desc limit ?
				""", (rs, n) -> new Row(rs.getLong("id"), rs.getLong("user_id"), rs.getString("message_type"),
				(Long) rs.getObject("post_id"), rs.getString("body"), rs.getObject("created_at", OffsetDateTime.class)),
				roomId, page.before(), page.before(), MESSAGE_PAGE + 1));
		String next = null;
		if (rows.size() > MESSAGE_PAGE) {
			rows = new ArrayList<>(rows.subList(0, MESSAGE_PAGE));
			next = String.valueOf(rows.getLast().id());
		}
		java.util.Collections.reverse(rows);
		jdbc.update("update room_members set last_read_at = clock_timestamp() where room_id = ? and user_id = ?", roomId, me);
		return new MessageList(messages(me, roomId, rows), next);
	}

	@Transactional
	public RoomMessage comment(long me, long roomId, String text, Long postId) {
		requireMember(me, roomId);
		String trimmed = text == null ? "" : text.strip();
		int length = trimmed.codePointCount(0, trimmed.length());
		if (length < 1 || length > COMMENT_MAX) {
			throw ApiException.invalid("text", "댓글은 1~200자예요.");
		}
		Long author = postId == null ? null : postAuthor(roomId, postId);
		long id = addMessage(roomId, me, "COMMENT", postId, trimmed);
		// 내 만화에 다른 사람이 댓글을 달면 올린 사람에게만 푸시. 올린 사람이 방에서 나갔으면 보내지 않는다.
		if (author != null && author != me && isMember(author, roomId)) {
			String nickname = jdbc.queryForObject("select nickname from user_profiles where user_id = ?", String.class, me);
			push.notify(author, PushService.roomComment(roomId, postId, nickname == null ? "" : nickname));
		}
		Row row = jdbc.queryForObject("select id, user_id, message_type, post_id, body, created_at from room_messages where id = ?",
				(rs, n) -> new Row(rs.getLong("id"), rs.getLong("user_id"), rs.getString("message_type"),
						(Long) rs.getObject("post_id"), rs.getString("body"),
						rs.getObject("created_at", OffsetDateTime.class)),
				id);
		return messages(me, roomId, List.of(row)).getFirst();
	}

	// ---- 안에서 쓰는 것 ----

	private static String roomName(String name) {
		String trimmed = name == null ? "" : name.strip();
		int length = trimmed.codePointCount(0, trimmed.length());
		if (length < 1 || length > NAME_MAX) {
			throw ApiException.invalid("name", "방 이름은 1~20자예요.");
		}
		return trimmed;
	}

	// 비었거나, 같은 사람이 두 번이거나, 나를 넣으면 400.
	private static void checkInvitees(long me, List<Long> ids, String field, String emptyMessage) {
		if (ids == null || ids.isEmpty()) {
			throw ApiException.invalid(field, emptyMessage);
		}
		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < ids.size(); i++) {
			Long id = ids.get(i);
			if (id == null || id == me || !seen.add(id)) {
				throw ApiException.invalid(field + "[" + i + "]", "같은 사람이나 나는 넣을 수 없어요.");
			}
		}
	}

	// 모두 내 친구여야 한다. 아니면 404 USER_NOT_FOUND.
	private void requireFriends(long me, List<Long> ids, String field) {
		for (int i = 0; i < ids.size(); i++) {
			Boolean friend = jdbc.queryForObject("""
					select exists (select 1 from friendships where status = 'ACCEPTED'
					  and ((requester_id = ? and addressee_id = ?) or (requester_id = ? and addressee_id = ?)))
					""", Boolean.class, me, ids.get(i), ids.get(i), me);
			if (!Boolean.TRUE.equals(friend)) {
				throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "친구가 아닌 사람이에요.", field + "[" + i + "]");
			}
		}
	}

	private void sendInvites(long me, long roomId, String roomName, List<Long> userIds) {
		for (Long userId : userIds) {
			long inviteId = jdbc.queryForObject("""
					insert into room_invites (room_id, user_id, invited_by, created_at) values (?, ?, ?, clock_timestamp())
					returning id
					""", Long.class, roomId, userId, me);
			push.notify(userId, PushService.roomInvite(inviteId, roomId, roomName));
		}
	}

	// 멤버 + 답을 기다리는 초대
	private int headcount(long roomId) {
		Integer count = jdbc.queryForObject("""
				select (select count(*) from room_members where room_id = ?) + (select count(*) from room_invites where room_id = ?)
				""", Integer.class, roomId, roomId);
		return count == null ? 0 : count;
	}

	private void checkRoomLimit(long userId) {
		Long count = jdbc.queryForObject("select count(*) from room_members where user_id = ?", Long.class, userId);
		if (count != null && count >= ROOM_LIMIT) {
			throw new ApiException(HttpStatus.CONFLICT, "ROOM_LIMIT", "방은 20개까지 들어갈 수 있어요.");
		}
	}

	// 같은 방의 인원을 동시에 바꾸지 않게 방 줄을 잠근다.
	private void lockRoom(long roomId) {
		jdbc.queryForList("select id from rooms where id = ? for update", Long.class, roomId);
	}

	// 한 사람의 방 20개 제한을 동시에 넘기지 않게 프로필 줄을 잠근다. 프로필이 없으면 잠그지 않는다.
	private void lockUser(long userId) {
		jdbc.queryForList("select user_id from user_profiles where user_id = ? for update", Long.class, userId);
	}

	private void requireOwner(long me, long roomId) {
		Long owner = jdbc.queryForObject("select owner_id from rooms where id = ?", Long.class, roomId);
		if (owner == null || owner != me) {
			throw new ApiException(HttpStatus.FORBIDDEN, "ROOM_OWNER_ONLY", "방장만 할 수 있어요.");
		}
	}

	private long invitedRoom(long me, long inviteId) {
		return jdbc.queryForList("select room_id from room_invites where id = ? and user_id = ?", Long.class, inviteId, me)
			.stream()
			.findFirst()
			.orElseThrow(RoomService::inviteNotFound);
	}

	private static ApiException memberLimit() {
		return new ApiException(HttpStatus.CONFLICT, "ROOM_MEMBER_LIMIT", "나를 포함해 6명까지예요.");
	}

	private static ApiException inviteNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "ROOM_INVITE_NOT_FOUND", "초대를 찾을 수 없어요.");
	}

	// 방장이 보는 답을 기다리는 초대. 방장이 아닌 방은 빈 목록.
	private Map<Long, List<RoomInvite>> sentInvites(List<Long> roomIds, long me) {
		record I(long id, long roomId, String roomName, long userId, OffsetDateTime at) {
		}
		List<I> rows = named.query("""
				select i.id, i.room_id, r.name, i.user_id, i.created_at from room_invites i join rooms r on r.id = i.room_id
				where i.room_id in (:ids) and r.owner_id = :me order by i.created_at, i.id
				""", new MapSqlParameterSource("ids", roomIds).addValue("me", me),
				(rs, n) -> new I(rs.getLong("id"), rs.getLong("room_id"), rs.getString("name"), rs.getLong("user_id"),
						rs.getObject("created_at", OffsetDateTime.class)));
		Map<Long, RoomMember> people = people(rows.stream().map(I::userId).toList(), me, null);
		Map<Long, List<RoomInvite>> result = new HashMap<>();
		rows.forEach(i -> result.computeIfAbsent(i.roomId(), k -> new ArrayList<>())
			.add(new RoomInvite(i.id(), new RoomRef(i.roomId(), i.roomName()), people.get(i.userId()), null, false,
					i.at())));
		return result;
	}

	private record Row(long id, long userId, String type, Long postId, String body, OffsetDateTime at) {
	}

	private boolean isMember(long userId, long roomId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists (select 1 from room_members where room_id = ? and user_id = ?)", Boolean.class, roomId,
				userId));
	}

	private void requireMember(long me, long roomId) {
		if (!isMember(me, roomId)) {
			throw new ApiException(HttpStatus.NOT_FOUND, "ROOM_NOT_FOUND", "방을 찾을 수 없어요.");
		}
	}

	// 이 방에 올린 만화의 올린 사람. 없거나 다른 방이면 404.
	private long postAuthor(long roomId, long postId) {
		return jdbc.queryForList("select user_id from room_posts where id = ? and room_id = ?", Long.class, postId, roomId)
			.stream()
			.findFirst()
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ROOM_POST_NOT_FOUND", "만화를 찾을 수 없어요."));
	}

	private long addMessage(long roomId, long userId, String type, Long postId, String body) {
		long id = jdbc.queryForObject("""
				insert into room_messages (room_id, user_id, message_type, post_id, body, created_at)
				values (?, ?, ?, ?, ?, clock_timestamp()) returning id
				""", Long.class, roomId, userId, type, postId, body);
		jdbc.update("update rooms set last_activity_at = clock_timestamp(), updated_at = current_timestamp where id = ?",
				roomId);
		return id;
	}

	// 방에 남긴 글의 사람. 지금 멤버가 아니면 "나간 멤버"로 바꾼다.
	private Map<Long, RoomMember> authors(Collection<Long> userIds, long me, long roomId) {
		Set<Long> members = new HashSet<>(
				jdbc.queryForList("select user_id from room_members where room_id = ?", Long.class, roomId));
		Map<Long, RoomMember> result = people(userIds, me, ownerOf(roomId));
		result.replaceAll((id, m) -> members.contains(id) ? m : RoomMember.leftMember());
		return result;
	}

	// 회원들의 닉네임과 프로필 캐릭터 썸네일. owner가 있으면 그 사람의 isOwner가 true.
	private Map<Long, RoomMember> people(Collection<Long> userIds, long me, Long owner) {
		Map<Long, RoomMember> result = new HashMap<>();
		if (userIds.isEmpty()) {
			return result;
		}
		Map<Long, String> states = friendStates(userIds, me);
		// 기한이 지난 정지는 정지 아님으로 본다(인증 서비스가 따로 알리지 않음).
		Set<Long> suspended = new HashSet<>(named.queryForList("""
				select user_id from user_suspensions where user_id in (:ids)
				and (suspended_until is null or suspended_until > current_timestamp)
				""", Map.of("ids", userIds), Long.class));
		named.query("select user_id, nickname, character_thumb_key from user_profiles where user_id in (:ids)",
				Map.of("ids", userIds), rs -> {
					long id = rs.getLong("user_id");
					String thumb = rs.getString("character_thumb_key");
					String nickname = rs.getString("nickname");
					result.put(id, new RoomMember(id, nickname == null ? "" : nickname,
							thumb == null ? null : storage.presignedUrl(thumb).toString(), id == me,
							owner != null && id == owner, false, states.get(id), suspended.contains(id)));
				});
		for (Long id : userIds) {
			result.putIfAbsent(id, new RoomMember(id, "", null, id == me, owner != null && id == owner, false,
					states.get(id), suspended.contains(id)));
		}
		return result;
	}

	// 나와 그 사람의 관계. 내가 차단했으면 BLOCKED가 먼저다.
	private Map<Long, String> friendStates(Collection<Long> userIds, long me) {
		Map<Long, String> states = new HashMap<>();
		userIds.forEach(id -> states.put(id, id == me ? "ME" : "NONE"));
		MapSqlParameterSource args = new MapSqlParameterSource("ids", userIds).addValue("me", me);
		named.query("""
				select requester_id, addressee_id, status from friendships
				where (requester_id = :me and addressee_id in (:ids)) or (addressee_id = :me and requester_id in (:ids))
				""", args, rs -> {
			boolean sent = rs.getLong("requester_id") == me;
			long other = sent ? rs.getLong("addressee_id") : rs.getLong("requester_id");
			states.put(other, "ACCEPTED".equals(rs.getString("status")) ? "FRIEND" : sent ? "REQUESTED" : "RECEIVED");
		});
		named.query("select blocked_id from user_blocks where blocker_id = :me and blocked_id in (:ids)", args,
				rs -> {
					states.put(rs.getLong("blocked_id"), "BLOCKED");
				});
		return states;
	}

	// 내가 차단하고 방 글을 숨기기로 한 사람들
	private Set<Long> hiddenUsers(long me) {
		return new HashSet<>(jdbc.queryForList(
				"select blocked_id from user_blocks where blocker_id = ? and hide_in_rooms", Long.class, me));
	}

	private List<RoomItem> items(List<Long> roomIds, long me) {
		if (roomIds.isEmpty()) {
			return List.of();
		}
		MapSqlParameterSource ids = new MapSqlParameterSource("ids", roomIds).addValue("me", me);
		Map<Long, List<Long>> memberIds = new HashMap<>();
		named.query("select room_id, user_id from room_members where room_id in (:ids) order by created_at, user_id", ids,
				rs -> {
					memberIds.computeIfAbsent(rs.getLong("room_id"), k -> new ArrayList<>()).add(rs.getLong("user_id"));
				});
		record Last(String type, long userId, OffsetDateTime at, String body) {
		}
		Map<Long, Last> last = new HashMap<>();
		named.query("""
				select distinct on (room_id) room_id, message_type, user_id, created_at, body from room_messages
				where room_id in (:ids) order by room_id, id desc
				""", ids, rs -> {
			last.put(rs.getLong("room_id"), new Last(rs.getString("message_type"), rs.getLong("user_id"),
					rs.getObject("created_at", OffsetDateTime.class), rs.getString("body")));
		});
		Map<Long, Integer> unread = new HashMap<>();
		named.query("""
				select m.room_id, count(x.id) as unread from room_members m
				left join room_messages x on x.room_id = m.room_id and x.user_id <> m.user_id and x.created_at > m.last_read_at
				where m.user_id = :me and m.room_id in (:ids) group by m.room_id
				""", ids, rs -> {
			unread.put(rs.getLong("room_id"), rs.getInt("unread"));
		});
		Set<Long> everyone = new HashSet<>();
		memberIds.values().forEach(everyone::addAll);
		last.values().forEach(l -> everyone.add(l.userId()));
		Map<Long, RoomMember> people = people(everyone, me, null);
		Set<String> memberPairs = new HashSet<>();
		memberIds.forEach((room, users) -> users.forEach(u -> memberPairs.add(room + ":" + u)));
		Map<Long, List<RoomInvite>> invites = sentInvites(roomIds, me);
		Set<Long> hidden = hiddenUsers(me);
		Map<Long, RoomItem> byId = new LinkedHashMap<>();
		named.query("select id, name, owner_id, created_at from rooms where id in (:ids)", ids, rs -> {
			long id = rs.getLong("id");
			long owner = rs.getLong("owner_id");
			Last l = last.get(id);
			RoomActivity activity = l == null ? null
					: new RoomActivity("COMMENT".equals(l.type()) ? "COMMENTED" : l.type(),
							memberPairs.contains(id + ":" + l.userId()) ? people.get(l.userId()).nickname() : null,
							l.userId() == me, l.at(), hidden.contains(l.userId()) ? null : l.body());
			List<RoomMember> members = memberIds.getOrDefault(id, List.of())
				.stream()
				.map(people::get)
				.map(m -> withOwner(m, owner))
				.toList();
			byId.put(id, new RoomItem(id, rs.getString("name"), owner, members, invites.getOrDefault(id, List.of()),
					activity, unread.getOrDefault(id, 0), rs.getObject("created_at", OffsetDateTime.class)));
		});
		return roomIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
	}

	private static RoomMember withOwner(RoomMember m, long owner) {
		return m.owner(m.userId() == owner);
	}

	private Long ownerOf(long roomId) {
		return jdbc.queryForList("select owner_id from rooms where id = ?", Long.class, roomId).stream().findFirst().orElse(null);
	}

	// 방의 올린 만화. onlyPost가 있으면 그 하나만. 최근에 올린 것이 앞.
	private List<RoomPost> posts(long me, long roomId, Long onlyPost) {
		record P(long id, long comicId, long userId, OffsetDateTime at, String title, String imageKey, int likes,
				boolean liked, int comments) {
		}
		List<P> rows = jdbc.query("""
				select p.id, p.comic_id, p.user_id, p.created_at, c.card_summary, c.image_key,
				       (select count(*) from room_post_likes l where l.post_id = p.id) as likes,
				       exists (select 1 from room_post_likes l where l.post_id = p.id and l.user_id = ?) as liked,
				       (select count(*) from room_messages x where x.post_id = p.id and x.message_type = 'COMMENT') as comments
				from room_posts p join comics c on c.id = p.comic_id
				where p.room_id = ? and (?::bigint is null or p.id = ?)
				order by p.created_at desc, p.id desc
				""", (rs, n) -> new P(rs.getLong("id"), rs.getLong("comic_id"), rs.getLong("user_id"),
				rs.getObject("created_at", OffsetDateTime.class), rs.getString("card_summary"), rs.getString("image_key"),
				rs.getInt("likes"), rs.getBoolean("liked"), rs.getInt("comments")), me, roomId, onlyPost, onlyPost);
		Map<Long, RoomMember> people = authors(rows.stream().map(P::userId).toList(), me, roomId);
		Set<Long> hidden = hiddenUsers(me);
		return rows.stream().map(p -> {
			boolean hide = hidden.contains(p.userId());
			return new RoomPost(p.id(), roomId, p.comicId(), hide ? null : p.title() == null ? "" : p.title(),
					hide ? null : url(p.imageKey()), people.get(p.userId()), p.at(), p.likes(), p.liked(), p.comments(),
					hide);
		}).toList();
	}

	private List<RoomMessage> messages(long me, long roomId, List<Row> rows) {
		Set<Long> postIds = new HashSet<>();
		rows.forEach(r -> {
			if (r.postId() != null) {
				postIds.add(r.postId());
			}
		});
		Map<Long, RoomPostSummary> summaries = new HashMap<>();
		if (!postIds.isEmpty()) {
			named.query("select p.id, c.card_summary, c.image_key from room_posts p join comics c on c.id = p.comic_id "
					+ "where p.id in (:ids)", Map.of("ids", postIds), rs -> {
						String title = rs.getString("card_summary");
						summaries.put(rs.getLong("id"), new RoomPostSummary(rs.getLong("id"), title == null ? "" : title,
								url(rs.getString("image_key"))));
					});
		}
		Map<Long, RoomMember> people = authors(rows.stream().map(Row::userId).toList(), me, roomId);
		Set<Long> hidden = hiddenUsers(me);
		return rows.stream().map(r -> {
			boolean hide = hidden.contains(r.userId());
			return new RoomMessage(r.id(), r.type(), people.get(r.userId()), r.at(), r.postId(),
					hide || r.postId() == null ? null : summaries.get(r.postId()), hide ? null : r.body(), hide);
		}).toList();
	}

	private String url(String key) {
		return key == null ? null : storage.presignedUrl(key).toString();
	}

}
