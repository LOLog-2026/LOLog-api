package com.lolog.api.domain.inbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.friend.service.FriendService;
import com.lolog.api.domain.inbox.dto.InboxDtos.Badges;
import com.lolog.api.domain.inbox.dto.InboxDtos.InboxItem;
import com.lolog.api.domain.inbox.dto.InboxDtos.InboxPage;
import com.lolog.api.domain.inbox.entity.BadgeRead;
import com.lolog.api.domain.inbox.repository.BadgeReadRepository;
import com.lolog.api.domain.inbox.repository.InboxQueries;
import com.lolog.api.domain.inbox.repository.NoticeReadRepository;
import com.lolog.api.domain.inbox.repository.NoticeRepository;
import com.lolog.api.domain.notification.entity.Notification;
import com.lolog.api.domain.notification.repository.NotificationRepository;
import com.lolog.api.domain.room.service.RoomService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("InboxService")
class InboxServiceTest {

	private static final long ME = 7L;
	private static final OffsetDateTime AT = OffsetDateTime.of(2026, 11, 20, 12, 0, 0, 123456000, ZoneOffset.UTC);

	@Mock
	private InboxQueries queries;

	@Mock
	private NotificationRepository notifications;

	@Mock
	private NoticeRepository notices;

	@Mock
	private NoticeReadRepository noticeReads;

	@Mock
	private BadgeReadRepository badgeReads;

	@Mock
	private CardRepository cards;

	@Mock
	private FriendService friends;

	@Mock
	private RoomService rooms;

	private InboxService service;

	@BeforeEach
	void setUp() {
		service = new InboxService(queries, notifications, notices, noticeReads, badgeReads, cards, friends, rooms,
				JsonMapper.builder().build());
	}

	// ---- list ----

	@Test
	@DisplayName("탭이 ALL·NOTICE·NOTIFICATION이 아니면 tab 칸 INVALID_REQUEST다")
	void 탭이_틀리면_INVALID() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.list(ME, "FRIEND", null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("tab");
		verifyNoInteractions(queries);
	}

	@Test
	@DisplayName("커서 모양이 틀리면 cursor 칸 INVALID_REQUEST다")
	void 커서_모양이_틀리면_INVALID() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.list(ME, null, "123.COMIC.4"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("cursor");
		verifyNoInteractions(queries);
	}

	@Test
	@DisplayName("탭이 없으면 ALL로 보고 공지와 알림을 함께 30일 기준으로 21줄 읽는다")
	void 탭이_없으면_ALL() {
		// when
		service.list(ME, null, null);

		// then
		ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
		verify(queries).page(eq(ME), eq(true), eq(true), since.capture(), isNull(), eq(InboxService.PAGE_SIZE + 1));
		assertThat(since.getValue())
			.isCloseTo(OffsetDateTime.now(ZoneOffset.UTC).minusDays(InboxService.KEEP_DAYS), within(1, ChronoUnit.MINUTES));
	}

	@Test
	@DisplayName("NOTICE 탭은 공지만, NOTIFICATION 탭은 알림만 읽는다")
	void 탭에_따라_읽는_종류가_다르다() {
		// when
		service.list(ME, "NOTICE", null);
		service.list(ME, "NOTIFICATION", null);

		// then
		verify(queries).page(eq(ME), eq(true), eq(false), any(), isNull(), anyInt());
		verify(queries).page(eq(ME), eq(false), eq(true), any(), isNull(), anyInt());
	}

	@Test
	@DisplayName("커서를 마이크로초 시각·종류·ID로 풀어 넘긴다")
	void 커서를_풀어_넘긴다() {
		// given
		long micros = ChronoUnit.MICROS.between(Instant.EPOCH, AT.toInstant());

		// when
		service.list(ME, "ALL", micros + ".NOTICE.42");

		// then
		verify(queries).page(eq(ME), eq(true), eq(true), any(), eq(new InboxQueries.Cursor(AT, "NOTICE", 42L)),
				anyInt());
	}

	@Test
	@DisplayName("21줄이 오면 20줄만 주고 20번째 줄로 다음 커서를 만든다")
	void 다음_페이지가_있으면_커서를_준다() {
		// given
		List<InboxQueries.Row> rows = new ArrayList<>();
		for (int i = 0; i < InboxService.PAGE_SIZE + 1; i++) {
			rows.add(notificationRow(i + 1, AT.minusSeconds(i)));
		}
		given(queries.page(anyLong(), anyBoolean(), anyBoolean(), any(), any(), anyInt())).willReturn(rows);

		// when
		InboxPage page = service.list(ME, null, null);

		// then
		assertThat(page.items()).hasSize(InboxService.PAGE_SIZE);
		InboxItem last = page.items().getLast();
		long micros = ChronoUnit.MICROS.between(Instant.EPOCH, last.createdAt().toInstant());
		assertThat(page.nextCursor()).isEqualTo(micros + ".NOTIFICATION." + last.id());
		assertThat(last.id()).isEqualTo(20L);
	}

