package com.lolog.api.domain.room.service;

import com.lolog.api.domain.comic.entity.ComicStatus;
import com.lolog.api.domain.comic.repository.ComicRepository;
import com.lolog.api.domain.friend.entity.Friendship;
import com.lolog.api.domain.friend.entity.FriendshipStatus;
import com.lolog.api.domain.friend.repository.FriendshipRepository;
import com.lolog.api.domain.friend.repository.UserBlockRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.room.dto.RoomDtos.InviteList;
import com.lolog.api.domain.room.dto.RoomDtos.MessageList;
import com.lolog.api.domain.room.dto.RoomDtos.PostList;
import com.lolog.api.domain.room.dto.RoomDtos.RoomActivity;
import com.lolog.api.domain.room.dto.RoomDtos.RoomInvite;
import com.lolog.api.domain.room.dto.RoomDtos.RoomItem;
import com.lolog.api.domain.room.dto.RoomDtos.RoomList;
import com.lolog.api.domain.room.dto.RoomDtos.RoomMember;
import com.lolog.api.domain.room.dto.RoomDtos.RoomMessage;
import com.lolog.api.domain.room.dto.RoomDtos.RoomPost;
import com.lolog.api.domain.room.dto.RoomDtos.RoomPostSummary;
import com.lolog.api.domain.room.dto.RoomDtos.RoomRef;
import com.lolog.api.domain.room.entity.Room;
import com.lolog.api.domain.room.repository.ReceivedInviteView;
import com.lolog.api.domain.room.repository.RoomInviteRepository;
import com.lolog.api.domain.room.repository.RoomItemView;
import com.lolog.api.domain.room.repository.RoomMemberRepository;
import com.lolog.api.domain.room.repository.RoomMessageRepository;
import com.lolog.api.domain.room.repository.RoomMessageView;
import com.lolog.api.domain.room.repository.RoomPersonView;
import com.lolog.api.domain.room.repository.RoomPostLikeRepository;
import com.lolog.api.domain.room.repository.RoomPostRepository;
import com.lolog.api.domain.room.repository.RoomPostView;
import com.lolog.api.domain.room.repository.RoomRepository;
import com.lolog.api.domain.room.repository.SentInviteView;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.domain.user.repository.UserSuspensionRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.web.PageRequestParams;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Limit;
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

	private final RoomRepository rooms;
	private final RoomMemberRepository members;
	private final RoomInviteRepository invites;
	private final RoomPostRepository posts;
	private final RoomPostLikeRepository likes;
	private final RoomMessageRepository messages;
	private final UserProfileRepository profiles;
	private final UserSuspensionRepository suspensions;
	private final FriendshipRepository friendships;
	private final UserBlockRepository blocks;
	private final ComicRepository comics;
	private final ImageStorage storage;
	private final PushService push;

	/** 방 도메인 리포지토리와 회원·친구·만화 리포지토리, 저장소, 푸시를 받는다. */
	public RoomService(RoomRepository rooms, RoomMemberRepository members, RoomInviteRepository invites,
			RoomPostRepository posts, RoomPostLikeRepository likes, RoomMessageRepository messages,
			UserProfileRepository profiles, UserSuspensionRepository suspensions, FriendshipRepository friendships,
			UserBlockRepository blocks, ComicRepository comics, ImageStorage storage, PushService push) {
		this.rooms = rooms;
		this.members = members;
		this.invites = invites;
		this.posts = posts;
		this.likes = likes;
		this.messages = messages;
		this.profiles = profiles;
		this.suspensions = suspensions;
		this.friendships = friendships;
		this.blocks = blocks;
		this.comics = comics;
		this.storage = storage;
		this.push = push;
	}

	// ---- 방 ----

	/**
	 * 마지막 소식이 최근인 방이 위(소식이 없으면 만든 시각)
	 *
	 * @param me 요청한 회원 ID
	 * @return 내가 들어간 방 목록
	 */
	@Transactional(readOnly = true)
	public RoomList rooms(long me) {
		List<Long> ids = rooms.findIdsOfMember(me);
		return new RoomList(items(ids, me));
	}

	/**
	 * 방을 만들고 고른 친구에게 초대를 보낸다(바로 넣지 않음). 처음에는 나 혼자 멤버이고 방장이다.
	 *
	 * @param me 방을 만드는 회원 ID
	 * @param name 방 이름(null 가능, 앞뒤 공백을 뺀 뒤 1~20자)
	 * @param memberIds 초대할 친구 회원 ID
	 * @return 만든 방
	 */
	@Transactional
	public RoomItem create(long me, String name, List<Long> memberIds) {
		String trimmed = roomName(name);
		checkInvitees(me, memberIds, "memberIds", "친구를 한 명 이상 골라 주세요.");
		if (memberIds.size() + 1 > MEMBER_MAX) {
			throw memberLimit();
		}
		// 한 사람의 방 20개 제한을 동시에 넘기지 않게 프로필 줄을 처음 읽을 때 잠근다.
		Optional<UserProfile> profile = lockUser(me);
		if (profile.isEmpty() || profile.get().getNickname() == null) {
			throw new ApiException(ErrorCode.PROFILE_REQUIRED);
		}
		requireFriends(me, memberIds, "memberIds");
		checkRoomLimit(me);
		long roomId = rooms.save(new Room(trimmed, me)).getId();
		members.insert(roomId, me);
		// "방을 만들었어요" 줄. 마지막 소식에도 보인다(앱 PR #53 화면과 같게).
		addMessage(roomId, me, "CREATED", null, null);
		sendInvites(me, roomId, trimmed, memberIds);
		return items(List.of(roomId), me).getFirst();
	}

	/**
	 * 방 하나를 돌려준다.
	 *
	 * @param me 요청한 회원 ID
	 * @param roomId 방 ID
	 * @return 방
	 */
	@Transactional(readOnly = true)
	public RoomItem room(long me, long roomId) {
		requireMember(me, roomId);
		return items(List.of(roomId), me).getFirst();
	}

	/**
	 * 방 이름 바꾸기(방장)
	 *
	 * @param me 요청한 회원 ID(방장)
	 * @param roomId 방 ID
	 * @param name 바꿀 방 이름(null 가능, 앞뒤 공백을 뺀 뒤 1~20자)
	 * @return 바꾼 방
	 */
	@Transactional
	public RoomItem rename(long me, long roomId, String name) {
		requireMember(me, roomId);
		Room room = requireOwner(me, rooms.findById(roomId));
		String trimmed = roomName(name);
		room.rename(trimmed);
		return items(List.of(roomId), me).getFirst();
	}

	/**
	 * 방 해산(방장). 방 줄을 지우면 멤버·초대·올린 기록·대화가 외래 키로 함께 지워진다. 만화는 각자 보관함에 남는다.
	 *
	 * @param me 요청한 회원 ID(방장)
	 * @param roomId 해산할 방 ID
	 */
	@Transactional
	public void disband(long me, long roomId) {
		requireMember(me, roomId);
		Room room = requireOwner(me, lockRoom(roomId));
		String name = room.getName();
		List<Long> others = members.findOtherUserIds(roomId, me);
		rooms.delete(room);
		others.forEach(userId -> push.notify(userId, PushService.roomDisbanded(name)));
	}

	/**
	 * 멤버 내보내기(방장). 내보낸 사람의 글은 "나간 멤버"로 남고, 다시 초대할 수 있다.
	 *
	 * @param me 요청한 회원 ID(방장)
	 * @param roomId 방 ID
	 * @param userId 내보낼 멤버 회원 ID
	 */
	@Transactional
	public void kick(long me, long roomId, long userId) {
		requireMember(me, roomId);
		requireOwner(me, lockRoom(roomId));
		if (userId == me) {
			throw ApiException.invalid("userId");
		}
		if (members.deleteMember(roomId, userId) == 0) {
			throw new ApiException(ErrorCode.USER_NOT_FOUND);
		}
	}

	/**
	 * 나가도 내가 올린 만화와 남긴 글은 "나간 멤버"로 남는다. 마지막 한 명이 나가면 방과 올린 기록·대화를 모두 지운다.
	 * 방장은 다른 멤버가 있으면 새 방장(newOwnerId)을 꼭 골라야 한다(초안).
	 *
	 * @param me 나가는 회원 ID
	 * @param roomId 나갈 방 ID
	 * @param newOwnerId 새 방장 회원 ID(방장이 아니거나 혼자면 null)
	 */
	@Transactional
	public void leave(long me, long roomId, Long newOwnerId) {
		requireMember(me, roomId);
		Optional<Room> room = lockRoom(roomId);
		Long owner = room.map(Room::getOwnerId).orElse(null);
		long others = members.countByRoomIdAndUserIdNot(roomId, me);
		if (owner != null && owner == me && others > 0) {
			if (newOwnerId == null) {
				throw new ApiException(ErrorCode.NEW_OWNER_REQUIRED);
			}
			if (newOwnerId == me || !members.existsByRoomIdAndUserId(roomId, newOwnerId)) {
				throw ApiException.invalid("newOwnerId");
			}
			// 아래 멤버 지우기가 먼저 이 변경을 DB에 쓴다(flushAutomatically).
			room.get().changeOwner(newOwnerId);
		}
		members.deleteMember(roomId, me);
		removeEmptyOrHandOver(roomId);
	}

	/**
	 * 멤버가 없으면 방을 지우고, 방장이 멤버가 아니면 가장 먼저 들어온 멤버에게 넘긴다. 탈퇴할 때도 쓴다.
	 *
	 * @param roomId 방 ID
	 */
	@Transactional
	public void removeEmptyOrHandOver(long roomId) {
		rooms.deleteIfEmpty(roomId);
		rooms.handOverIfOwnerLeft(roomId);
	}

	// ---- 초대 ----

	/**
	 * 방장만 친구를 초대한다. 이미 답을 기다리는 초대가 있는 사람은 그 초대를 그대로 돌려준다.
	 *
	 * @param me 요청한 회원 ID(방장)
	 * @param roomId 방 ID
	 * @param userIds 초대할 친구 회원 ID
	 * @return 요청한 친구들의 초대
	 */
	@Transactional
	public InviteList invite(long me, long roomId, List<Long> userIds) {
		requireMember(me, roomId);
		Room room = requireOwner(me, lockRoom(roomId));
		checkInvitees(me, userIds, "userIds", "초대할 친구를 한 명 이상 골라 주세요.");
		requireFriends(me, userIds, "userIds");
		for (int i = 0; i < userIds.size(); i++) {
			if (members.existsByRoomIdAndUserId(roomId, userIds.get(i))) {
				throw ApiException.invalid("userIds[" + i + "]");
			}
		}
		List<Long> fresh = new ArrayList<>();
		for (Long userId : userIds) {
			if (!invites.existsByRoomIdAndUserId(roomId, userId)) {
				fresh.add(userId);
			}
		}
		if (headcount(roomId) + fresh.size() > MEMBER_MAX) {
			throw memberLimit();
		}
		String name = room.getName();
		sendInvites(me, roomId, name, fresh);
		List<RoomInvite> all = sentInvites(List.of(roomId), me).getOrDefault(roomId, List.of());
		return new InviteList(all.stream().filter(i -> userIds.contains(i.invitedUser().userId())).toList());
	}

	/**
	 * 답을 기다리는 초대를 취소한다(방장).
	 *
	 * @param me 요청한 회원 ID(방장)
	 * @param roomId 방 ID
	 * @param inviteId 취소할 초대 ID
	 */
	@Transactional
	public void cancelInvite(long me, long roomId, long inviteId) {
		requireMember(me, roomId);
		requireOwner(me, rooms.findById(roomId));
		if (invites.deleteByIdAndRoomId(inviteId, roomId) == 0) {
			throw inviteNotFound();
		}
	}

	/**
	 * 내가 받은 초대(최신순)
	 *
	 * @param me 요청한 회원 ID
	 * @return 받은 초대 목록
	 */
	@Transactional(readOnly = true)
	public InviteList receivedInvites(long me) {
		List<ReceivedInviteView> rows = invites.findReceived(me);
		Map<Long, RoomMember> people = people(rows.stream().map(ReceivedInviteView::invitedBy).toList(), me);
		return new InviteList(rows.stream()
			.map(i -> new RoomInvite(i.id(), new RoomRef(i.roomId(), i.roomName()), null, people.get(i.invitedBy()),
					i.hasBlocked(), i.createdAt()))
			.toList());
	}

	/**
	 * 수락하면 초대를 지우고 멤버로 넣는다. 들어온 직후 받지 않은 글은 0이다.
	 *
	 * @param me 초대받은 회원 ID
	 * @param inviteId 수락할 초대 ID
	 * @return 들어간 방
	 */
	@Transactional
	public RoomItem acceptInvite(long me, long inviteId) {
		long roomId = invitedRoom(me, inviteId);
		lockRoom(roomId);
		lockUser(me);
		// 잠그기 전에 방장이 취소했을 수 있어 다시 읽는다.
		invitedRoom(me, inviteId);
		checkRoomLimit(me);
		invites.deleteInvite(inviteId);
		members.insert(roomId, me);
		return items(List.of(roomId), me).getFirst();
	}

	/**
	 * 거절해도 방장에게 알리지 않는다.
	 *
	 * @param me 초대받은 회원 ID
	 * @param inviteId 거절할 초대 ID
	 */
	@Transactional
	public void declineInvite(long me, long inviteId) {
		if (invites.deleteByIdAndUserId(inviteId, me) == 0) {
			throw inviteNotFound();
		}
	}

	// ---- 올린 만화 ----

	/**
	 * 방에 올린 만화 목록을 돌려준다.
	 *
	 * @param me 요청한 회원 ID
	 * @param roomId 방 ID
	 * @return 올린 만화 목록
	 */
	@Transactional(readOnly = true)
	public PostList posts(long me, long roomId) {
		requireMember(me, roomId);
		return new PostList(posts(me, roomId, null));
	}

	/**
	 * 내 만화 중 완성된 것만. 같은 만화를 같은 방에 두 번 올릴 수 없다.
	 *
	 * @param me 올리는 회원 ID
	 * @param roomId 방 ID
	 * @param comicId 올릴 내 만화 ID(null이면 400)
	 * @return 올린 만화
	 */
	@Transactional
	public RoomPost postComic(long me, long roomId, Long comicId) {
		requireMember(me, roomId);
		if (comicId == null) {
			throw ApiException.invalid("comicId");
		}
		boolean mine = comics.findByIdAndUserId(comicId, me).map(c -> c.getStatus() == ComicStatus.DONE).orElse(false);
		if (!mine) {
			throw new ApiException(ErrorCode.COMIC_NOT_FOUND);
		}
		long postId = posts.insertNew(roomId, comicId, me).orElseThrow(() -> new ApiException(ErrorCode.ROOM_POST_EXISTS));
		addMessage(roomId, me, "POSTED", postId, null);
		return posts(me, roomId, postId).getFirst();
	}

	/**
	 * 올린 사람만 내린다. 그 만화의 좋아요·대화 줄은 외래 키로 같이 지워진다.
	 *
	 * @param me 요청한 회원 ID(올린 사람)
	 * @param roomId 방 ID
	 * @param postId 내릴 올린 만화 ID
	 */
	@Transactional
	public void removePost(long me, long roomId, long postId) {
		requireMember(me, roomId);
		long author = postAuthor(roomId, postId);
		if (author != me) {
			throw new ApiException(ErrorCode.ROOM_POST_NOT_MINE);
		}
		posts.deletePost(postId);
	}

	/**
	 * 이미 눌렀어도 그대로 200
	 *
	 * @param me 누르는 회원 ID
	 * @param roomId 방 ID
	 * @param postId 올린 만화 ID
	 * @return 좋아요를 반영한 올린 만화
	 */
	@Transactional
	public RoomPost like(long me, long roomId, long postId) {
		requireMember(me, roomId);
		postAuthor(roomId, postId);
		int added = likes.insertIfAbsent(postId, me);
		if (added > 0) {
			addMessage(roomId, me, "LIKED", postId, null);
		}
		return posts(me, roomId, postId).getFirst();
	}

	/**
	 * 안 눌렀어도 그대로 200. 취소하면 그 좋아요 알림 줄도 뺀다.
	 *
	 * @param me 취소하는 회원 ID
	 * @param roomId 방 ID
	 * @param postId 올린 만화 ID
	 * @return 좋아요를 반영한 올린 만화
	 */
	@Transactional
	public RoomPost unlike(long me, long roomId, long postId) {
		requireMember(me, roomId);
		postAuthor(roomId, postId);
		int removed = likes.deleteLike(postId, me);
		if (removed > 0) {
			messages.deleteLiked(postId, me);
		}
		return posts(me, roomId, postId).getFirst();
	}

	// ---- 대화 ----

	/**
	 * 오래된 줄이 위. 처음엔 최근 50줄을 주고, cursor(받은 것 중 가장 오래된 줄)를 주면 그 앞 50줄을 준다(초안).
	 * 부르면 이 방의 받지 않은 글이 0이 된다.
	 *
	 * @param me 요청한 회원 ID
	 * @param roomId 방 ID
	 * @param cursor 받은 것 중 가장 오래된 줄 ID(null이면 최근 50줄)
	 * @return 대화 한 쪽
	 */
	@Transactional
	public MessageList messages(long me, long roomId, String cursor) {
		requireMember(me, roomId);
		PageRequestParams page = PageRequestParams.of(cursor, MESSAGE_PAGE);
		List<RoomMessageView> rows = new ArrayList<>(messages.findPage(roomId, page.before(), Limit.of(MESSAGE_PAGE + 1)));
		String next = null;
		if (rows.size() > MESSAGE_PAGE) {
			rows = new ArrayList<>(rows.subList(0, MESSAGE_PAGE));
			next = String.valueOf(rows.getLast().id());
		}
		java.util.Collections.reverse(rows);
		members.markRead(roomId, me);
		return new MessageList(messages(me, roomId, rows), next);
	}

	/**
	 * 방 대화에 글을 남긴다. postId가 있으면 그 만화에 단 댓글이다.
	 *
	 * @param me 글을 남기는 회원 ID
	 * @param roomId 방 ID
	 * @param text 글 내용(null 가능, 앞뒤 공백을 뺀 뒤 1~200자)
	 * @param postId 댓글을 달 올린 만화 ID(방 전체 글이면 null)
	 * @return 남긴 대화 줄
	 */
	@Transactional
	public RoomMessage comment(long me, long roomId, String text, Long postId) {
		requireMember(me, roomId);
		String trimmed = text == null ? "" : text.strip();
		int length = trimmed.codePointCount(0, trimmed.length());
		if (length < 1 || length > COMMENT_MAX) {
			throw ApiException.invalid("text");
		}
		Long author = postId == null ? null : postAuthor(roomId, postId);
		long id = addMessage(roomId, me, "COMMENT", postId, trimmed);
		// 내 만화에 다른 사람이 댓글을 달면 올린 사람에게만 푸시. 올린 사람이 방에서 나갔으면 보내지 않는다.
		if (author != null && author != me && isMember(author, roomId)) {
			String nickname = profiles.findById(me).map(UserProfile::getNickname).orElse(null);
			push.notify(author, PushService.roomComment(roomId, postId, nickname == null ? "" : nickname));
		}
		return messages(me, roomId, List.of(messages.findView(id).orElseThrow())).getFirst();
	}

	// ---- 안에서 쓰는 것 ----

	/**
	 * 방 이름의 앞뒤 공백을 빼고 길이를 확인한다.
	 *
	 * @param name 방 이름(null 가능)
	 * @return 앞뒤 공백을 뺀 방 이름
	 */
	private static String roomName(String name) {
		String trimmed = name == null ? "" : name.strip();
		int length = trimmed.codePointCount(0, trimmed.length());
		if (length < 1 || length > NAME_MAX) {
			throw ApiException.invalid("name");
		}
		return trimmed;
	}

	/**
	 * 비었거나, 같은 사람이 두 번이거나, 나를 넣으면 400.
	 *
	 * @param me 요청한 회원 ID
	 * @param ids 초대할 회원 ID(null 가능)
	 * @param field 틀렸을 때 알릴 필드 이름
	 * @param emptyMessage 비었을 때의 안내 문구(지금은 쓰지 않음)
	 */
	private static void checkInvitees(long me, List<Long> ids, String field, String emptyMessage) {
		if (ids == null || ids.isEmpty()) {
			throw ApiException.invalid(field);
		}
		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < ids.size(); i++) {
			Long id = ids.get(i);
			if (id == null || id == me || !seen.add(id)) {
				throw ApiException.invalid(field + "[" + i + "]");
			}
		}
	}

	/**
	 * 모두 내 친구여야 한다. 아니면 404 USER_NOT_FOUND.
	 *
	 * @param me 요청한 회원 ID
	 * @param ids 확인할 회원 ID
	 * @param field 틀렸을 때 알릴 필드 이름
	 */
	private void requireFriends(long me, List<Long> ids, String field) {
		for (int i = 0; i < ids.size(); i++) {
			if (!friendships.areFriends(me, ids.get(i))) {
				throw new ApiException(ErrorCode.USER_NOT_FOUND, field + "[" + i + "]");
			}
		}
	}

	/**
	 * 초대 줄을 넣고 초대받은 사람에게 푸시를 보낸다.
	 *
	 * @param me 초대하는 방장 회원 ID
	 * @param roomId 방 ID
	 * @param roomName 푸시에 넣을 방 이름
	 * @param userIds 초대받을 회원 ID
	 */
	private void sendInvites(long me, long roomId, String roomName, List<Long> userIds) {
		for (Long userId : userIds) {
			long inviteId = invites.insert(roomId, userId, me);
			push.notify(userId, PushService.roomInvite(inviteId, roomId, roomName));
		}
	}

	/**
	 * 멤버 + 답을 기다리는 초대
	 *
	 * @param roomId 방 ID
	 * @return 멤버 수 + 답을 기다리는 초대 수
	 */
	private int headcount(long roomId) {
		return (int) (members.countByRoomId(roomId) + invites.countByRoomId(roomId));
	}

	/**
	 * 이미 들어간 방이 20개면 막는다.
	 *
	 * @param userId 확인할 회원 ID
	 */
	private void checkRoomLimit(long userId) {
		if (members.countByUserId(userId) >= ROOM_LIMIT) {
			throw new ApiException(ErrorCode.ROOM_LIMIT);
		}
	}

	/**
	 * 같은 방의 인원을 동시에 바꾸지 않게 방 줄을 잠그고 읽는다. 그 사이 방이 지워졌으면 비어 있다.
	 *
	 * @param roomId 방 ID
	 * @return 잠근 방(없으면 빈 값)
	 */
	private Optional<Room> lockRoom(long roomId) {
		return rooms.findByIdForUpdate(roomId);
	}

	/**
	 * 한 사람의 방 20개 제한을 동시에 넘기지 않게 프로필 줄을 잠근다. 프로필이 없으면 잠그지 않는다.
	 *
	 * @param userId 회원 ID
	 * @return 잠근 프로필(없으면 빈 값)
	 */
	private Optional<UserProfile> lockUser(long userId) {
		return profiles.findByIdForUpdate(userId);
	}

	/**
	 * 방장이 아니면 막는다. 그 사이 방이 지워졌으면 방 없음 오류.
	 *
	 * @param me 요청한 회원 ID
	 * @param room 확인할 방(빈 값이면 방 없음)
	 * @return 방
	 */
	private Room requireOwner(long me, Optional<Room> room) {
		Room found = room.orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));
		if (!found.isOwnedBy(me)) {
			throw new ApiException(ErrorCode.ROOM_OWNER_ONLY);
		}
		return found;
	}

	/**
	 * 내가 받은 초대의 방 ID. 없으면 초대 없음 오류.
	 *
	 * @param me 초대받은 회원 ID
	 * @param inviteId 초대 ID
	 * @return 방 ID
	 */
	private long invitedRoom(long me, long inviteId) {
		return invites.findRoomIdByIdAndUserId(inviteId, me).orElseThrow(RoomService::inviteNotFound);
	}

	// 방 인원 초과 오류를 만든다.
	private static ApiException memberLimit() {
		return new ApiException(ErrorCode.ROOM_MEMBER_LIMIT);
	}

	// 초대 없음 오류를 만든다.
	private static ApiException inviteNotFound() {
		return new ApiException(ErrorCode.ROOM_INVITE_NOT_FOUND);
	}

	/**
	 * 방장이 보는 답을 기다리는 초대. 방장이 아닌 방은 빈 목록.
	 *
	 * @param roomIds 볼 방 ID들
	 * @param me 방장 회원 ID(나)
	 * @return 방 ID별 초대
	 */
	private Map<Long, List<RoomInvite>> sentInvites(List<Long> roomIds, long me) {
		List<SentInviteView> rows = invites.findSent(roomIds, me);
		Map<Long, RoomMember> people = people(rows.stream().map(SentInviteView::userId).toList(), me);
		Map<Long, List<RoomInvite>> result = new HashMap<>();
		rows.forEach(i -> result.computeIfAbsent(i.roomId(), k -> new ArrayList<>())
			.add(new RoomInvite(i.id(), new RoomRef(i.roomId(), i.roomName()), people.get(i.userId()), null, false,
					i.createdAt())));
		return result;
	}

	/**
	 * 지금 이 방의 멤버인지 본다.
	 *
	 * @param userId 확인할 회원 ID
	 * @param roomId 방 ID
	 * @return 멤버이면 true
	 */
	private boolean isMember(long userId, long roomId) {
		return members.existsByRoomIdAndUserId(roomId, userId);
	}

	/**
	 * 멤버가 아니면 방 없음 오류.
	 *
	 * @param me 요청한 회원 ID
	 * @param roomId 방 ID
	 */
	private void requireMember(long me, long roomId) {
		if (!isMember(me, roomId)) {
			throw new ApiException(ErrorCode.ROOM_NOT_FOUND);
		}
	}

	/**
	 * 이 방에 올린 만화의 올린 사람. 없거나 다른 방이면 404.
	 *
	 * @param roomId 방 ID
	 * @param postId 올린 만화 ID
	 * @return 올린 회원 ID
	 */
	private long postAuthor(long roomId, long postId) {
		return posts.findByIdAndRoomId(postId, roomId)
			.map(com.lolog.api.domain.room.entity.RoomPost::getUserId)
			.orElseThrow(() -> new ApiException(ErrorCode.ROOM_POST_NOT_FOUND));
	}

	/**
	 * 대화 줄을 넣고 방의 마지막 소식 시각을 바꾼다.
	 *
	 * @param roomId 방 ID
	 * @param userId 남긴 회원 ID
	 * @param type CREATED·POSTED·LIKED·COMMENT
	 * @param postId 관련 올린 만화 ID(없으면 null)
	 * @param body 댓글 내용(COMMENT가 아니면 null)
	 * @return 새 대화 줄 ID
	 */
	private long addMessage(long roomId, long userId, String type, Long postId, String body) {
		long id = messages.insert(roomId, userId, type, postId, body);
		rooms.touchActivity(roomId);
		return id;
	}

	/**
	 * 방에 남긴 글의 사람. 지금 멤버가 아니면 "나간 멤버"로 바꾼다.
	 *
	 * @param userIds 글을 남긴 회원 ID들
	 * @param me 보는 회원 ID(나)
	 * @param roomId 방 ID
	 * @return 회원 ID별 사람 정보
	 */
	private Map<Long, RoomMember> authors(Collection<Long> userIds, long me, long roomId) {
		return people(userIds, me, roomId, true);
	}

	/**
	 * 회원들의 닉네임과 프로필 캐릭터 썸네일, 나와의 관계, 정지 여부(방 목록·초대처럼 방이 정해지지 않은 화면).
	 *
	 * @param userIds 읽을 회원 ID들
	 * @param me 보는 회원 ID(나)
	 * @return 회원 ID별 사람 정보
	 */
	private Map<Long, RoomMember> people(Collection<Long> userIds, long me) {
		return people(userIds, me, null, false);
	}

	/**
	 * 사람 정보를 한 번에 읽는다. roomId가 있으면 그 방의 방장 표시를 하고, leftAsLeftMember면 그 방 멤버가 아닌 사람을 "나간 멤버"로 바꾼다.
	 *
	 * @param userIds 읽을 회원 ID들
	 * @param me 보는 회원 ID(나)
	 * @param roomId 방 ID(방이 정해지지 않은 화면이면 null)
	 * @param leftAsLeftMember 그 방 멤버가 아닌 사람을 나간 멤버로 바꿀지
	 * @return 회원 ID별 사람 정보
	 */
	private Map<Long, RoomMember> people(Collection<Long> userIds, long me, Long roomId, boolean leftAsLeftMember) {
		Map<Long, RoomMember> result = new HashMap<>();
		if (userIds.isEmpty()) {
			return result;
		}
		for (RoomPersonView p : members.findPeople(me, roomId, userIds)) {
			long id = p.userId();
			if (leftAsLeftMember && !p.member()) {
				result.put(id, RoomMember.leftMember());
				continue;
			}
			String thumb = p.thumbKey();
			result.put(id, new RoomMember(id, p.nickname() == null ? "" : p.nickname(),
					thumb == null ? null : storage.presignedUrl(thumb).toString(), id == me, p.owner(), false,
					friendState(id, me, p), p.suspended()));
		}
		// 프로필 줄이 없는 회원(드묾)은 따로 읽어 빈 닉네임으로 채운다.
		List<Long> missing = userIds.stream().filter(id -> !result.containsKey(id)).distinct().toList();
		if (!missing.isEmpty()) {
			Map<Long, String> states = friendStates(missing, me);
			Set<Long> suspended = new HashSet<>(suspensions.findSuspendedIdsAmong(missing));
			Set<Long> current = leftAsLeftMember ? new HashSet<>(members.findUserIdsByRoomId(roomId)) : Set.of();
			Long owner = roomId == null ? null : ownerOf(roomId);
			for (Long id : missing) {
				result.put(id, leftAsLeftMember && !current.contains(id) ? RoomMember.leftMember()
						: new RoomMember(id, "", null, id == me, owner != null && id.equals(owner), false, states.get(id),
								suspended.contains(id)));
			}
		}
		return result;
	}

	/**
	 * 나와 그 사람의 관계. 내가 차단했으면 BLOCKED가 먼저다.
	 *
	 * @param id 상대 회원 ID
	 * @param me 보는 회원 ID(나)
	 * @param p 상대 정보
	 * @return FRIEND·REQUESTED·RECEIVED·NONE·BLOCKED·ME
	 */
	private static String friendState(long id, long me, RoomPersonView p) {
		if (id == me) {
			return "ME";
		}
		if (p.blocked()) {
			return "BLOCKED";
		}
		if (p.sentStatus() == FriendshipStatus.ACCEPTED || p.receivedStatus() == FriendshipStatus.ACCEPTED) {
			return "FRIEND";
		}
		if (p.sentStatus() != null) {
			return "REQUESTED";
		}
		return p.receivedStatus() != null ? "RECEIVED" : "NONE";
	}

	/**
	 * 나와 그 사람의 관계. 내가 차단했으면 BLOCKED가 먼저다.
	 *
	 * @param userIds 상대 회원 ID들
	 * @param me 보는 회원 ID(나)
	 * @return 회원 ID별 관계
	 */
	private Map<Long, String> friendStates(Collection<Long> userIds, long me) {
		Map<Long, String> states = new HashMap<>();
		userIds.forEach(id -> states.put(id, id == me ? "ME" : "NONE"));
		for (Friendship f : friendships.findAllBetween(me, userIds)) {
			boolean sent = f.isRequestedBy(me);
			states.put(f.otherThan(me), f.isAccepted() ? "FRIEND" : sent ? "REQUESTED" : "RECEIVED");
		}
		blocks.findBlockedIdsAmong(me, userIds).forEach(id -> states.put(id, "BLOCKED"));
		return states;
	}

	/**
	 * 내가 차단하고 방 글을 숨기기로 한 사람들
	 *
	 * @param me 보는 회원 ID(나)
	 * @return 방 글을 숨길 회원 ID
	 */
	private Set<Long> hiddenUsers(long me) {
		return new HashSet<>(blocks.findHiddenInRoomsIds(me));
	}

	/**
	 * 방 응답을 만든다(멤버·마지막 소식·받지 않은 글·초대).
	 *
	 * @param roomIds 방 ID들(이 순서로 돌려줌)
	 * @param me 보는 회원 ID(나)
	 * @return 방 응답(그 사이 지워진 방은 빠짐)
	 */
	private List<RoomItem> items(List<Long> roomIds, long me) {
		if (roomIds.isEmpty()) {
			return List.of();
		}
		Map<Long, List<Long>> memberIds = new HashMap<>();
		members.findByRoomIdInOrderByCreatedAtAscUserIdAsc(roomIds)
			.forEach(m -> memberIds.computeIfAbsent(m.getRoomId(), k -> new ArrayList<>()).add(m.getUserId()));
		List<RoomItemView> found = rooms.findItems(me, roomIds);
		Set<Long> everyone = new HashSet<>();
		memberIds.values().forEach(everyone::addAll);
		found.stream().map(RoomItemView::lastUserId).filter(java.util.Objects::nonNull).forEach(everyone::add);
		Map<Long, RoomMember> people = people(everyone, me);
		Set<String> memberPairs = new HashSet<>();
		memberIds.forEach((room, users) -> users.forEach(u -> memberPairs.add(room + ":" + u)));
		Map<Long, List<RoomInvite>> sent = sentInvites(roomIds, me);
		Set<Long> hidden = hiddenUsers(me);
		Map<Long, RoomItem> byId = new LinkedHashMap<>();
		found.forEach(r -> {
			long id = r.id();
			long owner = r.ownerId();
			Long lastUser = r.lastUserId();
			RoomActivity activity = lastUser == null ? null
					: new RoomActivity("COMMENT".equals(r.lastType()) ? "COMMENTED" : r.lastType(),
							memberPairs.contains(id + ":" + lastUser) ? people.get(lastUser).nickname() : null,
							lastUser == me, r.lastAt(), hidden.contains(lastUser) ? null : r.lastBody());
			List<RoomMember> roomMembers = memberIds.getOrDefault(id, List.of())
				.stream()
				.map(people::get)
				.map(m -> withOwner(m, owner))
				.toList();
			byId.put(id, new RoomItem(id, r.name(), owner, roomMembers, sent.getOrDefault(id, List.of()), activity,
					(int) r.unread(), r.createdAt()));
		});
		return roomIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
	}

	/**
	 * 방장이면 isOwner를 true로 바꾼다.
	 *
	 * @param m 멤버
	 * @param owner 방장 회원 ID
	 * @return 방장 표시를 맞춘 멤버
	 */
	private static RoomMember withOwner(RoomMember m, long owner) {
		return m.owner(m.userId() == owner);
	}

	/**
	 * 방장 ID. 방이 없으면 null.
	 *
	 * @param roomId 방 ID
	 * @return 방장 회원 ID(방이 없으면 null)
	 */
	private Long ownerOf(long roomId) {
		return rooms.findById(roomId).map(Room::getOwnerId).orElse(null);
	}

	/**
	 * 방의 올린 만화. onlyPost가 있으면 그 하나만. 최근에 올린 것이 앞.
	 *
	 * @param me 보는 회원 ID(나)
	 * @param roomId 방 ID
	 * @param onlyPost 이 하나만 읽을 올린 만화 ID(null이면 모두)
	 * @return 올린 만화
	 */
	private List<RoomPost> posts(long me, long roomId, Long onlyPost) {
		List<RoomPostView> rows = posts.findViews(me, roomId, onlyPost);
		Map<Long, RoomMember> people = authors(rows.stream().map(RoomPostView::userId).toList(), me, roomId);
		Set<Long> hidden = hiddenUsers(me);
		return rows.stream().map(p -> {
			boolean hide = hidden.contains(p.userId());
			return new RoomPost(p.id(), roomId, p.comicId(), hide ? null : p.title() == null ? "" : p.title(),
					hide ? null : url(p.imageKey()), people.get(p.userId()), p.createdAt(), (int) p.likes(), p.liked(),
					(int) p.comments(), hide);
		}).toList();
	}

	/**
	 * 대화 줄을 응답 모양으로 바꾼다. 숨긴 사람의 줄은 내용을 뺀다. 내렸거나 없는 만화에 단 줄은 만화 요약이 없다.
	 *
	 * @param me 보는 회원 ID(나)
	 * @param roomId 방 ID
	 * @param rows 읽은 대화 줄
	 * @return 대화 줄 응답
	 */
	private List<RoomMessage> messages(long me, long roomId, List<RoomMessageView> rows) {
		Map<Long, RoomMember> people = authors(rows.stream().map(RoomMessageView::userId).toList(), me, roomId);
		Set<Long> hidden = hiddenUsers(me);
		return rows.stream().map(r -> {
			boolean hide = hidden.contains(r.userId());
			RoomPostSummary summary = hide || r.comicId() == null ? null
					: new RoomPostSummary(r.postId(), r.postTitle() == null ? "" : r.postTitle(), url(r.postImageKey()));
			return new RoomMessage(r.id(), r.type(), people.get(r.userId()), r.createdAt(), r.postId(), summary,
					hide ? null : r.body(), hide);
		}).toList();
	}

	/**
	 * 저장소 키를 서명 주소로 바꾼다.
	 *
	 * @param key 저장소 key(null 가능)
	 * @return 서명 주소(key가 null이면 null)
	 */
	private String url(String key) {
		return key == null ? null : storage.presignedUrl(key).toString();
	}

	/**
	 * 들어간 방에 아직 읽지 않은 남의 글이 있거나 받은 초대가 있는지 본다(방 새 소식 점).
	 *
	 * @param me 확인할 회원 ID
	 * @return 새 소식이 있으면 true
	 */
	@Transactional(readOnly = true)
	public boolean hasNews(long me) {
		return members.existsUnread(me) || invites.existsByUserId(me);
	}
}
