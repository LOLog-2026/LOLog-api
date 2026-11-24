package com.lolog.api.domain.friend.service;

import com.lolog.api.domain.character.service.CharacterDetacher;
import com.lolog.api.domain.character.service.CharacterViews;
import com.lolog.api.domain.friend.dto.FriendDtos.BlockItem;
import com.lolog.api.domain.friend.dto.FriendDtos.BlockList;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendItem;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendList;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendRequestList;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendRequestResponse;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendUser;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 친구 요청·수락, 친구 목록·끊기, 차단(friends.md, DB는 db.md V5).
// 두 사람 사이의 친구 요청과 친구는 friendships 한 줄이다(uk_friendships_pair). 거절·취소·끊기·차단은 줄을 지운다.
// 두 사람의 관계를 바꿀 때는 두 사람의 프로필 줄을 회원 ID 순서로 잠근다. 서로 동시에 요청하거나
// 친구 100명 제한을 동시에 넘기는 경우를 막는다.
@Service
public class FriendService {

	public static final int FRIEND_LIMIT = 100;

	private static final String PENDING = "PENDING";
	private static final String ACCEPTED = "ACCEPTED";

	private final FriendshipRepository friendships;
	private final UserBlockRepository blocks;
	private final UserProfileRepository profiles;
	private final FriendCodeAttemptLimiter limiter;
	private final PushService push;
	private final CharacterDetacher detacher;
	private final CharacterViews characters;

	// 친구 기능에 필요한 저장소와 도구를 받는다.
	public FriendService(FriendshipRepository friendships, UserBlockRepository blocks, UserProfileRepository profiles,
			FriendCodeAttemptLimiter limiter, PushService push, CharacterDetacher detacher, CharacterViews characters) {
		this.friendships = friendships;
		this.blocks = blocks;
		this.profiles = profiles;
		this.limiter = limiter;
		this.push = push;
		this.detacher = detacher;
		this.characters = characters;
	}

	// 친구 코드나 같은 방 멤버의 userId(초안)로 요청한다. 상대가 이미 나에게 요청해 뒀으면 새 줄 없이 그 줄을 ACCEPTED로 바꾼다(푸시 없음).
	@Transactional
	public FriendRequestResponse sendRequest(long me, String friendCode, Long userId) {
		if ((friendCode == null) == (userId == null)) {
			throw ApiException.invalid(friendCode == null ? "friendCode" : "userId");
		}
		long target;
		if (userId != null) {
			// 나에게 보내면 친구 코드와 같게 400이다(friends.md CANNOT_FRIEND_SELF). 방 조회보다 먼저 본다.
			if (userId == me) {
				throw new ApiException(ErrorCode.CANNOT_FRIEND_SELF);
			}
			requireNickname(me);
			// 지금 같은 방에 있지 않거나 닉네임이 없으면 친구 코드를 모를 때와 같은 404다.
			target = friendships.findRoommateWithNickname(userId, me).orElseThrow(FriendService::codeNotFound);
		}
		else {
			String code = friendCode.strip().toUpperCase(Locale.ROOT);
			if (code.length() != ProfileService.CODE_LENGTH) {
				throw ApiException.invalid("friendCode");
			}
			requireNickname(me);
			limiter.check(me);
			// 닉네임을 정하지 않은 사람의 코드는 없는 코드와 같다(코드가 앱에 보이지 않는 상태).
			target = profiles.findByFriendCodeAndNicknameIsNotNull(code)
				.map(UserProfile::getUserId)
				.orElseThrow(FriendService::codeNotFound);
		}
		if (target == me) {
			throw new ApiException(ErrorCode.CANNOT_FRIEND_SELF);
		}
		lockPair(me, target);
		// 상대가 나를 차단했으면 없는 코드와 똑같이 답한다. 차단됐다는 걸 알 수 없게 한다.
		if (blocked(target, me)) {
			throw codeNotFound();
		}
		if (blocked(me, target)) {
			throw new ApiException(ErrorCode.USER_BLOCKED);
		}
		Optional<Friendship> existing = friendships.findBetween(me, target);
		if (existing.isPresent() && existing.get().isAccepted()) {
			throw new ApiException(ErrorCode.ALREADY_FRIENDS);
		}
		if (existing.isPresent() && existing.get().isRequestedBy(me)) {
			throw new ApiException(ErrorCode.FRIEND_REQUEST_EXISTS);
		}
		checkLimit(me, target);
		FriendUser user = friendUser(target);
		if (existing.isPresent()) {
			Friendship received = existing.get();
			received.accept();
			return new FriendRequestResponse(received.getId(), "SENT", user, ACCEPTED, received.getCreatedAt());
		}
		Friendship created = friendships.save(new Friendship(me, target));
		String myNickname = profiles.findById(me).map(UserProfile::getNickname).orElse(null);
		push.notify(target, PushService.friendRequest(created.getId(), myNickname));
		return new FriendRequestResponse(created.getId(), "SENT", user, PENDING, created.getCreatedAt());
	}

