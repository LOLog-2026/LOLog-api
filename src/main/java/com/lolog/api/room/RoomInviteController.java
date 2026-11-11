package com.lolog.api.room;

import com.lolog.api.auth.CurrentUserProvider;
import com.lolog.api.room.RoomDtos.InviteList;
import com.lolog.api.room.RoomDtos.RoomItem;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 내가 받은 방 초대(rooms.md 2장, 초안). 내 초대가 아니거나 이미 답했으면 404 ROOM_INVITE_NOT_FOUND.
@RestController
@RequestMapping("/api/room-invites")
public class RoomInviteController {

	private final RoomService rooms;
	private final CurrentUserProvider currentUser;

	public RoomInviteController(RoomService rooms, CurrentUserProvider currentUser) {
		this.rooms = rooms;
		this.currentUser = currentUser;
	}

	@GetMapping
	public InviteList list() {
		return rooms.receivedInvites(currentUser.currentUserId());
	}

	@PostMapping("/{inviteId}/accept")
	public RoomItem accept(@PathVariable Long inviteId) {
		return rooms.acceptInvite(currentUser.currentUserId(), inviteId);
	}

	@DeleteMapping("/{inviteId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void decline(@PathVariable Long inviteId) {
		rooms.declineInvite(currentUser.currentUserId(), inviteId);
	}

}
