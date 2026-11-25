package com.lolog.api.domain.comic.controller;

import com.lolog.api.domain.comic.dto.ComicDtos.ComicPage;
import com.lolog.api.domain.comic.dto.ComicDtos.ComicResponse;
import com.lolog.api.domain.comic.service.ComicQueryService;
import com.lolog.api.domain.comic.service.ComicService.Created;
import com.lolog.api.domain.comic.service.ComicService;
import com.lolog.api.global.auth.CurrentUserProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 앱의 만화 요청·목록·자세히·지우기 API.
@RestController
public class ComicController {

	private final ComicService comicService;
	private final ComicQueryService comicQueries;
	private final CurrentUserProvider currentUser;

	/** 만화 서비스와 현재 사용자 정보를 받는다. */
	public ComicController(ComicService comicService, ComicQueryService comicQueries, CurrentUserProvider currentUser) {
		this.comicService = comicService;
		this.comicQueries = comicQueries;
		this.currentUser = currentUser;
	}

	/**
	 * 요청 본문 없음. 202 { comicId, status: QUEUED }
	 *
	 * @param id 경로의 카드 ID(만화로 만들 내 카드)
	 * @return 만든 만화 ID와 상태(QUEUED)
	 */
	@PostMapping("/api/cards/{id}/comics")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Created request(@PathVariable Long id) {
		return comicService.request(currentUser.currentUserId(), id);
	}

	/**
	 * 내 만화 목록을 페이지로 돌려준다.
	 *
	 * @param cursor 앞 페이지 응답의 nextCursor(없으면 첫 페이지)
	 * @param size 페이지 크기(1~50, 없으면 20)
	 * @param sort 정렬(latest 또는 oldest, 없으면 latest)
	 * @return 만화 목록 한 페이지
	 */
	@GetMapping("/api/comics")
	public ComicPage list(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size,
			@RequestParam(required = false) String sort) {
		return comicQueries.list(currentUser.currentUserId(), cursor, size, sort);
	}

	/**
	 * 내 만화 하나를 돌려준다.
	 *
	 * @param id 경로의 만화 ID
	 * @return 만화 하나
	 */
	@GetMapping("/api/comics/{id}")
	public ComicResponse detail(@PathVariable Long id) {
		return comicQueries.detail(currentUser.currentUserId(), id);
	}

	/**
	 * 내 만화 하나를 지운다.
	 *
	 * @param id 경로의 만화 ID
	 */
	@DeleteMapping("/api/comics/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		comicQueries.delete(currentUser.currentUserId(), id);
	}

}