	@Test
	@DisplayName("20줄 이하면 다음 커서가 없다")
	void 다음_페이지가_없으면_커서가_null() {
		// given
		given(queries.page(anyLong(), anyBoolean(), anyBoolean(), any(), any(), anyInt()))
			.willReturn(List.of(notificationRow(1, AT)));

		// when
		InboxPage page = service.list(ME, null, null);

		// then
		assertThat(page.items()).hasSize(1);
		assertThat(page.nextCursor()).isNull();
	}

	@Test
	@DisplayName("공지는 제목·본문을, 알림은 종류와 data JSON을 맵으로 풀어 담는다")
	void 공지와_알림_내용을_나눠_담는다() {
		// given
		InboxQueries.Row notice = new InboxQueries.Row("NOTICE", 1L, AT, true, "점검 안내", "본문", null, null);
		given(queries.page(anyLong(), anyBoolean(), anyBoolean(), any(), any(), anyInt()))
			.willReturn(List.of(notificationRow(2, AT), notice));

		// when
		InboxPage page = service.list(ME, null, null);

		// then
		InboxItem n = page.items().get(0);
		assertThat(n.notice()).isNull();
		assertThat(n.notification().type()).isEqualTo("COMIC_DONE");
		assertThat(n.notification().data()).containsEntry("comicId", "3").containsEntry("cardId", "");
		InboxItem o = page.items().get(1);
		assertThat(o.notification()).isNull();
		assertThat(o.notice().title()).isEqualTo("점검 안내");
		assertThat(o.read()).isTrue();
	}

	// ---- read ----

