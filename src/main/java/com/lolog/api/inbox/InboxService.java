package com.lolog.api.inbox;

import com.lolog.api.common.ApiException;
import com.lolog.api.inbox.InboxDtos.Badges;
import com.lolog.api.inbox.InboxDtos.InboxItem;
import com.lolog.api.inbox.InboxDtos.InboxPage;
import com.lolog.api.inbox.InboxDtos.NoticeContent;
import com.lolog.api.inbox.InboxDtos.NotificationContent;
import com.lolog.api.inbox.InboxDtos.Unread;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// 종 버튼 화면(전체·공지사항·알림 탭), 읽음, 앱 안 새 소식 표시(LOLog-docs inbox.md, 초안).
// 알림은 PushService가 푸시를 보낼 때 남긴 줄이고, 30일이 지난 것은 보여 주지 않는다(NotificationCleanupJob이 지움).
@Service
public class InboxService {

	static final int PAGE_SIZE = 20;
	static final int KEEP_DAYS = 30;

	// 새 소식 표시를 "마지막으로 본 시각"으로 정하는 기능과 그 알림 종류.
	// 카드 영상은 앱의 컨텐츠 화면(예전 "만화")에 함께 있어 comics 점에 넣는다(inbox.md 4장 "새 푸시가 생기면 그 기능 화면에 점").
	// 내 목소리는 프로필 캐릭터 화면에서 만들어 profileCharacter 점에 넣는다(초안).
	private static final Map<String, List<String>> SEEN_KINDS = Map.of("cards", List.of("CARDS_CREATED"), "comics",
			List.of("COMIC_DONE", "COMIC_FAILED", "VIDEO_DONE", "VIDEO_FAILED"), "profileCharacter",
			List.of("PROFILE_CHARACTER_READY", "PROFILE_CHARACTER_FAILED", "VOICE_CLONE_READY", "VOICE_CLONE_FAILED"),
			"rooms", List.of("ROOM_DISBANDED"));
	private static final Set<String> BADGE_KINDS = Set.of("cards", "comics", "profileCharacter", "friends", "rooms",
			"inbox");

	// 커서: 마지막으로 받은 항목의 "시각(epoch 마이크로초).종류.ID". 앱은 해석하지 않고 그대로 돌려보낸다.
	private static final Pattern CURSOR = Pattern.compile("(\\d{1,19})\\.(NOTICE|NOTIFICATION)\\.(\\d{1,18})");

	private final JdbcTemplate jdbc;
	private final NamedParameterJdbcTemplate named;
	private final ObjectMapper objectMapper;

	public InboxService(JdbcTemplate jdbc, NamedParameterJdbcTemplate named, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.named = named;
		this.objectMapper = objectMapper;
	}

