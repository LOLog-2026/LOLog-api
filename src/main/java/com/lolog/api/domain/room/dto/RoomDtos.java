package com.lolog.api.domain.room.dto;

import java.time.OffsetDateTime;
import java.util.List;

// 친구와 같이 쓰는 방의 요청·응답 모양. LOLog-docs rooms.md 2장(초안)과 같다.
public final class RoomDtos {

	private RoomDtos() {
	}

	/**
	 * 길이·개수는 앞뒤 공백을 뺀 뒤에 보므로 서비스에서 확인한다.
	 *
	 * @param name 방 이름(앞뒤 공백을 뺀 뒤 1~20자)
	 * @param memberIds 초대할 친구 회원 ID(나를 뺌)
	 */
	public record RoomCreateRequest(String name, List<Long> memberIds) {
	}

	/**
	 * 만화 올리기 요청.
	 *
	 * @param comicId 올릴 내 만화 ID
	 */
	public record PostCreateRequest(Long comicId) {
	}

	/**
	 * 방 대화에 글 남기기 요청. postId가 있으면 그 만화에 단 댓글이다.
	 *
	 * @param text 글 내용(앞뒤 공백을 뺀 뒤 1~200자)
	 * @param postId 댓글을 달 올린 만화 ID(방 전체 글이면 null)
	 */
	public record RoomCommentRequest(String text, Long postId) {
	}

	/**
	 * POST /api/rooms/{id}/invites. 초대할 친구 회원 ID(나를 뺌)
	 *
	 * @param userIds 초대할 친구 회원 ID
	 */
	public record InviteCreateRequest(List<Long> userIds) {
	}

	/**
	 * PATCH /api/rooms/{id}. 방 이름 바꾸기(방장)
	 *
	 * @param name 바꿀 방 이름(앞뒤 공백을 뺀 뒤 1~20자)
	 */
	public record RoomUpdateRequest(String name) {
	}

	/**
	 * thumbnailUrl은 그 사람의 프로필 캐릭터 256 그림(없으면 null). isOwner는 방장이면 true(초안).
	 * 방에서 나간 사람이면 userId·nickname·thumbnailUrl이 null이고 left가 true다. 앱은 "나간 멤버"로 보여 준다(초안).
	 * friendState는 친구 추가 버튼에 쓴다: FRIEND, REQUESTED(내가 보냄), RECEIVED(상대가 보냄), NONE, BLOCKED(내가 차단), ME.
	 * 나간 멤버는 null이다(초안). suspended는 정지된 계정이면 true(admin.md 2장, 초안).
	 *
	 * @param userId 회원 ID(나간 멤버는 null)
	 * @param nickname 닉네임(나간 멤버는 null)
	 * @param thumbnailUrl 프로필 캐릭터 그림 주소(없거나 나간 멤버는 null)
	 * @param isMe 나인지
	 * @param isOwner 방장인지
	 * @param left 방에서 나간 멤버인지
	 * @param friendState FRIEND·REQUESTED·RECEIVED·NONE·BLOCKED·ME(나간 멤버는 null)
	 * @param suspended 정지된 계정인지
	 */
	public record RoomMember(Long userId, String nickname, String thumbnailUrl, boolean isMe, boolean isOwner,
			boolean left, String friendState, boolean suspended) {

		// 나간 멤버 자리를 만든다.
		public static RoomMember leftMember() {
			return new RoomMember(null, null, null, false, false, true, null, false);
		}

		/**
		 * 방장 여부만 바꾼 사본을 돌려준다.
		 *
		 * @param isOwner 방장인지
		 * @return isOwner만 바꾼 사본
		 */
		public RoomMember owner(boolean isOwner) {
			return new RoomMember(userId, nickname, thumbnailUrl, isMe, isOwner, left, friendState, suspended);
		}

	}

	/**
	 * 마지막 소식. 문장은 앱이 만든다. type은 CREATED·POSTED·LIKED·COMMENTED, text는 COMMENTED일 때 댓글 내용.
	 *
	 * @param type CREATED·POSTED·LIKED·COMMENTED
	 * @param nickname 소식을 남긴 사람 닉네임(지금 멤버가 아니면 null)
	 * @param isMe 내가 남긴 소식인지
	 * @param at 소식 시각
	 * @param text 댓글 내용(COMMENTED일 때, 숨긴 사람이면 null)
	 */
	public record RoomActivity(String type, String nickname, boolean isMe, OffsetDateTime at, String text) {
	}

