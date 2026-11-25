package com.lolog.api.domain.video.controller;

import com.lolog.api.domain.video.dto.VideoDtos.VideoAccepted;
import com.lolog.api.domain.video.dto.VideoDtos.VideoPage;
import com.lolog.api.domain.video.dto.VideoDtos.VideoResponse;
import com.lolog.api.domain.video.service.VideoService;
import com.lolog.api.global.auth.CurrentUserProvider;
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

	/** 영상 서비스와 현재 사용자 정보를 받는다. */
	public VideoController(VideoService videos, CurrentUserProvider currentUser) {
		this.videos = videos;
		this.currentUser = currentUser;
	}

	/**
	 * 카드로 영상 만들기를 요청한다.
	 *
	 * @param id 영상으로 만들 내 카드 ID(경로)
	 * @return 만든 영상 ID와 상태 QUEUED(202)
	 */
	@PostMapping("/api/cards/{id}/videos")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public VideoAccepted request(@PathVariable Long id) {
		return videos.request(currentUser.currentUserId(), id);
	}

	/**
	 * 내 영상 목록을 페이지로 돌려준다.
	 *
	 * @param cursor 앞 페이지의 nextCursor(쿼리, 없으면 처음부터)
	 * @param size 페이지 크기(쿼리, 기본 20, 1~50)
	 * @param sort 정렬(쿼리, latest 기본 또는 oldest)
	 * @return 영상 목록과 다음 cursor
	 */
	@GetMapping("/api/videos")
	public VideoPage list(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size,
			@RequestParam(required = false) String sort) {
		return videos.list(currentUser.currentUserId(), cursor, size, sort);
	}

	/**
	 * 내 영상 하나를 돌려준다.
	 *
	 * @param id 영상 ID(경로)
	 * @return 영상 상태와 파일 주소
	 */
	@GetMapping("/api/videos/{id}")
	public VideoResponse get(@PathVariable Long id) {
		return videos.get(currentUser.currentUserId(), id);
	}

	/**
	 * 내 영상을 지운다.
	 *
	 * @param id 지울 영상 ID(경로)
	 */
	@DeleteMapping("/api/videos/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		videos.delete(currentUser.currentUserId(), id);
	}

}