	// direction은 received(내가 받음)·sent(내가 보냄). 최신순.
	@Transactional(readOnly = true)
	public FriendRequestList requests(long me, String direction) {
		boolean received;
		if ("received".equals(direction)) {
			received = true;
		}
		else if ("sent".equals(direction)) {
			received = false;
		}
		else {
			throw ApiException.invalid("direction");
		}
		List<Friendship> rows = received
				? friendships.findAllByAddresseeIdAndStatusOrderByCreatedAtDescIdDesc(me, FriendshipStatus.PENDING)
				: friendships.findAllByRequesterIdAndStatusOrderByCreatedAtDescIdDesc(me, FriendshipStatus.PENDING);
		Map<Long, UserProfile> people = profilesOf(rows.stream().map(f -> f.otherThan(me)).toList());
		List<FriendRequestResponse> list = new ArrayList<>();
		for (Friendship f : rows) {
			// 프로필이 없는 상대는 뺀다(전에 프로필과 이어 읽던 것과 같다).
			UserProfile p = people.get(f.otherThan(me));
			if (p != null) {
				list.add(new FriendRequestResponse(f.getId(), received ? "RECEIVED" : "SENT", friendUser(p), PENDING,
						f.getCreatedAt()));
			}
		}
		return new FriendRequestList(list);
	}

	// 내가 받은 요청만 수락할 수 있다. 보낸 사람이 수락하려 하면 없는 요청과 같은 404다.
	@Transactional
	public FriendItem acceptRequest(long me, long requestId) {
		long requester = receivedRequest(me, requestId).getRequesterId();
		requireNickname(me);
		lockPair(me, requester);
		// 잠그기 전에 상대가 취소했을 수 있어 다시 읽는다.
		Friendship request = receivedRequest(me, requestId);
		checkLimit(me, requester);
		request.accept();
		return friendItem(request, profiles.findById(requester).orElseThrow());
	}

	// 받은 요청 거절, 보낸 요청 취소. 상대에게 알리지 않는다.
	@Transactional
	public void deleteRequest(long me, long requestId) {
		if (friendships.deletePendingOf(requestId, me) == 0) {
			throw requestNotFound();
		}
	}

	// 최근에 친구가 된 사람이 위. 친구가 100명까지라 한 번에 다 준다.
	@Transactional(readOnly = true)
	public FriendList friends(long me) {
		List<Friendship> rows = friendships.findFriendsOf(me);
		Map<Long, UserProfile> people = profilesOf(rows.stream().map(f -> f.otherThan(me)).toList());
		List<FriendItem> list = new ArrayList<>();
		for (Friendship f : rows) {
			// 프로필이 없는 친구는 뺀다(전에 프로필과 이어 읽던 것과 같다).
			UserProfile p = people.get(f.otherThan(me));
			if (p != null) {
				list.add(friendItem(f, p));
			}
		}
		return new FriendList(list);
	}

	// 친구가 아니어도 204다. 끊으면 서로의 프로필 캐릭터가 상대 카드 화자에서 빠진다(characters.md 3장).
	@Transactional
	public void unfriend(long me, long userId) {
		detacher.detachBetween(me, userId);
		friendships.deleteAcceptedBetween(me, userId);
	}

	// 회원이면 누구나 차단할 수 있다(사용자 결정, 2026-11-11). 친구를 차단하면 친구 관계가 저절로 끊긴다.
	// 차단하면 두 사람 사이의 친구 관계와 요청을 지운다. 이미 차단했으면 그대로 204다(숨기기 설정은 PATCH로 바꾼다).
	// 메인 백엔드는 인증 DB를 보지 않으므로, 프로필이 없는 회원 ID는 없는 회원과 같은 404다.
	@Transactional
	public void block(long me, long userId, boolean hideInRooms) {
		if (userId == me) {
			throw new ApiException(ErrorCode.CANNOT_FRIEND_SELF);
		}
		if (!profiles.existsById(userId)) {
			throw new ApiException(ErrorCode.USER_NOT_FOUND);
		}
		lockPair(me, userId);
		if (blocked(me, userId)) {
			return;
		}
		friendships.deleteBetween(me, userId);
		detacher.detachBetween(me, userId);
		// 상대 프로필 줄을 잠근 뒤라 같은 차단이 동시에 들어오지 않는다.
		blocks.save(new UserBlock(me, userId, hideInRooms));
	}

