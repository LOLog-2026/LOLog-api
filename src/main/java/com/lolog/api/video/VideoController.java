package com.lolog.api.video;

import com.lolog.api.auth.CurrentUserProvider;
import com.lolog.api.video.VideoDtos.VideoAccepted;
import com.lolog.api.video.VideoDtos.VideoPage;
import com.lolog.api.video.VideoDtos.VideoResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 카드 영상 API(video.md 2장). 로그인이 필요하고 내 것만 보인다.
@RestController
public class VideoController {

	private final VideoService videos;
	private final CurrentUserProvider currentUser;

	public VideoController(VideoService videos, CurrentUserProvider currentUser) {
		this.videos = videos;
		this.currentUser = currentUser;
	}

	@PostMapping("/api/cards/{id}/videos")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public VideoAccepted request(@PathVariable Long id) {
		return videos.request(currentUser.currentUserId(), id);
	}

	@GetMapping("/api/videos")
	public VideoPage list(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size,
			@RequestParam(required = false) String sort) {
		return videos.list(currentUser.currentUserId(), cursor, size, sort);
	}

	@GetMapping("/api/videos/{id}")
	public VideoResponse get(@PathVariable Long id) {
		return videos.get(currentUser.currentUserId(), id);
	}

	@DeleteMapping("/api/videos/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		videos.delete(currentUser.currentUserId(), id);
	}

}
