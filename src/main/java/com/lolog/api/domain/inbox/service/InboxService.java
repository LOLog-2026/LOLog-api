package com.lolog.api.domain.inbox.service;

import com.lolog.api.domain.inbox.dto.InboxDtos.Badges;
import com.lolog.api.domain.inbox.dto.InboxDtos.InboxItem;
import com.lolog.api.domain.inbox.dto.InboxDtos.InboxPage;
import com.lolog.api.domain.inbox.dto.InboxDtos.NoticeContent;
import com.lolog.api.domain.inbox.dto.InboxDtos.NotificationContent;
import com.lolog.api.domain.inbox.dto.InboxDtos.Unread;
import com.lolog.api.domain.inbox.entity.BadgeRead;
import com.lolog.api.domain.inbox.repository.BadgeReadRepository;
import com.lolog.api.domain.inbox.repository.InboxQueries;
import com.lolog.api.domain.inbox.repository.NoticeReadRepository;
import com.lolog.api.domain.inbox.repository.NoticeRepository;
import com.lolog.api.domain.notification.repository.NotificationRepository;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.friend.service.FriendService;
import com.lolog.api.domain.room.service.RoomService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
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

	private final InboxQueries queries;
	private final NotificationRepository notifications;
	private final NoticeRepository notices;
	private final NoticeReadRepository noticeReads;
	private final BadgeReadRepository badgeReads;
	private final CardRepository cards;
	private final FriendService friends;
	private final RoomService rooms;
	private final ObjectMapper objectMapper;

	// 알림 리포지토리들, 새 소식을 확인할 다른 기능, JSON 도구를 받는다.
	public InboxService(InboxQueries queries, NotificationRepository notifications, NoticeRepository notices,
			NoticeReadRepository noticeReads, BadgeReadRepository badgeReads, CardRepository cards, FriendService friends,
			RoomService rooms, ObjectMapper objectMapper) {
		this.queries = queries;
		this.notifications = notifications;
		this.notices = notices;
		this.noticeReads = noticeReads;
		this.badgeReads = badgeReads;
		this.cards = cards;
		this.friends = friends;
		this.rooms = rooms;
		this.objectMapper = objectMapper;
	}

	// 최근 것이 위. 같은 시각이면 알림, 공지사항 순이다.
	@Transactional(readOnly = true)
	public InboxPage list(long me, String tab, String cursor) {
		String which = tab == null ? "ALL" : tab;
		if (!Set.of("ALL", "NOTICE", "NOTIFICATION").contains(which)) {
			throw ApiException.invalid("tab");
		}
		InboxQueries.Cursor after = null;
		if (cursor != null) {
			Matcher m = CURSOR.matcher(cursor);
			if (!m.matches()) {
				throw ApiException.invalid("cursor");
			}
			long micros = Long.parseLong(m.group(1));
			after = new InboxQueries.Cursor(
					OffsetDateTime.ofInstant(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), ZoneOffset.UTC), m.group(2),
					Long.parseLong(m.group(3)));
		}
		List<InboxItem> items = new ArrayList<>(queries.page(me, !"NOTIFICATION".equals(which), !"NOTICE".equals(which),
				OffsetDateTime.now(ZoneOffset.UTC).minusDays(KEEP_DAYS), after, PAGE_SIZE + 1).stream().map(r -> {
					boolean notice = "NOTICE".equals(r.kind());
					return new InboxItem(r.kind(), r.id(), r.createdAt(), r.read(),
							notice ? new NoticeContent(r.title(), r.body()) : null,
							notice ? null : new NotificationContent(r.type(), data(r.data())));
				}).toList());
		String next = null;
		if (items.size() > PAGE_SIZE) {
			items = new ArrayList<>(items.subList(0, PAGE_SIZE));
			InboxItem last = items.getLast();
			Instant at = last.createdAt().toInstant();
			next = ChronoUnit.MICROS.between(Instant.EPOCH, at) + "." + last.kind() + "." + last.id();
		}
		return new InboxPage(items, next);
	}

	// 안 읽은 알림·공지사항이 있는지 돌려준다.
	@Transactional(readOnly = true)
	public Unread unread(long me) {
		return new Unread(hasUnread(me));
	}

	// kind는 notice·notification. 이미 읽었어도 204다.
	@Transactional
	public void read(long me, String kind, long id) {
		if ("notice".equals(kind)) {
			if (!notices.existsById(id)) {
				throw new ApiException(ErrorCode.NOTICE_NOT_FOUND);
			}
			noticeReads.markRead(id, me);
		}
		else if ("notification".equals(kind)) {
			notifications.findByIdAndUserId(id, me)
				.orElseThrow(() -> new ApiException(ErrorCode.NOTIFICATION_NOT_FOUND))
				.markRead();
		}
		else {
			throw ApiException.invalid("kind");
		}
	}

	// 지금까지 온 알림과 공지사항을 모두 읽음으로 한다.
	@Transactional
	public void readAll(long me) {
		notifications.markAllRead(me, OffsetDateTime.now());
		noticeReads.markAllRead(me);
	}

	// 친구는 받은 요청이 있는지, 방은 받지 않은 글·받은 초대가 있거나 마지막으로 본 뒤 해산 알림이 왔는지로 정한다.
	// 보관함·만화·프로필 캐릭터는 마지막으로 본 뒤 그 알림이 왔는지로 정한다. 종 버튼은 안 읽은 알림·공지사항이 있는지다.
	@Transactional(readOnly = true)
	public Badges badges(long me) {
		boolean friendRequests = friends.hasReceivedRequests(me);
		boolean roomNews = rooms.hasNews(me);
		return new Badges(newSince(me, "cards") || newBlogCards(me), newSince(me, "comics"), newSince(me, "profileCharacter"),
				friendRequests, roomNews || newSince(me, "rooms"), hasUnread(me));
	}

	// 그 화면을 열었음. 친구·종 버튼은 지금 있는 수·읽음으로 정하므로 따로 남기지 않는다.
	@Transactional
	public void seen(long me, String kind) {
		if (!BADGE_KINDS.contains(kind)) {
			throw ApiException.invalid("kind");
		}
		if (SEEN_KINDS.containsKey(kind)) {
			badgeReads.markSeen(me, kind);
		}
	}

	// 마지막으로 본 뒤 그 기능의 알림이 왔는지 본다.
	private boolean newSince(long me, String kind) {
		OffsetDateTime seen = seenAt(me, kind);
		List<String> types = SEEN_KINDS.get(kind);
		return seen == null ? notifications.existsByUserIdAndTypeIn(me, types)
				: notifications.existsByUserIdAndTypeInAndCreatedAtAfter(me, types, seen);
	}

	// 블로그 글 카드는 푸시·알림 목록 없이 보관함 점만 켠다(texts.md 2장). 마지막으로 본 뒤 생긴 블로그 글 카드가 있는지.
	private boolean newBlogCards(long me) {
		OffsetDateTime seen = seenAt(me, "cards");
		return seen == null ? cards.existsBlogCard(me) : cards.existsBlogCardSince(me, seen);
	}

	// 30일 안의 안 읽은 알림이나 안 읽은 공지사항이 있는지 본다.
	private boolean hasUnread(long me) {
		return notifications.existsByUserIdAndReadAtIsNullAndCreatedAtAfter(me,
				OffsetDateTime.now(ZoneOffset.UTC).minusDays(KEEP_DAYS)) || notices.existsUnread(me);
	}

	// 그 기능의 새 소식 점을 마지막으로 본 시각. 본 적이 없으면 null.
	private OffsetDateTime seenAt(long me, String kind) {
		return badgeReads.findById(new BadgeRead.Key(me, kind)).map(BadgeRead::getSeenAt).orElse(null);
	}

	// 알림 data JSON을 맵으로 바꾼다.
	private Map<String, Object> data(String json) {
		return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
		});
	}

}