	// 방에서 그 사람 글 숨기기 설정을 바꾼다. 차단한 사람이 아니면 404(초안).
	@Transactional
	public void updateBlock(long me, long userId, boolean hideInRooms) {
		blocks.findById(new UserBlock.Key(me, userId))
			.orElseThrow(() -> new ApiException(ErrorCode.USER_NOT_FOUND))
			.changeHideInRooms(hideInRooms);
	}

	// 차단한 사람 목록(최신순).
	@Transactional(readOnly = true)
	public BlockList blocks(long me) {
		List<UserBlock> rows = blocks.findAllByBlockerIdOrderByCreatedAtDescBlockedIdDesc(me);
		Map<Long, UserProfile> people = profilesOf(rows.stream().map(UserBlock::getBlockedId).toList());
		List<BlockItem> list = new ArrayList<>();
		for (UserBlock b : rows) {
			// 프로필이 없는 사람은 뺀다(전에 프로필과 이어 읽던 것과 같다).
			UserProfile p = people.get(b.getBlockedId());
			if (p != null) {
				list.add(new BlockItem(b.getBlockedId(), p.getNickname(), p.getFriendCode(), b.getCreatedAt(),
						b.isHideInRooms()));
			}
		}
		return new BlockList(list);
	}

	// 차단을 풀어도 친구로 돌아가지 않는다. 차단한 적 없어도 204다.
	@Transactional
	public void unblock(long me, long userId) {
		blocks.deleteBlock(me, userId);
	}

	// 받은 친구 요청이 있는지 본다(알림 화면의 친구 새 소식 점).
	@Transactional(readOnly = true)
	public boolean hasReceivedRequests(long me) {
		return friendships.existsByAddresseeIdAndStatus(me, FriendshipStatus.PENDING);
	}

	// 닉네임이 없으면(프로필이 없는 경우 포함) 요청을 보내거나 수락할 수 없다.
	private void requireNickname(long userId) {
		if (profiles.findById(userId).map(UserProfile::getNickname).isEmpty()) {
			throw new ApiException(ErrorCode.PROFILE_REQUIRED);
		}
	}

	// 두 사람의 프로필 줄을 회원 ID 순서로 잠근다(교착 막기). 프로필이 없는 쪽은 잠그지 않는다.
	private void lockPair(long a, long b) {
		profiles.findAllByIdForUpdate(List.of(Math.min(a, b), Math.max(a, b)));
	}

	// blocker가 blockedUser를 차단했는지 본다.
	private boolean blocked(long blocker, long blockedUser) {
		return blocks.existsById(new UserBlock.Key(blocker, blockedUser));
	}

	// 내가 받은 대기 중인 요청을 찾는다. 없으면 404다.
	private Friendship receivedRequest(long me, long requestId) {
		return friendships.findByIdAndAddresseeIdAndStatus(requestId, me, FriendshipStatus.PENDING)
			.orElseThrow(FriendService::requestNotFound);
	}

	// 나나 상대의 친구가 이미 100명이면 409.
	private void checkLimit(long a, long b) {
		for (long userId : new long[] { a, b }) {
			if (friendships.countFriendsOf(userId) >= FRIEND_LIMIT) {
				throw new ApiException(ErrorCode.FRIEND_LIMIT);
			}
		}
	}

	// 회원 한 명의 닉네임과 친구 코드를 읽는다.
	private FriendUser friendUser(long userId) {
		return friendUser(profiles.findById(userId).orElseThrow());
	}

	// 프로필을 FriendUser로 바꾼다.
	private static FriendUser friendUser(UserProfile p) {
		return new FriendUser(p.getUserId(), p.getNickname(), p.getFriendCode());
	}

	// 친구 줄과 친구의 프로필로 친구 목록 한 줄을 만든다. 친구가 된 시각은 ACCEPTED로 바꾼 updated_at이다.
	private FriendItem friendItem(Friendship f, UserProfile p) {
		return new FriendItem(p.getUserId(), p.getNickname(), p.getFriendCode(), f.getUpdatedAt(),
				characters.friendCharacter(p.getCharacterThumbKey()));
	}

	// 회원 ID로 프로필을 한 번에 읽는다.
	private Map<Long, UserProfile> profilesOf(Collection<Long> userIds) {
		Map<Long, UserProfile> result = new HashMap<>();
		if (!userIds.isEmpty()) {
			profiles.findAllById(userIds).forEach(p -> result.put(p.getUserId(), p));
		}
		return result;
	}

	// 친구 코드를 찾지 못했다는 오류를 만든다.
	private static ApiException codeNotFound() {
		return new ApiException(ErrorCode.FRIEND_CODE_NOT_FOUND);
	}

	// 친구 요청을 찾지 못했다는 오류를 만든다.
	private static ApiException requestNotFound() {
		return new ApiException(ErrorCode.FRIEND_REQUEST_NOT_FOUND);
	}

}
