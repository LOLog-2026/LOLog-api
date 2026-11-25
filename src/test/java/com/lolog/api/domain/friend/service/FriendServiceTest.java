package com.lolog.api.domain.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.character.service.CharacterDetacher;
import com.lolog.api.domain.character.service.CharacterViews;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendItem;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendList;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendRequestList;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendRequestResponse;
import com.lolog.api.domain.friend.entity.Friendship;
import com.lolog.api.domain.friend.entity.FriendshipStatus;
import com.lolog.api.domain.friend.entity.UserBlock;
import com.lolog.api.domain.friend.repository.FriendshipRepository;
import com.lolog.api.domain.friend.repository.UserBlockRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("FriendService")
class FriendServiceTest {

	private static final long ME = 5L;
	private static final long OTHER = 3L;
	private static final long REQUEST_ID = 100L;
	private static final String OTHER_CODE = "ABCD2345";

	@Mock
	private FriendshipRepository friendships;

	@Mock
	private UserBlockRepository blocks;

	@Mock
	private UserProfileRepository profiles;

	@Mock
	private FriendCodeAttemptLimiter limiter;

	@Mock
	private PushService push;

	@Mock
	private CharacterDetacher detacher;

	@Mock
	private CharacterViews characters;

	@InjectMocks
	private FriendService service;

	// ---- sendRequest ----

