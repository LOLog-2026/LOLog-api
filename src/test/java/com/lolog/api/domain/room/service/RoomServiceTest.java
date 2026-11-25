package com.lolog.api.domain.room.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.comic.entity.Comic;
import com.lolog.api.domain.comic.entity.ComicStatus;
import com.lolog.api.domain.comic.repository.ComicRepository;
import com.lolog.api.domain.friend.repository.FriendshipRepository;
import com.lolog.api.domain.friend.repository.UserBlockRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.room.dto.RoomDtos.MessageList;
import com.lolog.api.domain.room.dto.RoomDtos.RoomItem;
import com.lolog.api.domain.room.dto.RoomDtos.RoomMessage;
import com.lolog.api.domain.room.entity.Room;
import com.lolog.api.domain.room.entity.RoomPost;
import com.lolog.api.domain.room.repository.RoomInviteRepository;
import com.lolog.api.domain.room.repository.RoomItemView;
import com.lolog.api.domain.room.repository.RoomMemberRepository;
import com.lolog.api.domain.room.repository.RoomMessageRepository;
import com.lolog.api.domain.room.repository.RoomMessageView;
import com.lolog.api.domain.room.repository.RoomPostLikeRepository;
import com.lolog.api.domain.room.repository.RoomPostRepository;
import com.lolog.api.domain.room.repository.RoomPostView;
import com.lolog.api.domain.room.repository.RoomRepository;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.domain.user.repository.UserSuspensionRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoomService")
class RoomServiceTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;
	private static final long THIRD = 3L;
	private static final long ROOM_ID = 10L;
	private static final long INVITE_ID = 20L;
	private static final long POST_ID = 30L;
	private static final long COMIC_ID = 40L;
	private static final OffsetDateTime AT = OffsetDateTime.parse("2026-11-20T12:00:00Z");

	@Mock
	private RoomRepository rooms;

	@Mock
	private RoomMemberRepository members;

	@Mock
	private RoomInviteRepository invites;

	@Mock
	private RoomPostRepository posts;

	@Mock
	private RoomPostLikeRepository likes;

	@Mock
	private RoomMessageRepository messages;

	@Mock
	private UserProfileRepository profiles;

	@Mock
	private UserSuspensionRepository suspensions;

	@Mock
	private FriendshipRepository friendships;

	@Mock
	private UserBlockRepository blocks;

	@Mock
	private ComicRepository comics;

	@Mock
	private ImageStorage storage;

	@Mock
	private PushService push;

	private RoomService service;

	@BeforeEach
	void setUp() {
		service = new RoomService(rooms, members, invites, posts, likes, messages, profiles, suspensions, friendships,
				blocks, comics, storage, push);
	}

	// ---- create ----

	@Test
	@DisplayName("방 이름이 공백뿐이면 name 칸 INVALID_REQUEST다")
	void 이름이_공백이면_INVALID() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.create(ME, "   ", List.of(OTHER)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("name");
		verifyNoInteractions(rooms, invites, push);
	}

	@Test
	@DisplayName("방 이름이 21자면 INVALID_REQUEST이고, 이모지는 한 글자로 세어 20자까지 된다")
	void 이름은_20자까지() {
		// given
		String emoji20 = "😀".repeat(RoomService.NAME_MAX);
		givenCreatable();

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.create(ME, "가".repeat(RoomService.NAME_MAX + 1), List.of(OTHER)));
		service.create(ME, emoji20, List.of(OTHER));

		// then
		assertThat(e.getField()).isEqualTo("name");
		ArgumentCaptor<Room> saved = ArgumentCaptor.forClass(Room.class);
		verify(rooms).save(saved.capture());
		assertThat(saved.getValue().getName()).isEqualTo(emoji20);
	}

	@Test
	@DisplayName("고른 친구가 없으면 memberIds 칸 INVALID_REQUEST다")
	void 친구를_안_고르면_INVALID() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.create(ME, "방", List.of()));

		// then
		assertThat(e.getField()).isEqualTo("memberIds");
	}

	@Test
	@DisplayName("같은 친구를 두 번 고르면 두 번째 자리를 가리킨다")
	void 같은_친구가_두_번이면_INVALID() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.create(ME, "방", List.of(OTHER, OTHER)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("memberIds[1]");
	}

	@Test
	@DisplayName("나를 고르면 그 자리를 가리킨다")
	void 나를_고르면_INVALID() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.create(ME, "방", List.of(OTHER, ME)));

		// then
		assertThat(e.getField()).isEqualTo("memberIds[1]");
	}

	@Test
	@DisplayName("나까지 7명이면 ROOM_MEMBER_LIMIT이고 프로필을 잠그지 않는다")
	void 일곱_명이면_ROOM_MEMBER_LIMIT() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.create(ME, "방", List.of(2L, 3L, 4L, 5L, 6L, 7L)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_MEMBER_LIMIT);
		verifyNoInteractions(profiles);
	}

	@Test
	@DisplayName("내 닉네임이 없으면 PROFILE_REQUIRED다")
	void 닉네임이_없으면_PROFILE_REQUIRED() {
		// given
		given(profiles.findByIdForUpdate(ME)).willReturn(Optional.of(profile(ME, null)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.create(ME, "방", List.of(OTHER)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_REQUIRED);
		verify(rooms, never()).save(any());
	}

	@Test
	@DisplayName("친구가 아닌 사람을 고르면 그 자리를 가리키는 USER_NOT_FOUND다")
	void 친구가_아니면_USER_NOT_FOUND() {
		// given
		given(profiles.findByIdForUpdate(ME)).willReturn(Optional.of(profile(ME, "나")));
		given(friendships.areFriends(ME, OTHER)).willReturn(true);
		given(friendships.areFriends(ME, THIRD)).willReturn(false);

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.create(ME, "방", List.of(OTHER, THIRD)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
		assertThat(e.getField()).isEqualTo("memberIds[1]");
		verify(rooms, never()).save(any());
	}

	@Test
	@DisplayName("이미 들어간 방이 20개면 ROOM_LIMIT다")
	void 방이_20개면_ROOM_LIMIT() {
		// given
		given(profiles.findByIdForUpdate(ME)).willReturn(Optional.of(profile(ME, "나")));
		given(friendships.areFriends(ME, OTHER)).willReturn(true);
		given(members.countByUserId(ME)).willReturn((long) RoomService.ROOM_LIMIT);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.create(ME, "방", List.of(OTHER)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_LIMIT);
		verify(rooms, never()).save(any());
	}

	@Test
	@DisplayName("방을 만들면 나만 멤버로 넣고 CREATED 줄을 남긴 뒤 친구에게 초대와 푸시를 보낸다")
	void 방을_만들면_나만_넣고_초대한다() {
		// given
		givenCreatable();
		given(invites.insert(ROOM_ID, OTHER, ME)).willReturn(INVITE_ID);

		// when
		RoomItem item = service.create(ME, "  우리 방 ", List.of(OTHER));

		// then
		assertThat(item.id()).isEqualTo(ROOM_ID);
		ArgumentCaptor<Room> saved = ArgumentCaptor.forClass(Room.class);
		verify(rooms).save(saved.capture());
		assertThat(saved.getValue().getName()).isEqualTo("우리 방");
		assertThat(saved.getValue().getOwnerId()).isEqualTo(ME);
		verify(members).insert(ROOM_ID, ME);
		verify(members, never()).insert(ROOM_ID, OTHER);
		verify(messages).insert(ROOM_ID, ME, "CREATED", null, null);
		verify(rooms).touchActivity(ROOM_ID);
		verify(push).notify(OTHER, PushService.roomInvite(INVITE_ID, ROOM_ID, "우리 방"));
	}

	// ---- room / rename ----

	@Test
	@DisplayName("멤버가 아니면 방을 볼 때 ROOM_NOT_FOUND다")
	void 멤버가_아니면_ROOM_NOT_FOUND() {
		// given
		given(members.existsByRoomIdAndUserId(ROOM_ID, ME)).willReturn(false);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.room(ME, ROOM_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND);
		verify(rooms, never()).findItems(anyLong(), any());
	}

	@Test
	@DisplayName("방장이 아니면 이름을 바꿀 때 ROOM_OWNER_ONLY이고 이름이 그대로다")
	void 방장이_아니면_이름을_못_바꾼다() {
		// given
		givenMember(ME);
		Room room = room(OTHER);
		given(rooms.findById(ROOM_ID)).willReturn(Optional.of(room));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.rename(ME, ROOM_ID, "새 이름"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_OWNER_ONLY);
		assertThat(room.getName()).isEqualTo("방");
	}

	@Test
	@DisplayName("방장이면 앞뒤 공백을 뺀 이름으로 바꾼다")
	void 방장이면_이름을_바꾼다() {
		// given
		givenMember(ME);
		Room room = room(ME);
		given(rooms.findById(ROOM_ID)).willReturn(Optional.of(room));
		given(rooms.findItems(eq(ME), any())).willReturn(List.of(itemView()));

		// when
		service.rename(ME, ROOM_ID, " 새 이름 ");

		// then
		assertThat(room.getName()).isEqualTo("새 이름");
	}

	// ---- disband ----

	@Test
	@DisplayName("방장이 아니면 해산할 때 ROOM_OWNER_ONLY이고 방을 지우지 않는다")
	void 방장이_아니면_해산_못_한다() {
		// given
		givenMember(ME);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room(OTHER)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.disband(ME, ROOM_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_OWNER_ONLY);
		verify(rooms, never()).delete(any());
		verifyNoInteractions(push);
	}

	@Test
	@DisplayName("잠그는 사이 방이 지워졌으면 해산할 때 ROOM_NOT_FOUND다")
	void 잠그는_사이_지워졌으면_ROOM_NOT_FOUND() {
		// given
		givenMember(ME);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.disband(ME, ROOM_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_NOT_FOUND);
	}

	@Test
	@DisplayName("해산하면 방을 지우고 나를 뺀 멤버에게 방 이름으로 푸시한다")
	void 해산하면_다른_멤버에게_푸시한다() {
		// given
		givenMember(ME);
		Room room = room(ME);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room));
		given(members.findOtherUserIds(ROOM_ID, ME)).willReturn(List.of(OTHER, THIRD));

		// when
		service.disband(ME, ROOM_ID);

		// then
		verify(rooms).delete(room);
		verify(push).notify(OTHER, PushService.roomDisbanded("방"));
		verify(push).notify(THIRD, PushService.roomDisbanded("방"));
		verify(push, never()).notify(eq(ME), any());
	}

	// ---- kick ----

	@Test
	@DisplayName("방장이 나를 내보내려 하면 userId 칸 INVALID_REQUEST다")
	void 나를_내보내면_INVALID() {
		// given
		givenMember(ME);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room(ME)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.kick(ME, ROOM_ID, ME));

		// then
		assertThat(e.getField()).isEqualTo("userId");
		verify(members, never()).deleteMember(anyLong(), anyLong());
	}

	@Test
	@DisplayName("멤버가 아닌 사람을 내보내면 USER_NOT_FOUND다")
	void 멤버가_아닌_사람을_내보내면_USER_NOT_FOUND() {
		// given
		givenMember(ME);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room(ME)));
		given(members.deleteMember(ROOM_ID, OTHER)).willReturn(0);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.kick(ME, ROOM_ID, OTHER));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	// ---- leave ----

	@Test
	@DisplayName("다른 멤버가 있는 방장이 새 방장을 고르지 않으면 NEW_OWNER_REQUIRED이고 나가지 않는다")
	void 방장이_새_방장을_안_고르면_NEW_OWNER_REQUIRED() {
		// given
		givenMember(ME);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room(ME)));
		given(members.countByRoomIdAndUserIdNot(ROOM_ID, ME)).willReturn(2L);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.leave(ME, ROOM_ID, null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.NEW_OWNER_REQUIRED);
		verify(members, never()).deleteMember(anyLong(), anyLong());
	}

	@Test
	@DisplayName("새 방장이 이 방 멤버가 아니면 newOwnerId 칸 INVALID_REQUEST다")
	void 새_방장이_멤버가_아니면_INVALID() {
		// given
		givenMember(ME);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room(ME)));
		given(members.countByRoomIdAndUserIdNot(ROOM_ID, ME)).willReturn(1L);
		given(members.existsByRoomIdAndUserId(ROOM_ID, THIRD)).willReturn(false);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.leave(ME, ROOM_ID, THIRD));

		// then
		assertThat(e.getField()).isEqualTo("newOwnerId");
		verify(members, never()).deleteMember(anyLong(), anyLong());
	}

	@Test
	@DisplayName("새 방장으로 나를 고르면 newOwnerId 칸 INVALID_REQUEST다")
	void 새_방장이_나면_INVALID() {
		// given
		givenMember(ME);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room(ME)));
		given(members.countByRoomIdAndUserIdNot(ROOM_ID, ME)).willReturn(1L);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.leave(ME, ROOM_ID, ME));

		// then
		assertThat(e.getField()).isEqualTo("newOwnerId");
	}

	@Test
	@DisplayName("방장이 새 방장을 고르고 나가면 방장을 바꾼 뒤 나를 지우고 빈 방 정리를 한다")
	void 방장이_넘기고_나간다() {
		// given
		givenMember(ME);
		Room room = room(ME);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room));
		given(members.countByRoomIdAndUserIdNot(ROOM_ID, ME)).willReturn(1L);
		given(members.existsByRoomIdAndUserId(ROOM_ID, OTHER)).willReturn(true);

		// when
		service.leave(ME, ROOM_ID, OTHER);

		// then
		assertThat(room.getOwnerId()).isEqualTo(OTHER);
		InOrder order = inOrder(members, rooms);
		order.verify(members).deleteMember(ROOM_ID, ME);
		order.verify(rooms).deleteIfEmpty(ROOM_ID);
		order.verify(rooms).handOverIfOwnerLeft(ROOM_ID);
	}

	@Test
	@DisplayName("방장이 아니면 새 방장 없이 나간다")
	void 멤버는_그냥_나간다() {
		// given
		givenMember(ME);
		Room room = room(OTHER);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room));
		given(members.countByRoomIdAndUserIdNot(ROOM_ID, ME)).willReturn(1L);

		// when
		service.leave(ME, ROOM_ID, null);

		// then
		assertThat(room.getOwnerId()).isEqualTo(OTHER);
		verify(members).deleteMember(ROOM_ID, ME);
	}

	// ---- invite ----

	@Test
	@DisplayName("이미 멤버인 사람을 초대하면 그 자리를 가리키는 INVALID_REQUEST다")
	void 멤버를_초대하면_INVALID() {
		// given
		givenMember(ME);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room(ME)));
		given(friendships.areFriends(eq(ME), anyLong())).willReturn(true);
		given(members.existsByRoomIdAndUserId(ROOM_ID, OTHER)).willReturn(false);
		given(members.existsByRoomIdAndUserId(ROOM_ID, THIRD)).willReturn(true);

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.invite(ME, ROOM_ID, List.of(OTHER, THIRD)));

		// then
		assertThat(e.getField()).isEqualTo("userIds[1]");
		verify(invites, never()).insert(anyLong(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("멤버와 기다리는 초대에 새 초대를 더해 6명을 넘으면 ROOM_MEMBER_LIMIT다")
	void 인원을_넘으면_ROOM_MEMBER_LIMIT() {
		// given
		givenMember(ME);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room(ME)));
		given(friendships.areFriends(eq(ME), anyLong())).willReturn(true);
		given(members.countByRoomId(ROOM_ID)).willReturn(4L);
		given(invites.countByRoomId(ROOM_ID)).willReturn(1L);

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.invite(ME, ROOM_ID, List.of(OTHER, THIRD)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_MEMBER_LIMIT);
		verify(invites, never()).insert(anyLong(), anyLong(), anyLong());
		verifyNoInteractions(push);
	}

	@Test
	@DisplayName("이미 초대를 기다리는 사람은 인원에 다시 세지 않고 초대·푸시를 다시 보내지 않는다")
	void 기다리는_초대는_다시_보내지_않는다() {
		// given
		givenMember(ME);
		given(rooms.findByIdForUpdate(ROOM_ID)).willReturn(Optional.of(room(ME)));
		given(friendships.areFriends(eq(ME), anyLong())).willReturn(true);
		given(invites.existsByRoomIdAndUserId(ROOM_ID, OTHER)).willReturn(true);
		given(members.countByRoomId(ROOM_ID)).willReturn(4L);
		given(invites.countByRoomId(ROOM_ID)).willReturn(1L);
		given(invites.insert(ROOM_ID, THIRD, ME)).willReturn(INVITE_ID);

		// when
		service.invite(ME, ROOM_ID, List.of(OTHER, THIRD));

		// then
		verify(invites, never()).insert(ROOM_ID, OTHER, ME);
		verify(push).notify(THIRD, PushService.roomInvite(INVITE_ID, ROOM_ID, "방"));
		verify(push, never()).notify(eq(OTHER), any());
	}

	@Test
	@DisplayName("지운 초대가 없으면 초대 취소는 ROOM_INVITE_NOT_FOUND다")
	void 취소할_초대가_없으면_ROOM_INVITE_NOT_FOUND() {
		// given
		givenMember(ME);
		given(rooms.findById(ROOM_ID)).willReturn(Optional.of(room(ME)));
		given(invites.deleteByIdAndRoomId(INVITE_ID, ROOM_ID)).willReturn(0);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.cancelInvite(ME, ROOM_ID, INVITE_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_INVITE_NOT_FOUND);
	}

	// ---- acceptInvite / declineInvite ----

	@Test
	@DisplayName("내가 받은 초대가 아니면 수락할 때 ROOM_INVITE_NOT_FOUND다")
	void 받은_초대가_아니면_ROOM_INVITE_NOT_FOUND() {
		// given
		given(invites.findRoomIdByIdAndUserId(INVITE_ID, ME)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.acceptInvite(ME, INVITE_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_INVITE_NOT_FOUND);
		verify(rooms, never()).findByIdForUpdate(anyLong());
	}

	@Test
	@DisplayName("잠그는 사이 방장이 초대를 취소했으면 ROOM_INVITE_NOT_FOUND이고 멤버로 넣지 않는다")
	void 잠그는_사이_취소됐으면_ROOM_INVITE_NOT_FOUND() {
		// given
		given(invites.findRoomIdByIdAndUserId(INVITE_ID, ME)).willReturn(Optional.of(ROOM_ID), Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.acceptInvite(ME, INVITE_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_INVITE_NOT_FOUND);
		verify(rooms).findByIdForUpdate(ROOM_ID);
		verify(profiles).findByIdForUpdate(ME);
		verify(members, never()).insert(anyLong(), anyLong());
	}

	@Test
	@DisplayName("이미 들어간 방이 20개면 수락할 때 ROOM_LIMIT이고 초대를 남긴다")
	void 수락할_때_방이_20개면_ROOM_LIMIT() {
		// given
		given(invites.findRoomIdByIdAndUserId(INVITE_ID, ME)).willReturn(Optional.of(ROOM_ID));
		given(members.countByUserId(ME)).willReturn((long) RoomService.ROOM_LIMIT);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.acceptInvite(ME, INVITE_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_LIMIT);
		verify(invites, never()).deleteInvite(anyLong());
		verify(members, never()).insert(anyLong(), anyLong());
	}

	@Test
	@DisplayName("수락하면 초대를 지우고 멤버로 넣는다")
	void 수락하면_멤버가_된다() {
		// given
		given(invites.findRoomIdByIdAndUserId(INVITE_ID, ME)).willReturn(Optional.of(ROOM_ID));
		given(rooms.findItems(eq(ME), any())).willReturn(List.of(itemView()));

		// when
		RoomItem item = service.acceptInvite(ME, INVITE_ID);

		// then
		assertThat(item.id()).isEqualTo(ROOM_ID);
		verify(invites).deleteInvite(INVITE_ID);
		verify(members).insert(ROOM_ID, ME);
	}

	@Test
	@DisplayName("거절할 초대가 없으면 ROOM_INVITE_NOT_FOUND이고, 거절해도 푸시하지 않는다")
	void 거절() {
		// given
		given(invites.deleteByIdAndUserId(INVITE_ID, ME)).willReturn(0, 1);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.declineInvite(ME, INVITE_ID));
		service.declineInvite(ME, INVITE_ID);

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_INVITE_NOT_FOUND);
		verifyNoInteractions(push);
	}

	// ---- postComic / removePost ----

	@Test
	@DisplayName("만화 ID가 없으면 comicId 칸 INVALID_REQUEST다")
	void 만화_ID가_없으면_INVALID() {
		// given
		givenMember(ME);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.postComic(ME, ROOM_ID, null));

		// then
		assertThat(e.getField()).isEqualTo("comicId");
		verifyNoInteractions(comics);
	}

	@Test
	@DisplayName("완성되지 않은 내 만화는 COMIC_NOT_FOUND다")
	void 완성되지_않은_만화는_COMIC_NOT_FOUND() {
		// given
		givenMember(ME);
		given(comics.findByIdAndUserId(COMIC_ID, ME)).willReturn(Optional.of(comic(ComicStatus.DRAWING)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.postComic(ME, ROOM_ID, COMIC_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.COMIC_NOT_FOUND);
		verify(posts, never()).insertNew(anyLong(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("같은 만화를 다시 올리면 ROOM_POST_EXISTS이고 대화 줄을 남기지 않는다")
	void 같은_만화를_다시_올리면_ROOM_POST_EXISTS() {
		// given
		givenMember(ME);
		given(comics.findByIdAndUserId(COMIC_ID, ME)).willReturn(Optional.of(comic(ComicStatus.DONE)));
		given(posts.insertNew(ROOM_ID, COMIC_ID, ME)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.postComic(ME, ROOM_ID, COMIC_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_POST_EXISTS);
		verify(messages, never()).insert(anyLong(), anyLong(), anyString(), any(), any());
	}

	@Test
	@DisplayName("완성된 내 만화를 올리면 POSTED 줄을 남기고 올린 만화를 돌려준다")
	void 만화를_올리면_POSTED_줄을_남긴다() {
		// given
		givenMember(ME);
		given(comics.findByIdAndUserId(COMIC_ID, ME)).willReturn(Optional.of(comic(ComicStatus.DONE)));
		given(posts.insertNew(ROOM_ID, COMIC_ID, ME)).willReturn(Optional.of(POST_ID));
		given(posts.findViews(ME, ROOM_ID, POST_ID)).willReturn(List.of(postView(ME)));

		// when
		var post = service.postComic(ME, ROOM_ID, COMIC_ID);

		// then
		assertThat(post.id()).isEqualTo(POST_ID);
		assertThat(post.comicId()).isEqualTo(COMIC_ID);
		verify(messages).insert(ROOM_ID, ME, "POSTED", POST_ID, null);
		verify(rooms).touchActivity(ROOM_ID);
	}

	@Test
	@DisplayName("이 방에 없는 만화를 내리면 ROOM_POST_NOT_FOUND다")
	void 없는_만화를_내리면_ROOM_POST_NOT_FOUND() {
		// given
		givenMember(ME);
		given(posts.findByIdAndRoomId(POST_ID, ROOM_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.removePost(ME, ROOM_ID, POST_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_POST_NOT_FOUND);
	}

	@Test
	@DisplayName("남이 올린 만화를 내리면 ROOM_POST_NOT_MINE이고 지우지 않는다")
	void 남의_만화를_내리면_ROOM_POST_NOT_MINE() {
		// given
		givenMember(ME);
		given(posts.findByIdAndRoomId(POST_ID, ROOM_ID)).willReturn(Optional.of(post(OTHER)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.removePost(ME, ROOM_ID, POST_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ROOM_POST_NOT_MINE);
		verify(posts, never()).deletePost(anyLong());
	}

	// ---- like / unlike ----

	@Test
	@DisplayName("좋아요를 새로 누르면 LIKED 줄을 남기고, 이미 눌렀으면 남기지 않는다")
	void 좋아요는_처음_누를_때만_줄을_남긴다() {
		// given
		givenMember(ME);
		given(posts.findByIdAndRoomId(POST_ID, ROOM_ID)).willReturn(Optional.of(post(OTHER)));
		given(likes.insertIfAbsent(POST_ID, ME)).willReturn(1, 0);
		given(posts.findViews(ME, ROOM_ID, POST_ID)).willReturn(List.of(postView(OTHER)));

		// when
		service.like(ME, ROOM_ID, POST_ID);
		service.like(ME, ROOM_ID, POST_ID);

		// then
		verify(messages).insert(ROOM_ID, ME, "LIKED", POST_ID, null);
	}

	@Test
	@DisplayName("좋아요를 취소하면 그 좋아요 줄도 빼고, 안 눌렀으면 빼지 않는다")
	void 좋아요_취소는_지운_것이_있을_때만_줄을_뺀다() {
		// given
		givenMember(ME);
		given(posts.findByIdAndRoomId(POST_ID, ROOM_ID)).willReturn(Optional.of(post(OTHER)));
		given(likes.deleteLike(POST_ID, ME)).willReturn(1, 0);
		given(posts.findViews(ME, ROOM_ID, POST_ID)).willReturn(List.of(postView(OTHER)));

		// when
		service.unlike(ME, ROOM_ID, POST_ID);
		service.unlike(ME, ROOM_ID, POST_ID);

		// then
		verify(messages).deleteLiked(POST_ID, ME);
	}

	// ---- messages / comment ----

	@Test
	@DisplayName("51줄이 오면 50줄을 오래된 순으로 주고 가장 오래된 줄 ID를 다음 커서로 주며 읽음 처리한다")
	void 대화는_50줄씩_오래된_순() {
		// given
		givenMember(ME);
		List<RoomMessageView> rows = new ArrayList<>();
		for (long id = 100; id > 100 - (RoomService.MESSAGE_PAGE + 1); id--) {
			rows.add(messageView(id, OTHER, null));
		}
		given(messages.findPage(eq(ROOM_ID), isNull(), any(Limit.class))).willReturn(rows);

		// when
		MessageList list = service.messages(ME, ROOM_ID, null);

		// then
		assertThat(list.messages()).hasSize(RoomService.MESSAGE_PAGE);
		assertThat(list.messages().getFirst().id()).isEqualTo(51L);
		assertThat(list.messages().getLast().id()).isEqualTo(100L);
		assertThat(list.nextCursor()).isEqualTo("51");
		verify(messages).findPage(ROOM_ID, null, Limit.of(RoomService.MESSAGE_PAGE + 1));
		verify(members).markRead(ROOM_ID, ME);
	}

	@Test
	@DisplayName("댓글이 공백뿐이면 text 칸 INVALID_REQUEST다")
	void 빈_댓글은_INVALID() {
		// given
		givenMember(ME);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.comment(ME, ROOM_ID, "  ", null));

		// then
		assertThat(e.getField()).isEqualTo("text");
		verify(messages, never()).insert(anyLong(), anyLong(), anyString(), any(), any());
	}

	@Test
	@DisplayName("댓글이 201자면 text 칸 INVALID_REQUEST다")
	void 긴_댓글은_INVALID() {
		// given
		givenMember(ME);

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.comment(ME, ROOM_ID, "가".repeat(RoomService.COMMENT_MAX + 1), null));

		// then
		assertThat(e.getField()).isEqualTo("text");
	}

	@Test
	@DisplayName("남의 만화에 댓글을 달면 방에 남아 있는 올린 사람에게 내 닉네임으로 푸시한다")
	void 남의_만화_댓글은_올린_사람에게_푸시() {
		// given
		givenMember(ME);
		given(posts.findByIdAndRoomId(POST_ID, ROOM_ID)).willReturn(Optional.of(post(OTHER)));
		given(messages.insert(ROOM_ID, ME, "COMMENT", POST_ID, "재밌다")).willReturn(77L);
		given(members.existsByRoomIdAndUserId(ROOM_ID, OTHER)).willReturn(true);
		given(profiles.findById(ME)).willReturn(Optional.of(profile(ME, "나")));
		given(messages.findView(77L)).willReturn(Optional.of(messageView(77L, ME, POST_ID)));

		// when
		RoomMessage message = service.comment(ME, ROOM_ID, " 재밌다 ", POST_ID);

		// then
		assertThat(message.id()).isEqualTo(77L);
		verify(push).notify(OTHER, PushService.roomComment(ROOM_ID, POST_ID, "나"));
	}

	@Test
	@DisplayName("올린 사람이 방에서 나갔으면 댓글 푸시를 보내지 않는다")
	void 올린_사람이_나갔으면_푸시하지_않는다() {
		// given
		givenMember(ME);
		given(posts.findByIdAndRoomId(POST_ID, ROOM_ID)).willReturn(Optional.of(post(OTHER)));
		given(messages.insert(ROOM_ID, ME, "COMMENT", POST_ID, "재밌다")).willReturn(77L);
		given(members.existsByRoomIdAndUserId(ROOM_ID, OTHER)).willReturn(false);
		given(messages.findView(77L)).willReturn(Optional.of(messageView(77L, ME, POST_ID)));

		// when
		service.comment(ME, ROOM_ID, "재밌다", POST_ID);

		// then
		verifyNoInteractions(push);
	}

	@Test
	@DisplayName("내 만화에 단 댓글은 푸시하지 않는다")
	void 내_만화_댓글은_푸시하지_않는다() {
		// given
		givenMember(ME);
		given(posts.findByIdAndRoomId(POST_ID, ROOM_ID)).willReturn(Optional.of(post(ME)));
		given(messages.insert(ROOM_ID, ME, "COMMENT", POST_ID, "재밌다")).willReturn(77L);
		given(messages.findView(77L)).willReturn(Optional.of(messageView(77L, ME, POST_ID)));

		// when
		service.comment(ME, ROOM_ID, "재밌다", POST_ID);

		// then
		verifyNoInteractions(push);
	}

	// ---- hasNews ----

	@Test
	@DisplayName("안 읽은 글이 없어도 받은 초대가 있으면 새 소식이 있다")
	void 받은_초대가_있으면_새_소식() {
		// given
		given(members.existsUnread(ME)).willReturn(false);
		given(invites.existsByUserId(ME)).willReturn(true);

		// when & then
		assertThat(service.hasNews(ME)).isTrue();
	}

	// ---- 도움 ----

	// 방을 만들 수 있는 상태(닉네임 있음, OTHER와 친구, 저장하면 ROOM_ID)를 둔다.
	private void givenCreatable() {
		given(profiles.findByIdForUpdate(ME)).willReturn(Optional.of(profile(ME, "나")));
		given(friendships.areFriends(ME, OTHER)).willReturn(true);
		given(rooms.save(any())).willAnswer(inv -> {
			Room r = inv.getArgument(0);
			ReflectionTestUtils.setField(r, "id", ROOM_ID);
			return r;
		});
		given(rooms.findItems(eq(ME), any())).willReturn(List.of(itemView()));
	}

	private void givenMember(long userId) {
		given(members.existsByRoomIdAndUserId(ROOM_ID, userId)).willReturn(true);
	}

	private static Room room(long owner) {
		Room r = new Room("방", owner);
		ReflectionTestUtils.setField(r, "id", ROOM_ID);
		return r;
	}

	private static UserProfile profile(long userId, String nickname) {
		UserProfile p = new UserProfile();
		ReflectionTestUtils.setField(p, "userId", userId);
		ReflectionTestUtils.setField(p, "nickname", nickname);
		return p;
	}

	private static Comic comic(ComicStatus status) {
		Comic c = new Comic(5L, ME, "요약");
		ReflectionTestUtils.setField(c, "id", COMIC_ID);
		ReflectionTestUtils.setField(c, "status", status);
		return c;
	}

	private static RoomPost post(long author) {
		RoomPost p = new RoomPost();
		ReflectionTestUtils.setField(p, "id", POST_ID);
		ReflectionTestUtils.setField(p, "userId", author);
		return p;
	}

	private static RoomItemView itemView() {
		return new RoomItemView(ROOM_ID, "방", ME, AT, null, null, null, null, 0);
	}

	private static RoomPostView postView(long author) {
		return new RoomPostView(POST_ID, COMIC_ID, author, AT, "제목", null, 0, false, 0);
	}

	private static RoomMessageView messageView(long id, long userId, Long postId) {
		return new RoomMessageView(id, userId, "COMMENT", postId, "글", AT, null, null, null);
	}

}
