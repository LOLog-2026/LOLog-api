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

	// thumbnailUrl은 그 사람의 프로필 캐릭터 256 그림(없으면 null)
	public record RoomMember(Long userId, String nickname, String thumbnailUrl, boolean isMe) {
	}

	// 마지막 소식. 문장은 앱이 만든다.
	public record RoomActivity(String type, String nickname, boolean isMe, OffsetDateTime at) {
	}

	public record RoomItem(Long id, String name, List<RoomMember> members, RoomActivity lastActivity, int unread,
			OffsetDateTime createdAt) {
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

	// type은 POSTED·LIKED·COMMENT. text는 COMMENT일 때만.
	public record RoomMessage(Long id, String type, RoomMember member, OffsetDateTime at, Long postId,
			RoomPostSummary post, String text) {
	}

	public record MessageList(List<RoomMessage> messages) {
	}

}