	// 최근 것이 위. 같은 시각이면 알림, 공지사항 순이다.
	@Transactional(readOnly = true)
	public InboxPage list(long me, String tab, String cursor) {
		String which = tab == null ? "ALL" : tab;
		if (!Set.of("ALL", "NOTICE", "NOTIFICATION").contains(which)) {
			throw ApiException.invalid("tab", "tab은 ALL, NOTICE, NOTIFICATION 중 하나예요.");
		}
		MapSqlParameterSource args = new MapSqlParameterSource("me", me).addValue("limit", PAGE_SIZE + 1)
			.addValue("since", OffsetDateTime.now(ZoneOffset.UTC).minusDays(KEEP_DAYS));
		String after = "";
		if (cursor != null) {
			Matcher m = CURSOR.matcher(cursor);
			if (!m.matches()) {
				throw ApiException.invalid("cursor", "cursor가 올바르지 않습니다.");
			}
			long micros = Long.parseLong(m.group(1));
			args.addValue("cAt", OffsetDateTime.ofInstant(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), ZoneOffset.UTC))
				.addValue("cKind", m.group(2))
				.addValue("cId", Long.parseLong(m.group(3)));
			after = "where (t.created_at, t.kind, t.id) < (:cAt, :cKind, :cId)";
		}
		List<String> parts = new ArrayList<>();
		if (!"NOTIFICATION".equals(which)) {
			parts.add("""
					select 'NOTICE' as kind, n.id, n.created_at,
					       exists (select 1 from notice_reads r where r.notice_id = n.id and r.user_id = :me) as is_read,
					       n.title, n.body, null as type, null as data
					from notices n
					""");
		}
		if (!"NOTICE".equals(which)) {
			parts.add("""
					select 'NOTIFICATION' as kind, x.id, x.created_at, x.read_at is not null as is_read,
					       null as title, null as body, x.type, x.data::text as data
					from notifications x where x.user_id = :me and x.created_at > :since
					""");
		}
		String sql = "select * from (" + String.join(" union all ", parts) + ") t " + after
				+ " order by t.created_at desc, t.kind desc, t.id desc limit :limit";
		List<InboxItem> items = new ArrayList<>(named.query(sql, args, (rs, n) -> {
			String kind = rs.getString("kind");
			boolean notice = "NOTICE".equals(kind);
			return new InboxItem(kind, rs.getLong("id"), rs.getObject("created_at", OffsetDateTime.class),
					rs.getBoolean("is_read"), notice ? new NoticeContent(rs.getString("title"), rs.getString("body")) : null,
					notice ? null : new NotificationContent(rs.getString("type"), data(rs.getString("data"))));
		}));
		String next = null;
		if (items.size() > PAGE_SIZE) {
			items = new ArrayList<>(items.subList(0, PAGE_SIZE));
			InboxItem last = items.getLast();
			Instant at = last.createdAt().toInstant();
			next = ChronoUnit.MICROS.between(Instant.EPOCH, at) + "." + last.kind() + "." + last.id();
		}
		return new InboxPage(items, next);
	}

	@Transactional(readOnly = true)
	public Unread unread(long me) {
		return new Unread(hasUnread(me));
	}

	// kind는 notice·notification. 이미 읽었어도 204다.
	@Transactional
	public void read(long me, String kind, long id) {
		if ("notice".equals(kind)) {
			Boolean exists = jdbc.queryForObject("select exists (select 1 from notices where id = ?)", Boolean.class, id);
			if (!Boolean.TRUE.equals(exists)) {
				throw new ApiException(HttpStatus.NOT_FOUND, "NOTICE_NOT_FOUND", "공지사항을 찾을 수 없어요.");
			}
			jdbc.update("insert into notice_reads (notice_id, user_id) values (?, ?) on conflict do nothing", id, me);
		}
		else if ("notification".equals(kind)) {
			int updated = jdbc.update(
					"update notifications set read_at = coalesce(read_at, current_timestamp) where id = ? and user_id = ?", id,
					me);
			if (updated == 0) {
				throw new ApiException(HttpStatus.NOT_FOUND, "NOTIFICATION_NOT_FOUND", "알림을 찾을 수 없어요.");
			}
		}
		else {
			throw ApiException.invalid("kind", "kind는 notice 또는 notification이에요.");
		}
	}

	// 지금까지 온 알림과 공지사항을 모두 읽음으로 한다.
	@Transactional
	public void readAll(long me) {
		jdbc.update("update notifications set read_at = current_timestamp where user_id = ? and read_at is null", me);
		jdbc.update("insert into notice_reads (notice_id, user_id) select id, ? from notices on conflict do nothing", me);
	}

	// 친구는 받은 요청이 있는지, 방은 받지 않은 글·받은 초대가 있거나 마지막으로 본 뒤 해산 알림이 왔는지로 정한다.
	// 보관함·만화·프로필 캐릭터는 마지막으로 본 뒤 그 알림이 왔는지로 정한다. 종 버튼은 안 읽은 알림·공지사항이 있는지다.
	@Transactional(readOnly = true)
	public Badges badges(long me) {
		boolean friends = Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists (select 1 from friendships where addressee_id = ? and status = 'PENDING')", Boolean.class, me));
		boolean roomUnread = Boolean.TRUE.equals(jdbc.queryForObject("""
				select exists (select 1 from room_members m join room_messages x on x.room_id = m.room_id
				  and x.user_id <> m.user_id and x.created_at > m.last_read_at where m.user_id = ?)
				  or exists (select 1 from room_invites i where i.user_id = ?)
				""", Boolean.class, me, me));
		return new Badges(newSince(me, "cards") || newBlogCards(me), newSince(me, "comics"), newSince(me, "profileCharacter"), friends,
				roomUnread || newSince(me, "rooms"), hasUnread(me));
	}

	// 그 화면을 열었음. 친구·종 버튼은 지금 있는 수·읽음으로 정하므로 따로 남기지 않는다.
	@Transactional
	public void seen(long me, String kind) {
		if (!BADGE_KINDS.contains(kind)) {
			throw ApiException.invalid("kind", "kind는 cards, comics, profileCharacter, friends, rooms, inbox 중 하나예요.");
		}
		if (SEEN_KINDS.containsKey(kind)) {
			jdbc.update("""
					insert into badge_reads (user_id, kind, seen_at) values (?, ?, clock_timestamp())
					on conflict (user_id, kind) do update set seen_at = excluded.seen_at
					""", me, kind);
		}
	}

	private boolean newSince(long me, String kind) {
		return Boolean.TRUE.equals(named.queryForObject("""
				select exists (select 1 from notifications x where x.user_id = :me and x.type in (:types)
				  and x.created_at > coalesce((select seen_at from badge_reads where user_id = :me and kind = :kind), '-infinity'))
				""", new MapSqlParameterSource("me", me).addValue("types", SEEN_KINDS.get(kind)).addValue("kind", kind),
				Boolean.class));
	}

	// 블로그 글 카드는 푸시·알림 목록 없이 보관함 점만 켠다(texts.md 2장). 마지막으로 본 뒤 생긴 블로그 글 카드가 있는지.
	private boolean newBlogCards(long me) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				select exists (select 1 from cards c join texts t on t.id = c.text_id
				  where c.user_id = ? and t.source = 'BLOG'
				  and c.created_at > coalesce((select seen_at from badge_reads where user_id = ? and kind = 'cards'), '-infinity'))
				""", Boolean.class, me, me));
	}

	private boolean hasUnread(long me) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				select exists (select 1 from notifications where user_id = ? and read_at is null and created_at > ?)
				  or exists (select 1 from notices n where not exists
				    (select 1 from notice_reads r where r.notice_id = n.id and r.user_id = ?))
				""", Boolean.class, me, OffsetDateTime.now(ZoneOffset.UTC).minusDays(KEEP_DAYS), me));
	}

	private Map<String, Object> data(String json) {
		return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
		});
	}

}
