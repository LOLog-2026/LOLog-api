package com.lolog.api.domain.room.controller;

import com.lolog.api.domain.room.dto.RoomDtos.InviteCreateRequest;
import com.lolog.api.domain.room.dto.RoomDtos.InviteList;
import com.lolog.api.domain.room.dto.RoomDtos.MessageList;
import com.lolog.api.domain.room.dto.RoomDtos.PostCreateRequest;
import com.lolog.api.domain.room.dto.RoomDtos.PostList;
import com.lolog.api.domain.room.dto.RoomDtos.RoomCommentRequest;
import com.lolog.api.domain.room.dto.RoomDtos.RoomCreateRequest;
import com.lolog.api.domain.room.dto.RoomDtos.RoomItem;
import com.lolog.api.domain.room.dto.RoomDtos.RoomList;
import com.lolog.api.domain.room.dto.RoomDtos.RoomMessage;
import com.lolog.api.domain.room.dto.RoomDtos.RoomPost;
import com.lolog.api.domain.room.dto.RoomDtos.RoomUpdateRequest;
import com.lolog.api.domain.room.service.RoomService;
import com.lolog.api.global.auth.CurrentUserProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 친구와 같이 쓰는 방 API(rooms.md 2장, 초안). 모두 로그인이 필요하고, 멤버가 아니면 404 ROOM_NOT_FOUND.
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

	private final RoomService rooms;
	private final CurrentUserProvider currentUser;

	/** 쓰는 서비스와 현재 사용자 정보를 받는다. */
	public RoomController(RoomService rooms, CurrentUserProvider currentUser) {
		this.rooms = rooms;
		this.currentUser = currentUser;
	}

	// 내가 들어간 방 목록을 돌려준다.
	@GetMapping
	public RoomList list() {
		return rooms.rooms(currentUser.currentUserId());
	}

	/**
	 * 방을 만들고 친구를 초대한다.
	 *
	 * @param request 방 이름과 초대할 친구 회원 ID
	 * @return 만든 방
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public RoomItem create(@RequestBody RoomCreateRequest request) {
		return rooms.create(currentUser.currentUserId(), request.name(), request.memberIds());
	}

	/**
	 * 방 하나를 돌려준다.
	 *
	 * @param id 방 ID
	 * @return 방
	 */
	@GetMapping("/{id}")
	public RoomItem room(@PathVariable Long id) {
		return rooms.room(currentUser.currentUserId(), id);
	}

	/**
	 * 방 이름을 바꾼다(방장).
	 *
	 * @param id 방 ID
	 * @param request 바꿀 방 이름
	 * @return 바꾼 방
	 */
	@PatchMapping("/{id}")
	public RoomItem rename(@PathVariable Long id, @RequestBody RoomUpdateRequest request) {
		return rooms.rename(currentUser.currentUserId(), id, request.name());
	}

	/**
	 * 방을 해산한다(방장).
	 *
	 * @param id 해산할 방 ID
	 */
	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void disband(@PathVariable Long id) {
		rooms.disband(currentUser.currentUserId(), id);
	}

	/**
	 * 방장은 다른 멤버가 있으면 newOwnerId를 꼭 보낸다(초안).
	 *
	 * @param id 나갈 방 ID
	 * @param newOwnerId 새 방장 회원 ID(쿼리, 방장이 아니거나 혼자면 생략)
	 */
	@DeleteMapping("/{id}/members/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void leave(@PathVariable Long id, @RequestParam(required = false) Long newOwnerId) {
		rooms.leave(currentUser.currentUserId(), id, newOwnerId);
	}

	/**
	 * 멤버를 내보낸다(방장).
	 *
	 * @param id 방 ID
	 * @param userId 내보낼 멤버 회원 ID
	 */
	@DeleteMapping("/{id}/members/{userId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void kick(@PathVariable Long id, @PathVariable Long userId) {
		rooms.kick(currentUser.currentUserId(), id, userId);
	}

	/**
	 * 친구를 방에 초대한다(방장).
	 *
	 * @param id 방 ID
	 * @param request 초대할 친구 회원 ID
	 * @return 요청한 친구들의 초대
	 */
	@PostMapping("/{id}/invites")
	@ResponseStatus(HttpStatus.CREATED)
	public InviteList invite(@PathVariable Long id, @RequestBody InviteCreateRequest request) {
		return rooms.invite(currentUser.currentUserId(), id, request.userIds());
	}

	/**
	 * 답을 기다리는 초대를 취소한다(방장).
	 *
	 * @param id 방 ID
	 * @param inviteId 취소할 초대 ID
	 */
	@DeleteMapping("/{id}/invites/{inviteId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cancelInvite(@PathVariable Long id, @PathVariable Long inviteId) {
		rooms.cancelInvite(currentUser.currentUserId(), id, inviteId);
	}

	/**
	 * 방에 올린 만화 목록을 돌려준다.
	 *
	 * @param id 방 ID
	 * @return 올린 만화 목록
	 */
	@GetMapping("/{id}/posts")
	public PostList posts(@PathVariable Long id) {
		return rooms.posts(currentUser.currentUserId(), id);
	}

	/**
	 * 내 만화를 방에 올린다.
	 *
	 * @param id 방 ID
	 * @param request 올릴 내 만화 ID
	 * @return 올린 만화
	 */
	@PostMapping("/{id}/posts")
	@ResponseStatus(HttpStatus.CREATED)
	public RoomPost post(@PathVariable Long id, @RequestBody PostCreateRequest request) {
		return rooms.postComic(currentUser.currentUserId(), id, request.comicId());
	}

	/**
	 * 내가 올린 만화를 내린다.
	 *
	 * @param id 방 ID
	 * @param postId 내릴 올린 만화 ID
	 */
	@DeleteMapping("/{id}/posts/{postId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removePost(@PathVariable Long id, @PathVariable Long postId) {
		rooms.removePost(currentUser.currentUserId(), id, postId);
	}

	/**
	 * 만화에 좋아요를 누른다.
	 *
	 * @param id 방 ID
	 * @param postId 올린 만화 ID
	 * @return 좋아요를 반영한 올린 만화
	 */
	@PutMapping("/{id}/posts/{postId}/like")
	public RoomPost like(@PathVariable Long id, @PathVariable Long postId) {
		return rooms.like(currentUser.currentUserId(), id, postId);
	}

	/**
	 * 좋아요를 취소한다.
	 *
	 * @param id 방 ID
	 * @param postId 올린 만화 ID
	 * @return 좋아요를 반영한 올린 만화
	 */
	@DeleteMapping("/{id}/posts/{postId}/like")
	public RoomPost unlike(@PathVariable Long id, @PathVariable Long postId) {
		return rooms.unlike(currentUser.currentUserId(), id, postId);
	}

	/**
	 * 방 대화를 페이지로 돌려준다.
	 *
	 * @param id 방 ID
	 * @param cursor 받은 것 중 가장 오래된 줄 ID(없으면 최근 50줄)
	 * @return 대화 한 쪽
	 */
	@GetMapping("/{id}/messages")
	public MessageList messages(@PathVariable Long id, @RequestParam(required = false) String cursor) {
		return rooms.messages(currentUser.currentUserId(), id, cursor);
	}

	/**
	 * 방 대화에 글을 남긴다.
	 *
	 * @param id 방 ID
	 * @param request 글 내용과 댓글을 달 올린 만화 ID
	 * @return 남긴 대화 줄
	 */
	@PostMapping("/{id}/messages")
	@ResponseStatus(HttpStatus.CREATED)
	public RoomMessage comment(@PathVariable Long id, @RequestBody RoomCommentRequest request) {
		return rooms.comment(currentUser.currentUserId(), id, request.text(), request.postId());
	}

}