	/**
	 * ownerId는 방장. invites는 답을 기다리는 초대이고 방장이 아니면 빈 배열이다(초안).
	 *
	 * @param id 방 ID
	 * @param name 방 이름
	 * @param ownerId 방장 회원 ID
	 * @param members 멤버(들어온 순서)
	 * @param invites 답을 기다리는 초대(방장이 아니면 빈 배열)
	 * @param lastActivity 마지막 소식(없으면 null)
	 * @param unread 내가 받지 않은 글 수
	 * @param createdAt 방을 만든 시각
	 */
	public record RoomItem(Long id, String name, Long ownerId, List<RoomMember> members, List<RoomInvite> invites,
			RoomActivity lastActivity, int unread, OffsetDateTime createdAt) {
	}

	/**
	 * 방 ID와 이름만 담은 짧은 모양.
	 *
	 * @param id 방 ID
	 * @param name 방 이름
	 */
	public record RoomRef(Long id, String name) {
	}

	/**
	 * 방장이 보는 초대는 invitedUser, 내가 받은 초대는 invitedBy를 채우고 다른 쪽은 null이다(초안).
	 * hasBlockedMember는 내가 차단한 사람이 그 방에 있으면 true. 받은 초대를 수락하기 전에 알려 줄 때 쓴다.
	 *
	 * @param id 초대 ID
	 * @param room 초대한 방
	 * @param invitedUser 초대받은 사람(방장이 보는 초대일 때, 아니면 null)
	 * @param invitedBy 초대한 방장(내가 받은 초대일 때, 아니면 null)
	 * @param hasBlockedMember 내가 차단한 사람이 그 방에 있는지
	 * @param createdAt 초대한 시각
	 */
	public record RoomInvite(Long id, RoomRef room, RoomMember invitedUser, RoomMember invitedBy, boolean hasBlockedMember,
			OffsetDateTime createdAt) {
	}

	/**
	 * 초대 목록 응답.
	 *
	 * @param invites 초대
	 */
	public record InviteList(List<RoomInvite> invites) {
	}

	/**
	 * 방 목록 응답.
	 *
	 * @param rooms 방(마지막 소식이 최근인 방이 위)
	 */
	public record RoomList(List<RoomItem> rooms) {
	}

	/**
	 * hidden은 내가 숨기기로 한 차단한 사람이 올린 것. title·imageUrl이 null이고 앱은 "숨긴 글"로 보여 준다(초안).
	 *
	 * @param id 올린 만화 ID
	 * @param roomId 방 ID
	 * @param comicId 만화 ID
	 * @param title 만화 제목(숨긴 글이면 null)
	 * @param imageUrl 만화 그림 주소(숨긴 글이면 null)
	 * @param author 올린 사람
	 * @param postedAt 올린 시각
	 * @param likeCount 좋아요 수
	 * @param likedByMe 내가 좋아요를 눌렀는지
	 * @param commentCount 댓글 수
	 * @param hidden 숨긴 글인지
	 */
	public record RoomPost(Long id, Long roomId, Long comicId, String title, String imageUrl, RoomMember author,
			OffsetDateTime postedAt, int likeCount, boolean likedByMe, int commentCount, boolean hidden) {
	}

	/**
	 * 올린 만화 목록 응답.
	 *
	 * @param posts 올린 만화(최근에 올린 것이 앞)
	 */
	public record PostList(List<RoomPost> posts) {
	}

	/**
	 * 대화 줄에 붙는 만화 요약.
	 *
	 * @param id 올린 만화 ID
	 * @param title 만화 제목
	 * @param imageUrl 만화 그림 주소(null 가능)
	 */
	public record RoomPostSummary(Long id, String title, String imageUrl) {
	}

	/**
	 * type은 CREATED·POSTED·LIKED·COMMENT. text는 COMMENT일 때만.
	 * hidden은 내가 숨기기로 한 차단한 사람의 줄. text·post가 null이다(초안).
	 *
	 * @param id 대화 줄 ID
	 * @param type CREATED·POSTED·LIKED·COMMENT
	 * @param member 남긴 사람
	 * @param at 남긴 시각
	 * @param postId 관련 올린 만화 ID(없으면 null)
	 * @param post 관련 만화 요약(없거나 숨긴 줄이면 null)
	 * @param text 댓글 내용(COMMENT일 때, 숨긴 줄이면 null)
	 * @param hidden 숨긴 줄인지
	 */
	public record RoomMessage(Long id, String type, RoomMember member, OffsetDateTime at, Long postId,
			RoomPostSummary post, String text, boolean hidden) {
	}

	/**
	 * nextCursor는 더 오래된 줄이 남았을 때 다음 요청의 cursor, 없으면 null
	 *
	 * @param messages 대화 줄(오래된 줄이 위)
	 * @param nextCursor 다음 요청의 cursor(더 없으면 null)
	 */
	public record MessageList(List<RoomMessage> messages, String nextCursor) {
	}

}
