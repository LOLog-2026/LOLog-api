package com.lolog.api.room;

import com.lolog.api.common.ApiException;
import com.lolog.api.room.RoomDtos.MessageList;
import com.lolog.api.room.RoomDtos.PostList;
import com.lolog.api.room.RoomDtos.RoomActivity;
import com.lolog.api.room.RoomDtos.RoomItem;
import com.lolog.api.room.RoomDtos.RoomList;
import com.lolog.api.room.RoomDtos.RoomMember;
import com.lolog.api.room.RoomDtos.RoomMessage;
import com.lolog.api.room.RoomDtos.RoomPost;
import com.lolog.api.room.RoomDtos.RoomPostSummary;
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
// 방·올린 만화·좋아요·대화는 외래 키로 이어 두어, 방을 지우면 모두 지워지고 만화를 지우면 그 만화를 올린 기록이 지워진다.
@Service
public class RoomService {

	static final int NAME_MAX = 20;
	static final int MEMBER_MAX = 6;
	static final int COMMENT_MAX = 200;
	static final int MESSAGE_LIMIT = 200;

	private final JdbcTemplate jdbc;
	private final NamedParameterJdbcTemplate named;
	private final ImageStorage storage;

	public RoomService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, ImageStorage storage) {
		this.jdbc = jdbc;
		this.named = named;
		this.storage = storage;
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

	@Transactional
	public RoomItem create(long me, String name, List<Long> memberIds) {
		String trimmed = name == null ? "" : name.strip();
		int length = trimmed.codePointCount(0, trimmed.length());
		if (length < 1 || length > NAME_MAX) {
			throw ApiException.invalid("name", "방 이름은 1~20자예요.");
		}
		if (memberIds == null || memberIds.isEmpty()) {
			throw ApiException.invalid("memberIds", "친구를 한 명 이상 골라 주세요.");
		}
		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < memberIds.size(); i++) {
			Long id = memberIds.get(i);
			if (id == null || id == me || !seen.add(id)) {
				throw ApiException.invalid("memberIds[" + i + "]", "같은 사람이나 나는 넣을 수 없어요.");
			}
		}
		if (memberIds.size() + 1 > MEMBER_MAX) {
			throw new ApiException(HttpStatus.CONFLICT, "ROOM_MEMBER_LIMIT", "나를 포함해 6명까지예요.");
		}
		List<String> nickname = jdbc.queryForList("select nickname from user_profiles where user_id = ?", String.class, me);
		if (nickname.isEmpty() || nickname.getFirst() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "PROFILE_REQUIRED", "닉네임을 먼저 정해 주세요.");
		}
		for (int i = 0; i < memberIds.size(); i++) {
			Boolean friend = jdbc.queryForObject("""
					select exists (select 1 from friendships where status = 'ACCEPTED'
					  and ((requester_id = ? and addressee_id = ?) or (requester_id = ? and addressee_id = ?)))
					""", Boolean.class, me, memberIds.get(i), memberIds.get(i), me);
			if (!Boolean.TRUE.equals(friend)) {
				throw new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "친구가 아닌 사람이에요.",
						"memberIds[" + i + "]");
			}
		}
		long roomId = jdbc.queryForObject("insert into rooms (name, created_by) values (?, ?) returning id", Long.class,
				trimmed, me);
		// 들어온 순서를 남기려고 줄마다 실제 시각을 쓴다(나 → 고른 순서).
		List<Long> all = new ArrayList<>();
		all.add(me);
		all.addAll(memberIds);
		for (Long userId : all) {
			jdbc.update("insert into room_members (room_id, user_id, created_at) values (?, ?, clock_timestamp())", roomId,
					userId);
		}
		// "방을 만들었어요" 줄. 마지막 소식에도 보인다(앱 PR #53 화면과 같게).
		addMessage(roomId, me, "CREATED", null, null);
		return items(List.of(roomId), me).getFirst();
	}

	@Transactional(readOnly = true)
	public RoomItem room(long me, long roomId) {
		requireMember(me, roomId);
		return items(List.of(roomId), me).getFirst();
	}

	// 마지막 한 명이 나가면 방과 올린 기록·대화를 모두 지운다. 내가 올린 만화와 남긴 글은 남는다(초안).
	@Transactional
	public void leave(long me, long roomId) {
		requireMember(me, roomId);
		jdbc.update("delete from room_members where room_id = ? and user_id = ?", roomId, me);
		jdbc.update("delete from rooms r where r.id = ? and not exists (select 1 from room_members m where m.room_id = r.id)",
				roomId);
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

	// 오래된 줄이 위, 최근 200줄까지. 부르면 이 방의 받지 않은 글이 0이 된다.
	@Transactional
	public MessageList messages(long me, long roomId) {
		requireMember(me, roomId);
		List<Row> rows = jdbc.query("""
				select * from (
				  select x.id, x.user_id, x.message_type, x.post_id, x.body, x.created_at
				  from room_messages x where x.room_id = ? order by x.id desc limit ?
				) recent order by id
				""", (rs, n) -> new Row(rs.getLong("id"), rs.getLong("user_id"), rs.getString("message_type"),
				(Long) rs.getObject("post_id"), rs.getString("body"), rs.getObject("created_at", OffsetDateTime.class)),
				roomId, MESSAGE_LIMIT);
		jdbc.update("update room_members set last_read_at = clock_timestamp() where room_id = ? and user_id = ?", roomId, me);
		return new MessageList(messages(me, rows));
	}

	@Transactional
	public RoomMessage comment(long me, long roomId, String text, Long postId) {
		requireMember(me, roomId);
		String trimmed = text == null ? "" : text.strip();
		int length = trimmed.codePointCount(0, trimmed.length());
		if (length < 1 || length > COMMENT_MAX) {
			throw ApiException.invalid("text", "댓글은 1~200자예요.");
		}
		if (postId != null) {
			postAuthor(roomId, postId);
		}
		long id = addMessage(roomId, me, "COMMENT", postId, trimmed);
		Row row = jdbc.queryForObject("select id, user_id, message_type, post_id, body, created_at from room_messages where id = ?",
				(rs, n) -> new Row(rs.getLong("id"), rs.getLong("user_id"), rs.getString("message_type"),
						(Long) rs.getObject("post_id"), rs.getString("body"),
						rs.getObject("created_at", OffsetDateTime.class)),
				id);
		return messages(me, List.of(row)).getFirst();
	}

	// ---- 안에서 쓰는 것 ----

	private record Row(long id, long userId, String type, Long postId, String body, OffsetDateTime at) {
	}

	private void requireMember(long me, long roomId) {
		Boolean member = jdbc.queryForObject(
				"select exists (select 1 from room_members where room_id = ? and user_id = ?)", Boolean.class, roomId, me);
		if (!Boolean.TRUE.equals(member)) {
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

	// 회원들의 닉네임과 프로필 캐릭터 썸네일
	private Map<Long, RoomMember> people(Collection<Long> userIds, long me) {
		Map<Long, RoomMember> result = new HashMap<>();
		if (userIds.isEmpty()) {
			return result;
		}
		named.query("select user_id, nickname, character_thumb_key from user_profiles where user_id in (:ids)",
				Map.of("ids", userIds), rs -> {
					long id = rs.getLong("user_id");
					String thumb = rs.getString("character_thumb_key");
					String nickname = rs.getString("nickname");
					result.put(id, new RoomMember(id, nickname == null ? "" : nickname,
							thumb == null ? null : storage.presignedUrl(thumb).toString(), id == me));
				});
		for (Long id : userIds) {
			result.putIfAbsent(id, new RoomMember(id, "", null, id == me));
		}
		return result;
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
		Map<Long, RoomMember> people = people(everyone, me);
		Map<Long, RoomItem> byId = new LinkedHashMap<>();
		named.query("select id, name, created_at from rooms where id in (:ids)", ids, rs -> {
			long id = rs.getLong("id");
			Last l = last.get(id);
			RoomActivity activity = l == null ? null
					: new RoomActivity("COMMENT".equals(l.type()) ? "COMMENTED" : l.type(),
							people.get(l.userId()).nickname(), l.userId() == me, l.at(), l.body());
			byId.put(id, new RoomItem(id, rs.getString("name"),
					memberIds.getOrDefault(id, List.of()).stream().map(people::get).toList(), activity,
					unread.getOrDefault(id, 0), rs.getObject("created_at", OffsetDateTime.class)));
		});
		return roomIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
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
		Map<Long, RoomMember> people = people(rows.stream().map(P::userId).toList(), me);
		return rows.stream()
			.map(p -> new RoomPost(p.id(), roomId, p.comicId(), p.title() == null ? "" : p.title(), url(p.imageKey()),
					people.get(p.userId()), p.at(), p.likes(), p.liked(), p.comments()))
			.toList();
	}

	private List<RoomMessage> messages(long me, List<Row> rows) {
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
		Map<Long, RoomMember> people = people(rows.stream().map(Row::userId).toList(), me);
		return rows.stream()
			.map(r -> new RoomMessage(r.id(), r.type(), people.get(r.userId()), r.at(), r.postId(),
					r.postId() == null ? null : summaries.get(r.postId()), r.body()))
			.toList();
	}

	private String url(String key) {
		return key == null ? null : storage.presignedUrl(key).toString();
	}

}
