package com.lolog.api.domain.room.controller;

import com.lolog.api.domain.room.dto.RoomDtos.InviteList;
import com.lolog.api.domain.room.dto.RoomDtos.RoomItem;
import com.lolog.api.domain.room.service.RoomService;
import com.lolog.api.global.auth.CurrentUserProvider;
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

	// 내가 받은 초대 목록을 돌려준다.
	@GetMapping
	public InviteList list() {
		return rooms.receivedInvites(currentUser.currentUserId());
	}

	// 초대를 수락하고 방에 들어간다.
	@PostMapping("/{inviteId}/accept")
	public RoomItem accept(@PathVariable Long inviteId) {
		return rooms.acceptInvite(currentUser.currentUserId(), inviteId);
	}

	// 초대를 거절한다.
	@DeleteMapping("/{inviteId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void decline(@PathVariable Long inviteId) {
		rooms.declineInvite(currentUser.currentUserId(), inviteId);
	}

}
