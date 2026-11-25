package com.lolog.api.domain.friend.dto;

import com.lolog.api.domain.character.service.CharacterViews.FriendCharacter;
import com.lolog.api.domain.character.service.ProfileCharacterService.ProfileCharacterResponse;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

// 프로필·친구·차단 API의 요청·응답 모양. LOLog-docs friends.md 2장과 같다.
public final class FriendDtos {

	private FriendDtos() {
	}

	/**
	 * userId는 내 회원 ID. 앱이 화자에 내 프로필 캐릭터를 붙일 때 CharacterRef의 id로 쓴다(LOLog-docs PR #56).
	 * friendCode는 닉네임을 정하기 전에는 null이다. character는 내 프로필 캐릭터(만든 적 없거나 지웠으면 null).
	 *
	 * @param userId 내 회원 ID
	 * @param nickname 내 닉네임(정하기 전에는 null)
	 * @param friendCode 내 친구 코드(닉네임을 정하기 전에는 null)
	 * @param character 내 프로필 캐릭터(없으면 null)
	 */
	public record ProfileResponse(Long userId, String nickname, String friendCode, ProfileCharacterResponse character) {
	}

	/**
	 * nickname 길이는 앞뒤 공백을 뺀 뒤에 보므로 서비스에서 확인한다.
	 *
	 * @param nickname 바꿀 닉네임(앞뒤 공백을 뺀 뒤 1~10자)
	 */
	public record ProfileUpdateRequest(String nickname) {
	}

	/**
	 * friendCode 길이는 대문자로 바꾸기 전 앞뒤 공백을 뺀 뒤에 보므로 서비스에서 확인한다.
	 * userId는 같은 방 멤버에게 친구 코드 없이 보낼 때(초안). 둘 중 하나만 보낸다.
	 *
	 * @param friendCode 상대의 친구 코드(userId와 둘 중 하나)
	 * @param userId 같은 방 멤버의 회원 ID(friendCode와 둘 중 하나)
	 */
	public record FriendRequestCreate(String friendCode, Long userId) {
	}

	/**
	 * 친구 요청에 보이는 상대 정보.
	 *
	 * @param userId 상대 회원 ID
	 * @param nickname 상대 닉네임
	 * @param friendCode 상대 친구 코드
	 */
	public record FriendUser(Long userId, String nickname, String friendCode) {
	}

	/**
	 * 친구 요청 하나. direction은 SENT·RECEIVED다.
	 *
	 * @param id 친구 요청 ID
	 * @param direction SENT 또는 RECEIVED
	 * @param user 요청 상대
	 * @param status PENDING 또는 ACCEPTED
	 * @param createdAt 요청한 시각
	 */
	public record FriendRequestResponse(Long id, String direction, FriendUser user, String status,
			OffsetDateTime createdAt) {
	}

	/**
	 * 친구 요청 목록.
	 *
	 * @param requests 친구 요청(최신순)
	 */
	public record FriendRequestList(List<FriendRequestResponse> requests) {
	}

	/**
	 * character는 친구에게 쓸 수 있는 그림이 있으면 { thumbnailUrl }, 없으면 null(친구의 status는 보내지 않는다).
	 *
	 * @param userId 친구 회원 ID
	 * @param nickname 친구 닉네임
	 * @param friendCode 친구 코드
	 * @param friendsSince 친구가 된 시각
	 * @param character 친구 프로필 캐릭터 그림(없으면 null)
	 */
	public record FriendItem(Long userId, String nickname, String friendCode, OffsetDateTime friendsSince,
			FriendCharacter character) {
	}

	/**
	 * 내 친구 목록.
	 *
	 * @param friends 친구(최근에 친구가 된 사람이 위)
	 */
	public record FriendList(List<FriendItem> friends) {
	}

	/**
	 * hideInRooms는 같은 방에서 그 사람 글을 숨길지. 없으면 false(초안).
	 *
	 * @param userId 차단할 회원 ID
	 * @param hideInRooms 같은 방에서 그 사람 글을 숨길지(null이면 false)
	 */
	public record BlockCreate(@NotNull Long userId, Boolean hideInRooms) {
	}

	/**
	 * 차단한 사람의 방 글 숨기기 설정을 바꾸는 요청.
	 *
	 * @param hideInRooms 같은 방에서 그 사람 글을 숨길지
	 */
	public record BlockUpdate(@NotNull Boolean hideInRooms) {
	}

	/**
	 * 차단한 사람 하나.
	 *
	 * @param userId 차단한 회원 ID
	 * @param nickname 차단한 회원 닉네임
	 * @param friendCode 차단한 회원 친구 코드
	 * @param blockedAt 차단한 시각
	 * @param hideInRooms 같은 방에서 그 사람 글을 숨기는지
	 */
	public record BlockItem(Long userId, String nickname, String friendCode, OffsetDateTime blockedAt,
			boolean hideInRooms) {
	}

	/**
	 * 차단한 사람 목록.
	 *
	 * @param blocks 차단한 사람(최신순)
	 */
	public record BlockList(List<BlockItem> blocks) {
	}

}
