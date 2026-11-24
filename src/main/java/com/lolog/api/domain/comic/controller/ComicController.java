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

	// 만화 서비스와 현재 사용자 정보를 받는다.
	public ComicController(ComicService comicService, ComicQueryService comicQueries, CurrentUserProvider currentUser) {
		this.comicService = comicService;
		this.comicQueries = comicQueries;
		this.currentUser = currentUser;
	}

	// 요청 본문 없음. 202 { comicId, status: QUEUED }
	@PostMapping("/api/cards/{id}/comics")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Created request(@PathVariable Long id) {
		return comicService.request(currentUser.currentUserId(), id);
	}

	// 내 만화 목록을 페이지로 돌려준다.
	@GetMapping("/api/comics")
	public ComicPage list(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size,
			@RequestParam(required = false) String sort) {
		return comicQueries.list(currentUser.currentUserId(), cursor, size, sort);
	}

	// 내 만화 하나를 돌려준다.
	@GetMapping("/api/comics/{id}")
	public ComicResponse detail(@PathVariable Long id) {
		return comicQueries.detail(currentUser.currentUserId(), id);
	}

	// 내 만화 하나를 지운다.
	@DeleteMapping("/api/comics/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		comicQueries.delete(currentUser.currentUserId(), id);
	}

}
