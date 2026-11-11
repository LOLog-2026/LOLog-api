package com.lolog.api.room;

import com.lolog.api.auth.CurrentUserProvider;
import com.lolog.api.room.RoomDtos.MessageList;
import com.lolog.api.room.RoomDtos.PostCreateRequest;
import com.lolog.api.room.RoomDtos.PostList;
import com.lolog.api.room.RoomDtos.RoomCommentRequest;
import com.lolog.api.room.RoomDtos.RoomCreateRequest;
import com.lolog.api.room.RoomDtos.RoomItem;
import com.lolog.api.room.RoomDtos.RoomList;
import com.lolog.api.room.RoomDtos.RoomMessage;
import com.lolog.api.room.RoomDtos.RoomPost;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 친구와 같이 쓰는 방 API(rooms.md 2장, 초안). 모두 로그인이 필요하고, 멤버가 아니면 404 ROOM_NOT_FOUND.
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

	private final RoomService rooms;
	private final CurrentUserProvider currentUser;

	public RoomController(RoomService rooms, CurrentUserProvider currentUser) {
		this.rooms = rooms;
		this.currentUser = currentUser;
	}

	@GetMapping
	public RoomList list() {
		return rooms.rooms(currentUser.currentUserId());
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public RoomItem create(@RequestBody RoomCreateRequest request) {
		return rooms.create(currentUser.currentUserId(), request.name(), request.memberIds());
	}

	@GetMapping("/{id}")
	public RoomItem room(@PathVariable Long id) {
		return rooms.room(currentUser.currentUserId(), id);
	}

	@DeleteMapping("/{id}/members/me")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void leave(@PathVariable Long id) {
		rooms.leave(currentUser.currentUserId(), id);
	}

	@GetMapping("/{id}/posts")
	public PostList posts(@PathVariable Long id) {
		return rooms.posts(currentUser.currentUserId(), id);
	}

	@PostMapping("/{id}/posts")
	@ResponseStatus(HttpStatus.CREATED)
	public RoomPost post(@PathVariable Long id, @RequestBody PostCreateRequest request) {
		return rooms.postComic(currentUser.currentUserId(), id, request.comicId());
	}

	@DeleteMapping("/{id}/posts/{postId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removePost(@PathVariable Long id, @PathVariable Long postId) {
		rooms.removePost(currentUser.currentUserId(), id, postId);
	}

	@PutMapping("/{id}/posts/{postId}/like")
	public RoomPost like(@PathVariable Long id, @PathVariable Long postId) {
		return rooms.like(currentUser.currentUserId(), id, postId);
	}

	@DeleteMapping("/{id}/posts/{postId}/like")
	public RoomPost unlike(@PathVariable Long id, @PathVariable Long postId) {
		return rooms.unlike(currentUser.currentUserId(), id, postId);
	}

	@GetMapping("/{id}/messages")
	public MessageList messages(@PathVariable Long id) {
		return rooms.messages(currentUser.currentUserId(), id);
	}

	@PostMapping("/{id}/messages")
	@ResponseStatus(HttpStatus.CREATED)
	public RoomMessage comment(@PathVariable Long id, @RequestBody RoomCommentRequest request) {
		return rooms.comment(currentUser.currentUserId(), id, request.text(), request.postId());
	}

}
