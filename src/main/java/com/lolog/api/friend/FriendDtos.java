package com.lolog.api.friend;

import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

// 프로필·친구·차단 API의 요청·응답 모양. LOLog-docs friends.md 2장과 같다.
public final class FriendDtos {

	private FriendDtos() {
	}

	// friendCode는 닉네임을 정하기 전에는 null이다.
	// character는 프로필 캐릭터 기능(characters.md)에서 채운다. 그 전까지는 항상 null이다.
	public record ProfileResponse(String nickname, String friendCode, Object character) {
	}

	// nickname 길이는 앞뒤 공백을 뺀 뒤에 보므로 서비스에서 확인한다.
	public record ProfileUpdateRequest(String nickname) {
	}

	// friendCode 길이는 대문자로 바꾸기 전 앞뒤 공백을 뺀 뒤에 보므로 서비스에서 확인한다.
	public record FriendRequestCreate(String friendCode) {
	}

	public record FriendUser(Long userId, String nickname, String friendCode) {
	}

	public record FriendRequestResponse(Long id, String direction, FriendUser user, String status,
			OffsetDateTime createdAt) {
	}

	public record FriendRequestList(List<FriendRequestResponse> requests) {
	}

	// character는 프로필 캐릭터 기능에서 { thumbnailUrl }로 채운다. 그 전까지는 항상 null이다.
	public record FriendItem(Long userId, String nickname, String friendCode, OffsetDateTime friendsSince,
			Object character) {
	}

	public record FriendList(List<FriendItem> friends) {
	}

	public record BlockCreate(@NotNull Long userId) {
	}

	public record BlockItem(Long userId, String nickname, String friendCode, OffsetDateTime blockedAt) {
	}

	public record BlockList(List<BlockItem> blocks) {
	}

}
