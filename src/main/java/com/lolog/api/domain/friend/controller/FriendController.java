package com.lolog.api.domain.friend.controller;

import com.lolog.api.domain.friend.dto.FriendDtos.BlockCreate;
import com.lolog.api.domain.friend.dto.FriendDtos.BlockList;
import com.lolog.api.domain.friend.dto.FriendDtos.BlockUpdate;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendItem;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendList;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendRequestCreate;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendRequestList;
import com.lolog.api.domain.friend.dto.FriendDtos.FriendRequestResponse;
import com.lolog.api.domain.friend.dto.FriendDtos.ProfileResponse;
import com.lolog.api.domain.friend.dto.FriendDtos.ProfileUpdateRequest;
import com.lolog.api.domain.friend.service.FriendService;
import com.lolog.api.domain.friend.service.ProfileService;
import com.lolog.api.global.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 내 프로필, 친구 요청, 친구, 차단 API(friends.md 2장). 모두 로그인이 필요하다.
@RestController
public class FriendController {

	private final ProfileService profiles;
	private final FriendService friends;
	private final CurrentUserProvider currentUser;

	/** 프로필·친구 서비스와 현재 사용자 정보를 받는다. */
	public FriendController(ProfileService profiles, FriendService friends, CurrentUserProvider currentUser) {
		this.profiles = profiles;
		this.friends = friends;
		this.currentUser = currentUser;
	}

	// 내 프로필을 돌려준다.
	@GetMapping("/api/me/profile")
	public ProfileResponse profile() {
		return profiles.get(currentUser.currentUserId());
	}

	/**
	 * 내 닉네임을 바꾼다.
	 *
	 * @param request 바꿀 닉네임
	 * @return 바꾼 내 프로필
	 */
	@PatchMapping("/api/me/profile")
	public ProfileResponse updateProfile(@RequestBody ProfileUpdateRequest request) {
		return profiles.updateNickname(currentUser.currentUserId(), request.nickname());
	}

	/**
	 * 친구 요청을 보낸다.
	 *
	 * @param request 상대의 친구 코드나 같은 방 멤버의 회원 ID
	 * @return 보낸 요청(상대가 먼저 요청했으면 ACCEPTED)
	 */
	@PostMapping("/api/friend-requests")
	@ResponseStatus(HttpStatus.CREATED)
	public FriendRequestResponse sendRequest(@RequestBody FriendRequestCreate request) {
		return friends.sendRequest(currentUser.currentUserId(), request.friendCode(), request.userId());
	}

	/**
	 * direction은 필수지만 빠졌을 때도 INVALID_REQUEST(field: direction)로 답하려고 서비스에서 확인한다.
	 *
	 * @param direction received(받은 요청) 또는 sent(보낸 요청)
	 * @return 친구 요청 목록(최신순)
	 */
	@GetMapping("/api/friend-requests")
	public FriendRequestList requests(@RequestParam(required = false) String direction) {
		return friends.requests(currentUser.currentUserId(), direction);
	}

	/**
	 * 받은 친구 요청을 수락한다.
	 *
	 * @param id 수락할 받은 친구 요청 ID
	 * @return 새 친구
	 */
	@PostMapping("/api/friend-requests/{id}/accept")
	public FriendItem accept(@PathVariable Long id) {
		return friends.acceptRequest(currentUser.currentUserId(), id);
	}

	/**
	 * 친구 요청을 거절하거나 취소한다.
	 *
	 * @param id 거절하거나 취소할 친구 요청 ID
	 */
	@DeleteMapping("/api/friend-requests/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRequest(@PathVariable Long id) {
		friends.deleteRequest(currentUser.currentUserId(), id);
	}

	// 내 친구 목록을 돌려준다.
	@GetMapping("/api/friends")
	public FriendList friends() {
		return friends.friends(currentUser.currentUserId());
	}

	/**
	 * 친구를 끊는다.
	 *
	 * @param userId 친구를 끊을 회원 ID
	 */
	@DeleteMapping("/api/friends/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unfriend(@PathVariable Long userId) {
		friends.unfriend(currentUser.currentUserId(), userId);
	}

	/**
	 * 사람을 차단한다.
	 *
	 * @param request 차단할 회원 ID와 방 글 숨기기 여부
	 */
	@PostMapping("/api/blocks")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void block(@Valid @RequestBody BlockCreate request) {
		friends.block(currentUser.currentUserId(), request.userId(), Boolean.TRUE.equals(request.hideInRooms()));
	}

	/**
	 * 차단한 사람의 방 글 숨기기 설정을 바꾼다.
	 *
	 * @param userId 차단한 회원 ID
	 * @param request 방 글 숨기기 여부
	 */
	@PatchMapping("/api/blocks/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void updateBlock(@PathVariable Long userId, @Valid @RequestBody BlockUpdate request) {
		friends.updateBlock(currentUser.currentUserId(), userId, request.hideInRooms());
	}

	// 차단한 사람 목록을 돌려준다.
	@GetMapping("/api/blocks")
	public BlockList blocks() {
		return friends.blocks(currentUser.currentUserId());
	}

	/**
	 * 차단을 푼다.
	 *
	 * @param userId 차단을 풀 회원 ID
	 */
	@DeleteMapping("/api/blocks/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unblock(@PathVariable Long userId) {
		friends.unblock(currentUser.currentUserId(), userId);
	}

}
