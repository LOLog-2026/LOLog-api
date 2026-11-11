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

	// thumbnailUrl은 그 사람의 프로필 캐릭터 256 그림(없으면 null). isOwner는 방장이면 true(초안).
	public record RoomMember(Long userId, String nickname, String thumbnailUrl, boolean isMe, boolean isOwner) {
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
	public record RoomInvite(Long id, RoomRef room, RoomMember invitedUser, RoomMember invitedBy,
			OffsetDateTime createdAt) {
	}

	public record InviteList(List<RoomInvite> invites) {
	}

	public record RoomList(List<RoomItem> rooms) {
	}

	public record RoomPost(Long id, Long roomId, Long comicId, String title, String imageUrl, RoomMember author,
			OffsetDateTime postedAt, int likeCount, boolean likedByMe, int commentCount) {
	}

	public record PostList(List<RoomPost> posts) {
	}

	public record RoomPostSummary(Long id, String title, String imageUrl) {
	}

	// type은 CREATED·POSTED·LIKED·COMMENT. text는 COMMENT일 때만.
	public record RoomMessage(Long id, String type, RoomMember member, OffsetDateTime at, Long postId,
			RoomPostSummary post, String text) {
	}

	public record MessageList(List<RoomMessage> messages) {
	}

}
