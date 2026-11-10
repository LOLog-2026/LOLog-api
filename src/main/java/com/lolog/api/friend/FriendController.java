package com.lolog.api.friend;

import com.lolog.api.auth.CurrentUserProvider;
import com.lolog.api.friend.FriendDtos.BlockCreate;
import com.lolog.api.friend.FriendDtos.BlockList;
import com.lolog.api.friend.FriendDtos.FriendItem;
import com.lolog.api.friend.FriendDtos.FriendList;
import com.lolog.api.friend.FriendDtos.FriendRequestCreate;
import com.lolog.api.friend.FriendDtos.FriendRequestList;
import com.lolog.api.friend.FriendDtos.FriendRequestResponse;
import com.lolog.api.friend.FriendDtos.ProfileResponse;
import com.lolog.api.friend.FriendDtos.ProfileUpdateRequest;
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

	public FriendController(ProfileService profiles, FriendService friends, CurrentUserProvider currentUser) {
		this.profiles = profiles;
		this.friends = friends;
		this.currentUser = currentUser;
	}

	@GetMapping("/api/me/profile")
	public ProfileResponse profile() {
		return profiles.get(currentUser.currentUserId());
	}

	@PatchMapping("/api/me/profile")
	public ProfileResponse updateProfile(@RequestBody ProfileUpdateRequest request) {
		return profiles.updateNickname(currentUser.currentUserId(), request.nickname());
	}

	@PostMapping("/api/friend-requests")
	@ResponseStatus(HttpStatus.CREATED)
	public FriendRequestResponse sendRequest(@RequestBody FriendRequestCreate request) {
		return friends.sendRequest(currentUser.currentUserId(), request.friendCode());
	}

	// direction은 필수지만 빠졌을 때도 INVALID_REQUEST(field: direction)로 답하려고 서비스에서 확인한다.
	@GetMapping("/api/friend-requests")
	public FriendRequestList requests(@RequestParam(required = false) String direction) {
		return friends.requests(currentUser.currentUserId(), direction);
	}

	@PostMapping("/api/friend-requests/{id}/accept")
	public FriendItem accept(@PathVariable Long id) {
		return friends.acceptRequest(currentUser.currentUserId(), id);
	}

	@DeleteMapping("/api/friend-requests/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteRequest(@PathVariable Long id) {
		friends.deleteRequest(currentUser.currentUserId(), id);
	}

	@GetMapping("/api/friends")
	public FriendList friends() {
		return friends.friends(currentUser.currentUserId());
	}

	@DeleteMapping("/api/friends/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unfriend(@PathVariable Long userId) {
		friends.unfriend(currentUser.currentUserId(), userId);
	}

	@PostMapping("/api/blocks")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void block(@Valid @RequestBody BlockCreate request) {
		friends.block(currentUser.currentUserId(), request.userId());
	}

	@GetMapping("/api/blocks")
	public BlockList blocks() {
		return friends.blocks(currentUser.currentUserId());
	}

	@DeleteMapping("/api/blocks/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unblock(@PathVariable Long userId) {
		friends.unblock(currentUser.currentUserId(), userId);
	}

}