	@Test
	@DisplayName("친구 코드와 userId가 둘 다 없으면 friendCode 칸 INVALID_REQUEST다")
	void 둘_다_없으면_friendCode_INVALID() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, null, null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("friendCode");
		verifyNoInteractions(friendships, profiles, limiter, push);
	}

	@Test
	@DisplayName("친구 코드와 userId가 둘 다 있으면 userId 칸 INVALID_REQUEST다")
	void 둘_다_있으면_userId_INVALID() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, OTHER_CODE, OTHER));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("userId");
		verifyNoInteractions(friendships, profiles, limiter, push);
	}

	@Test
	@DisplayName("userId로 나에게 보내면 방 조회 전에 CANNOT_FRIEND_SELF다")
	void userId가_나면_CANNOT_FRIEND_SELF() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, null, ME));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CANNOT_FRIEND_SELF);
		verifyNoInteractions(friendships, profiles);
	}

	@Test
	@DisplayName("userId로 보낼 때 지금 같은 방이 아니면 FRIEND_CODE_NOT_FOUND이고 시도 횟수를 세지 않는다")
	void 같은_방이_아니면_FRIEND_CODE_NOT_FOUND() {
		// given
		given(profiles.findById(ME)).willReturn(Optional.of(profile(ME, "나", "MYCODE23")));
		given(friendships.findRoommateWithNickname(OTHER, ME)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, null, OTHER));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_CODE_NOT_FOUND);
		verifyNoInteractions(limiter);
		verify(friendships, never()).save(any());
	}

	@Test
	@DisplayName("친구 코드 길이가 8자가 아니면 friendCode 칸 INVALID_REQUEST이고 시도 횟수를 세지 않는다")
	void 코드_길이가_다르면_INVALID() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, "ABC", null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("friendCode");
		verifyNoInteractions(limiter, profiles);
	}

	@Test
	@DisplayName("내 닉네임이 없으면 PROFILE_REQUIRED이고 시도 횟수를 세지 않는다")
	void 닉네임이_없으면_PROFILE_REQUIRED() {
		// given
		given(profiles.findById(ME)).willReturn(Optional.of(profile(ME, null, "MYCODE23")));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, OTHER_CODE, null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_REQUIRED);
		verifyNoInteractions(limiter);
	}

	@Test
	@DisplayName("친구 코드는 앞뒤 공백을 빼고 대문자로 바꿔 찾고, 없으면 FRIEND_CODE_NOT_FOUND다")
	void 코드는_다듬어_찾고_없으면_FRIEND_CODE_NOT_FOUND() {
		// given
		given(profiles.findById(ME)).willReturn(Optional.of(profile(ME, "나", "MYCODE23")));
		given(profiles.findByFriendCodeAndNicknameIsNotNull(OTHER_CODE)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, "  abcd2345 ", null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_CODE_NOT_FOUND);
		verify(limiter).check(ME);
		verify(profiles).findByFriendCodeAndNicknameIsNotNull(OTHER_CODE);
	}

	@Test
	@DisplayName("내 친구 코드를 넣으면 CANNOT_FRIEND_SELF다")
	void 내_코드면_CANNOT_FRIEND_SELF() {
		// given
		given(profiles.findById(ME)).willReturn(Optional.of(profile(ME, "나", OTHER_CODE)));
		given(profiles.findByFriendCodeAndNicknameIsNotNull(OTHER_CODE))
			.willReturn(Optional.of(profile(ME, "나", OTHER_CODE)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, OTHER_CODE, null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CANNOT_FRIEND_SELF);
		verify(profiles, never()).findAllByIdForUpdate(any());
	}

	@Test
	@DisplayName("상대가 나를 차단했으면 없는 코드와 같은 FRIEND_CODE_NOT_FOUND다")
	void 상대가_나를_차단했으면_FRIEND_CODE_NOT_FOUND() {
		// given
		givenCodeTarget();
		given(blocks.existsById(new UserBlock.Key(OTHER, ME))).willReturn(true);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, OTHER_CODE, null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_CODE_NOT_FOUND);
		verify(friendships, never()).save(any());
		verifyNoInteractions(push);
	}

	@Test
	@DisplayName("내가 상대를 차단했으면 USER_BLOCKED다")
	void 내가_차단했으면_USER_BLOCKED() {
		// given
		givenCodeTarget();
		given(blocks.existsById(new UserBlock.Key(OTHER, ME))).willReturn(false);
		given(blocks.existsById(new UserBlock.Key(ME, OTHER))).willReturn(true);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, OTHER_CODE, null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_BLOCKED);
		verify(friendships, never()).save(any());
	}

	@Test
	@DisplayName("이미 친구면 ALREADY_FRIENDS다")
	void 이미_친구면_ALREADY_FRIENDS() {
		// given
		givenCodeTarget();
		Friendship accepted = friendship(OTHER, ME);
		accepted.accept();
		given(friendships.findBetween(ME, OTHER)).willReturn(Optional.of(accepted));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, OTHER_CODE, null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.ALREADY_FRIENDS);
	}

	@Test
	@DisplayName("내가 이미 보낸 요청이 있으면 FRIEND_REQUEST_EXISTS다")
	void 이미_보냈으면_FRIEND_REQUEST_EXISTS() {
		// given
		givenCodeTarget();
		given(friendships.findBetween(ME, OTHER)).willReturn(Optional.of(friendship(ME, OTHER)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, OTHER_CODE, null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_REQUEST_EXISTS);
		verify(friendships, never()).save(any());
	}

	@Test
	@DisplayName("나나 상대의 친구가 100명이면 FRIEND_LIMIT이고 요청을 만들지 않는다")
	void 친구가_100명이면_FRIEND_LIMIT() {
		// given
		givenCodeTarget();
		given(friendships.countFriendsOf(ME)).willReturn(10L);
		given(friendships.countFriendsOf(OTHER)).willReturn((long) FriendService.FRIEND_LIMIT);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sendRequest(ME, OTHER_CODE, null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_LIMIT);
		verify(friendships, never()).save(any());
		verifyNoInteractions(push);
	}

	@Test
	@DisplayName("상대가 먼저 보낸 요청이 있으면 새 줄 없이 그 줄을 ACCEPTED로 바꾸고 푸시하지 않는다")
	void 상대_요청이_있으면_바로_수락한다() {
		// given
		givenCodeTarget();
		Friendship received = friendship(OTHER, ME);
		given(friendships.findBetween(ME, OTHER)).willReturn(Optional.of(received));
		given(profiles.findById(OTHER)).willReturn(Optional.of(profile(OTHER, "친구", OTHER_CODE)));

		// when
		FriendRequestResponse response = service.sendRequest(ME, OTHER_CODE, null);

		// then
		assertThat(received.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);
		assertThat(response.id()).isEqualTo(REQUEST_ID);
		assertThat(response.status()).isEqualTo("ACCEPTED");
		assertThat(response.direction()).isEqualTo("SENT");
		assertThat(response.user().userId()).isEqualTo(OTHER);
		verify(friendships, never()).save(any());
		verifyNoInteractions(push);
	}

	@Test
	@DisplayName("새 요청이면 두 사람 프로필을 ID 순서로 잠그고 PENDING 줄을 저장한 뒤 상대에게 내 닉네임으로 푸시한다")
	void 새_요청이면_저장하고_푸시한다() {
		// given
		givenCodeTarget();
		given(profiles.findById(OTHER)).willReturn(Optional.of(profile(OTHER, "친구", OTHER_CODE)));
		given(friendships.save(any())).willAnswer(inv -> {
			Friendship f = inv.getArgument(0);
			ReflectionTestUtils.setField(f, "id", REQUEST_ID);
			return f;
		});

		// when
		FriendRequestResponse response = service.sendRequest(ME, OTHER_CODE, null);

		// then
		assertThat(response.status()).isEqualTo("PENDING");
		assertThat(response.id()).isEqualTo(REQUEST_ID);
		assertThat(response.user().nickname()).isEqualTo("친구");
		verify(profiles).findAllByIdForUpdate(List.of(OTHER, ME));
		ArgumentCaptor<Friendship> saved = ArgumentCaptor.forClass(Friendship.class);
		verify(friendships).save(saved.capture());
		assertThat(saved.getValue().getRequesterId()).isEqualTo(ME);
		assertThat(saved.getValue().getAddresseeId()).isEqualTo(OTHER);
		assertThat(saved.getValue().getStatus()).isEqualTo(FriendshipStatus.PENDING);
		verify(push).notify(OTHER, PushService.friendRequest(REQUEST_ID, "나"));
	}

	@Test
	@DisplayName("같은 방 멤버의 userId로 보내면 친구 코드 시도 횟수를 세지 않고 요청을 만든다")
	void userId로_보내면_시도_횟수를_세지_않는다() {
		// given
		given(profiles.findById(ME)).willReturn(Optional.of(profile(ME, "나", "MYCODE23")));
		given(friendships.findRoommateWithNickname(OTHER, ME)).willReturn(Optional.of(OTHER));
		given(profiles.findById(OTHER)).willReturn(Optional.of(profile(OTHER, "친구", OTHER_CODE)));
		given(friendships.save(any())).willAnswer(inv -> {
			Friendship f = inv.getArgument(0);
			ReflectionTestUtils.setField(f, "id", REQUEST_ID);
			return f;
		});

		// when
		FriendRequestResponse response = service.sendRequest(ME, null, OTHER);

		// then
		assertThat(response.status()).isEqualTo("PENDING");
		verifyNoInteractions(limiter);
		verify(push).notify(OTHER, PushService.friendRequest(REQUEST_ID, "나"));
	}

	// ---- requests ----

	@Test
	@DisplayName("direction이 received·sent가 아니면 direction 칸 INVALID_REQUEST다")
	void direction이_틀리면_INVALID() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.requests(ME, "all"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("direction");
		verifyNoInteractions(friendships);
	}

	@Test
	@DisplayName("받은 요청은 받은 쪽으로 읽고, 프로필이 없는 상대는 뺀다")
	void 받은_요청은_프로필_없는_상대를_뺀다() {
		// given
		Friendship fromOther = friendship(OTHER, ME);
		Friendship fromGhost = friendship(9L, ME);
		given(friendships.findAllByAddresseeIdAndStatusOrderByCreatedAtDescIdDesc(ME, FriendshipStatus.PENDING))
			.willReturn(List.of(fromOther, fromGhost));
		given(profiles.findAllById(List.of(OTHER, 9L))).willReturn(List.of(profile(OTHER, "친구", OTHER_CODE)));

		// when
		FriendRequestList list = service.requests(ME, "received");

		// then
		assertThat(list.requests()).hasSize(1);
		assertThat(list.requests().getFirst().direction()).isEqualTo("RECEIVED");
		assertThat(list.requests().getFirst().user().userId()).isEqualTo(OTHER);
		verify(friendships, never()).findAllByRequesterIdAndStatusOrderByCreatedAtDescIdDesc(anyLong(), any());
	}

	@Test
	@DisplayName("보낸 요청은 보낸 쪽으로 읽고 direction이 SENT다")
	void 보낸_요청은_SENT() {
		// given
		given(friendships.findAllByRequesterIdAndStatusOrderByCreatedAtDescIdDesc(ME, FriendshipStatus.PENDING))
			.willReturn(List.of(friendship(ME, OTHER)));
		given(profiles.findAllById(List.of(OTHER))).willReturn(List.of(profile(OTHER, "친구", OTHER_CODE)));

		// when
		FriendRequestList list = service.requests(ME, "sent");

		// then
		assertThat(list.requests()).extracting(FriendRequestResponse::direction).containsExactly("SENT");
	}

	// ---- acceptRequest ----

	@Test
	@DisplayName("내가 받은 대기 요청이 아니면 FRIEND_REQUEST_NOT_FOUND다")
	void 받은_요청이_없으면_FRIEND_REQUEST_NOT_FOUND() {
		// given
		given(friendships.findByIdAndAddresseeIdAndStatus(REQUEST_ID, ME, FriendshipStatus.PENDING))
			.willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.acceptRequest(ME, REQUEST_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_REQUEST_NOT_FOUND);
		verify(profiles, never()).findAllByIdForUpdate(any());
	}

	@Test
	@DisplayName("잠그는 사이 상대가 요청을 취소했으면 FRIEND_REQUEST_NOT_FOUND다")
	void 잠그는_사이_취소됐으면_FRIEND_REQUEST_NOT_FOUND() {
		// given
		given(friendships.findByIdAndAddresseeIdAndStatus(REQUEST_ID, ME, FriendshipStatus.PENDING))
			.willReturn(Optional.of(friendship(OTHER, ME)), Optional.empty());
		given(profiles.findById(ME)).willReturn(Optional.of(profile(ME, "나", "MYCODE23")));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.acceptRequest(ME, REQUEST_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_REQUEST_NOT_FOUND);
		verify(profiles).findAllByIdForUpdate(List.of(OTHER, ME));
		verify(friendships, never()).countFriendsOf(anyLong());
	}

	@Test
	@DisplayName("수락할 때 내 닉네임이 없으면 PROFILE_REQUIRED다")
	void 수락할_때_닉네임이_없으면_PROFILE_REQUIRED() {
		// given
		given(friendships.findByIdAndAddresseeIdAndStatus(REQUEST_ID, ME, FriendshipStatus.PENDING))
			.willReturn(Optional.of(friendship(OTHER, ME)));
		given(profiles.findById(ME)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.acceptRequest(ME, REQUEST_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PROFILE_REQUIRED);
		verify(profiles, never()).findAllByIdForUpdate(any());
	}

	@Test
	@DisplayName("내 친구가 100명이면 FRIEND_LIMIT이고 요청은 PENDING으로 남는다")
	void 수락할_때_100명이면_FRIEND_LIMIT() {
		// given
		Friendship request = friendship(OTHER, ME);
		given(friendships.findByIdAndAddresseeIdAndStatus(REQUEST_ID, ME, FriendshipStatus.PENDING))
			.willReturn(Optional.of(request));
		given(profiles.findById(ME)).willReturn(Optional.of(profile(ME, "나", "MYCODE23")));
		given(friendships.countFriendsOf(ME)).willReturn((long) FriendService.FRIEND_LIMIT);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.acceptRequest(ME, REQUEST_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_LIMIT);
		assertThat(request.getStatus()).isEqualTo(FriendshipStatus.PENDING);
	}

	@Test
	@DisplayName("수락하면 요청이 ACCEPTED가 되고 보낸 사람을 친구로 돌려준다")
	void 수락하면_ACCEPTED() {
		// given
		Friendship request = friendship(OTHER, ME);
		given(friendships.findByIdAndAddresseeIdAndStatus(REQUEST_ID, ME, FriendshipStatus.PENDING))
			.willReturn(Optional.of(request));
		given(profiles.findById(ME)).willReturn(Optional.of(profile(ME, "나", "MYCODE23")));
		given(profiles.findById(OTHER)).willReturn(Optional.of(profile(OTHER, "친구", OTHER_CODE)));

		// when
		FriendItem item = service.acceptRequest(ME, REQUEST_ID);

		// then
		assertThat(request.getStatus()).isEqualTo(FriendshipStatus.ACCEPTED);
		assertThat(item.userId()).isEqualTo(OTHER);
		assertThat(item.friendsSince()).isEqualTo(request.getUpdatedAt());
		verifyNoInteractions(push);
	}

	// ---- deleteRequest ----

	@Test
	@DisplayName("지운 줄이 없으면 FRIEND_REQUEST_NOT_FOUND다")
	void 지울_요청이_없으면_FRIEND_REQUEST_NOT_FOUND() {
		// given
		given(friendships.deletePendingOf(REQUEST_ID, ME)).willReturn(0);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.deleteRequest(ME, REQUEST_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.FRIEND_REQUEST_NOT_FOUND);
	}

	@Test
	@DisplayName("요청을 지우면 상대에게 푸시하지 않는다")
	void 요청을_지우면_푸시하지_않는다() {
		// given
		given(friendships.deletePendingOf(REQUEST_ID, ME)).willReturn(1);

		// when
		service.deleteRequest(ME, REQUEST_ID);

		// then
		verifyNoInteractions(push);
	}

	// ---- friends ----

	@Test
	@DisplayName("친구 목록은 프로필이 없는 친구를 빼고 친구가 된 시각과 캐릭터를 넣는다")
	void 친구_목록은_프로필_없는_친구를_뺀다() {
		// given
		Friendship f = friendship(ME, OTHER);
		f.accept();
		Friendship ghost = friendship(9L, ME);
		ghost.accept();
		given(friendships.findFriendsOf(ME)).willReturn(List.of(f, ghost));
		UserProfile p = profile(OTHER, "친구", OTHER_CODE);
		ReflectionTestUtils.setField(p, "characterThumbKey", "thumb/3.png");
		given(profiles.findAllById(List.of(OTHER, 9L))).willReturn(List.of(p));

		// when
		FriendList list = service.friends(ME);

		// then
		assertThat(list.friends()).extracting(FriendItem::userId).containsExactly(OTHER);
		assertThat(list.friends().getFirst().friendsSince()).isEqualTo(f.getUpdatedAt());
		verify(characters).friendCharacter("thumb/3.png");
	}

	@Test
	@DisplayName("친구가 없으면 프로필을 읽지 않는다")
	void 친구가_없으면_프로필을_읽지_않는다() {
		// given
		given(friendships.findFriendsOf(ME)).willReturn(List.of());

		// when
		FriendList list = service.friends(ME);

		// then
		assertThat(list.friends()).isEmpty();
		verify(profiles, never()).findAllById(any());
	}

	// ---- unfriend ----

	@Test
	@DisplayName("친구를 끊으면 서로의 캐릭터를 화자에서 빼고 친구 줄을 지운다")
	void 끊으면_캐릭터를_빼고_줄을_지운다() {
		// when
		service.unfriend(ME, OTHER);

		// then
		verify(detacher).detachBetween(ME, OTHER);
		verify(friendships).deleteAcceptedBetween(ME, OTHER);
	}

	// ---- block ----

	@Test
	@DisplayName("나를 차단하면 CANNOT_FRIEND_SELF다")
	void 나를_차단하면_CANNOT_FRIEND_SELF() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.block(ME, ME, true));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CANNOT_FRIEND_SELF);
		verifyNoInteractions(blocks, friendships);
	}

	@Test
	@DisplayName("프로필이 없는 회원을 차단하면 USER_NOT_FOUND다")
	void 없는_회원을_차단하면_USER_NOT_FOUND() {
		// given
		given(profiles.existsById(OTHER)).willReturn(false);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.block(ME, OTHER, true));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
		verify(blocks, never()).save(any());
		verify(friendships, never()).deleteBetween(anyLong(), anyLong());
	}

	@Test
	@DisplayName("차단하면 두 사람의 친구 줄과 화자 캐릭터를 지우고 숨기기 설정으로 차단 줄을 저장한다")
	void 차단하면_관계를_지우고_저장한다() {
		// given
		given(profiles.existsById(OTHER)).willReturn(true);

		// when
		service.block(ME, OTHER, true);

		// then
		verify(profiles).findAllByIdForUpdate(List.of(OTHER, ME));
		verify(friendships).deleteBetween(ME, OTHER);
		verify(detacher).detachBetween(ME, OTHER);
		ArgumentCaptor<UserBlock> saved = ArgumentCaptor.forClass(UserBlock.class);
		verify(blocks).save(saved.capture());
		assertThat(saved.getValue().getBlockerId()).isEqualTo(ME);
		assertThat(saved.getValue().getBlockedId()).isEqualTo(OTHER);
		assertThat(saved.getValue().isHideInRooms()).isTrue();
	}

	// ---- updateBlock ----

	@Test
	@DisplayName("차단한 사람이 아니면 숨기기 설정을 바꿀 때 USER_NOT_FOUND다")
	void 차단하지_않은_사람이면_USER_NOT_FOUND() {
		// given
		given(blocks.findById(new UserBlock.Key(ME, OTHER))).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.updateBlock(ME, OTHER, false));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.USER_NOT_FOUND);
	}

	@Test
	@DisplayName("차단한 사람이면 숨기기 설정을 바꾼다")
	void 숨기기_설정을_바꾼다() {
		// given
		UserBlock block = new UserBlock(ME, OTHER, true);
		given(blocks.findById(new UserBlock.Key(ME, OTHER))).willReturn(Optional.of(block));

		// when
		service.updateBlock(ME, OTHER, false);

		// then
		assertThat(block.isHideInRooms()).isFalse();
	}

	// ---- blocks / unblock / hasReceivedRequests ----

	@Test
	@DisplayName("차단 목록은 프로필이 없는 사람을 뺀다")
	void 차단_목록은_프로필_없는_사람을_뺀다() {
		// given
		given(blocks.findAllByBlockerIdOrderByCreatedAtDescBlockedIdDesc(ME))
			.willReturn(List.of(new UserBlock(ME, OTHER, false), new UserBlock(ME, 9L, true)));
		given(profiles.findAllById(List.of(OTHER, 9L))).willReturn(List.of(profile(OTHER, "친구", OTHER_CODE)));

		// when
		var list = service.blocks(ME);

		// then
		assertThat(list.blocks()).hasSize(1);
		assertThat(list.blocks().getFirst().userId()).isEqualTo(OTHER);
		assertThat(list.blocks().getFirst().friendCode()).isEqualTo(OTHER_CODE);
	}

	@Test
	@DisplayName("차단을 풀면 차단 줄만 지우고 친구로 되돌리지 않는다")
	void 차단을_풀면_줄만_지운다() {
		// when
		service.unblock(ME, OTHER);

		// then
		verify(blocks).deleteBlock(ME, OTHER);
		verifyNoInteractions(friendships);
	}

	@Test
	@DisplayName("받은 대기 요청이 있는지 PENDING 상태로 묻는다")
	void 받은_요청이_있는지_묻는다() {
		// given
		given(friendships.existsByAddresseeIdAndStatus(ME, FriendshipStatus.PENDING)).willReturn(true);

		// when & then
		assertThat(service.hasReceivedRequests(ME)).isTrue();
	}

	// ---- 도움 ----

	// 친구 코드로 OTHER를 찾게 하고 내 닉네임을 둔다.
	private void givenCodeTarget() {
		given(profiles.findById(ME)).willReturn(Optional.of(profile(ME, "나", "MYCODE23")));
		given(profiles.findByFriendCodeAndNicknameIsNotNull(OTHER_CODE))
			.willReturn(Optional.of(profile(OTHER, "친구", OTHER_CODE)));
	}

	private static UserProfile profile(long userId, String nickname, String code) {
		UserProfile p = new UserProfile();
		ReflectionTestUtils.setField(p, "userId", userId);
		ReflectionTestUtils.setField(p, "nickname", nickname);
		ReflectionTestUtils.setField(p, "friendCode", code);
		return p;
	}

	private static Friendship friendship(long requester, long addressee) {
		Friendship f = new Friendship(requester, addressee);
		ReflectionTestUtils.setField(f, "id", REQUEST_ID);
		return f;
	}

}