	@Test
	@DisplayName("없는 공지를 읽으면 NOTICE_NOT_FOUND이고 읽음을 남기지 않는다")
	void 없는_공지는_NOTICE_NOT_FOUND() {
		// given
		given(notices.existsById(1L)).willReturn(false);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.read(ME, "notice", 1L));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTICE_NOT_FOUND);
		verify(noticeReads, never()).markRead(anyLong(), anyLong());
	}

	@Test
	@DisplayName("공지를 읽으면 공지 읽음 줄을 남긴다")
	void 공지를_읽으면_읽음을_남긴다() {
		// given
		given(notices.existsById(1L)).willReturn(true);

		// when
		service.read(ME, "notice", 1L);

		// then
		verify(noticeReads).markRead(1L, ME);
	}

	@Test
	@DisplayName("내 알림이 아니면 NOTIFICATION_NOT_FOUND다")
	void 내_알림이_아니면_NOTIFICATION_NOT_FOUND() {
		// given
		given(notifications.findByIdAndUserId(2L, ME)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.read(ME, "notification", 2L));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NOTIFICATION_NOT_FOUND);
	}

	@Test
	@DisplayName("알림을 읽으면 읽은 시각이 생긴다")
	void 알림을_읽으면_읽은_시각이_생긴다() {
		// given
		Notification n = new Notification();
		given(notifications.findByIdAndUserId(2L, ME)).willReturn(Optional.of(n));

		// when
		service.read(ME, "notification", 2L);

		// then
		assertThat(n.getReadAt()).isNotNull();
	}

	@Test
	@DisplayName("kind가 notice·notification이 아니면 kind 칸 INVALID_REQUEST다")
	void 읽기_kind가_틀리면_INVALID() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.read(ME, "NOTICE", 1L));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("kind");
		verifyNoInteractions(notices, notifications, noticeReads);
	}

	@Test
	@DisplayName("모두 읽으면 알림과 공지를 함께 읽음으로 한다")
	void 모두_읽으면_알림과_공지를_읽음으로() {
		// when
		service.readAll(ME);

		// then
		verify(notifications).markAllRead(eq(ME), any());
		verify(noticeReads).markAllRead(ME);
	}

	// ---- unread ----

	@Test
	@DisplayName("안 읽은 알림이 없어도 안 읽은 공지가 있으면 hasUnread가 true다")
	void 안_읽은_공지가_있으면_true() {
		// given
		given(notifications.existsByUserIdAndReadAtIsNullAndCreatedAtAfter(eq(ME), any())).willReturn(false);
		given(notices.existsUnread(ME)).willReturn(true);

		// when & then
		assertThat(service.unread(ME).hasUnread()).isTrue();
	}

	@Test
	@DisplayName("안 읽은 알림이 있으면 공지를 보지 않고 true다")
	void 안_읽은_알림이_있으면_공지를_보지_않는다() {
		// given
		given(notifications.existsByUserIdAndReadAtIsNullAndCreatedAtAfter(eq(ME), any())).willReturn(true);

		// when & then
		assertThat(service.unread(ME).hasUnread()).isTrue();
		verify(notices, never()).existsUnread(anyLong());
	}

	// ---- badges ----

	@Test
	@DisplayName("본 적 없는 점은 그 종류의 알림이 하나라도 있는지로 정한다")
	void 본_적_없으면_알림이_있는지로_정한다() {
		// given
		given(badgeReads.findById(any())).willReturn(Optional.empty());
		given(notifications.existsByUserIdAndTypeIn(eq(ME), any())).willReturn(false);
		given(notifications.existsByUserIdAndTypeIn(ME, List.of("COMIC_DONE", "COMIC_FAILED", "VIDEO_DONE", "VIDEO_FAILED")))
			.willReturn(true);

		// when
		Badges badges = service.badges(ME);

		// then
		assertThat(badges.comics()).isTrue();
		assertThat(badges.cards()).isFalse();
		verify(cards).existsBlogCard(ME);
		verify(notifications, never()).existsByUserIdAndTypeInAndCreatedAtAfter(anyLong(), any(), any());
	}

	@Test
	@DisplayName("본 적 있는 점은 마지막으로 본 뒤 알림이 왔는지로 정하고, 보관함은 새 블로그 글 카드도 본다")
	void 본_적_있으면_본_뒤로_정한다() {
		// given
		BadgeRead seen = new BadgeRead();
		ReflectionTestUtils.setField(seen, "seenAt", AT);
		given(badgeReads.findById(any())).willReturn(Optional.empty());
		given(badgeReads.findById(new BadgeRead.Key(ME, "cards"))).willReturn(Optional.of(seen));
		given(cards.existsBlogCardSince(ME, AT)).willReturn(true);

		// when
		Badges badges = service.badges(ME);

		// then
		assertThat(badges.cards()).isTrue();
		verify(notifications).existsByUserIdAndTypeInAndCreatedAtAfter(ME, List.of("CARDS_CREATED"), AT);
		verify(cards, never()).existsBlogCard(anyLong());
	}

	@Test
	@DisplayName("친구 점은 받은 요청으로, 방 점은 방 소식이나 해산 알림으로 정한다")
	void 친구와_방_점() {
		// given
		given(badgeReads.findById(any())).willReturn(Optional.empty());
		given(friends.hasReceivedRequests(ME)).willReturn(true);
		given(rooms.hasNews(ME)).willReturn(false);
		given(notifications.existsByUserIdAndTypeIn(eq(ME), any())).willReturn(false);
		given(notifications.existsByUserIdAndTypeIn(ME, List.of("ROOM_DISBANDED"))).willReturn(true);

		// when
		Badges badges = service.badges(ME);

		// then
		assertThat(badges.friends()).isTrue();
		assertThat(badges.rooms()).isTrue();
		assertThat(badges.profileCharacter()).isFalse();
	}

	// ---- seen ----

	@Test
	@DisplayName("모르는 점 종류면 kind 칸 INVALID_REQUEST다")
	void 모르는_점_종류면_INVALID() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.seen(ME, "videos"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("kind");
		verifyNoInteractions(badgeReads);
	}

	@Test
	@DisplayName("친구·종 버튼 점은 본 시각을 남기지 않는다")
	void 친구와_종_버튼은_남기지_않는다() {
		// when
		service.seen(ME, "friends");
		service.seen(ME, "inbox");

		// then
		verify(badgeReads, never()).markSeen(anyLong(), anyString());
	}

	@Test
	@DisplayName("보관함·만화·프로필 캐릭터·방 점은 본 시각을 남긴다")
	void 기능_화면_점은_본_시각을_남긴다() {
		// when
		service.seen(ME, "cards");
		service.seen(ME, "rooms");

		// then
		verify(badgeReads).markSeen(ME, "cards");
		verify(badgeReads).markSeen(ME, "rooms");
	}

	// ---- 도움 ----

	private static InboxQueries.Row notificationRow(long id, OffsetDateTime at) {
		return new InboxQueries.Row("NOTIFICATION", id, at, false, null, null, "COMIC_DONE",
				"{\"comicId\":\"3\",\"cardId\":\"\"}");
	}

}
