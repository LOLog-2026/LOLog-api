package com.lolog.api.room;

import java.time.OffsetDateTime;
import java.util.List;

// 친구와 같이 쓰는 방의 요청·응답 모양. LOLog-docs rooms.md 2장(초안)과 같다.
public final class RoomDtos {

	private RoomDtos() {
	}

	// 길이·개수는 앞뒤 공백을 뺀 뒤에 보므로 서비스에서 확인한다.
	public record RoomCreateRequest(String name, List<Long> memberIds) {
	}

	public record PostCreateRequest(Long comicId) {
	}

	public record RoomCommentRequest(String text, Long postId) {
	}

	// POST /api/rooms/{id}/invites. 초대할 친구 회원 ID(나를 뺌)
	public record InviteCreateRequest(List<Long> userIds) {
	}

	// PATCH /api/rooms/{id}. 방 이름 바꾸기(방장)
	public record RoomUpdateRequest(String name) {
	}

	// thumbnailUrl은 그 사람의 프로필 캐릭터 256 그림(없으면 null). isOwner는 방장이면 true(초안).
	// 방에서 나간 사람이면 userId·nickname·thumbnailUrl이 null이고 left가 true다. 앱은 "나간 멤버"로 보여 준다(초안).
	// friendState는 친구 추가 버튼에 쓴다: FRIEND, REQUESTED(내가 보냄), RECEIVED(상대가 보냄), NONE, BLOCKED(내가 차단), ME.
	// 나간 멤버는 null이다(초안).
	public record RoomMember(Long userId, String nickname, String thumbnailUrl, boolean isMe, boolean isOwner,
			boolean left, String friendState) {

		static RoomMember leftMember() {
			return new RoomMember(null, null, null, false, false, true, null);
		}

		RoomMember owner(boolean isOwner) {
			return new RoomMember(userId, nickname, thumbnailUrl, isMe, isOwner, left, friendState);
		}

	}

	// 마지막 소식. 문장은 앱이 만든다. type은 CREATED·POSTED·LIKED·COMMENTED, text는 COMMENTED일 때 댓글 내용.
	public record RoomActivity(String type, String nickname, boolean isMe, OffsetDateTime at, String text) {
	}

	// ownerId는 방장. invites는 답을 기다리는 초대이고 방장이 아니면 빈 배열이다(초안).
	public record RoomItem(Long id, String name, Long ownerId, List<RoomMember> members, List<RoomInvite> invites,
			RoomActivity lastActivity, int unread, OffsetDateTime createdAt) {
	}

	public record RoomRef(Long id, String name) {
	}

	// 방장이 보는 초대는 invitedUser, 내가 받은 초대는 invitedBy를 채우고 다른 쪽은 null이다(초안).
	// hasBlockedMember는 내가 차단한 사람이 그 방에 있으면 true. 받은 초대를 수락하기 전에 알려 줄 때 쓴다.
	public record RoomInvite(Long id, RoomRef room, RoomMember invitedUser, RoomMember invitedBy, boolean hasBlockedMember,
			OffsetDateTime createdAt) {
	}

	public record InviteList(List<RoomInvite> invites) {
	}

	public record RoomList(List<RoomItem> rooms) {
	}

	// hidden은 내가 숨기기로 한 차단한 사람이 올린 것. title·imageUrl이 null이고 앱은 "숨긴 글"로 보여 준다(초안).
	public record RoomPost(Long id, Long roomId, Long comicId, String title, String imageUrl, RoomMember author,
			OffsetDateTime postedAt, int likeCount, boolean likedByMe, int commentCount, boolean hidden) {
	}

	public record PostList(List<RoomPost> posts) {
	}

	public record RoomPostSummary(Long id, String title, String imageUrl) {
	}

	// type은 CREATED·POSTED·LIKED·COMMENT. text는 COMMENT일 때만.
	// hidden은 내가 숨기기로 한 차단한 사람의 줄. text·post가 null이다(초안).
	public record RoomMessage(Long id, String type, RoomMember member, OffsetDateTime at, Long postId,
			RoomPostSummary post, String text, boolean hidden) {
	}

	// nextCursor는 더 오래된 줄이 남았을 때 다음 요청의 cursor, 없으면 null
	public record MessageList(List<RoomMessage> messages, String nextCursor) {
	}

}
