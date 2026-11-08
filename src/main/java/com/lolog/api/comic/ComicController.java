package com.lolog.api.comic;

import com.lolog.api.auth.CurrentUserProvider;
import com.lolog.api.comic.ComicDtos.ComicPage;
import com.lolog.api.comic.ComicDtos.ComicResponse;
import com.lolog.api.comic.ComicService.Created;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ComicController {

	private final ComicService comicService;
	private final ComicQueryService comicQueries;
	private final CurrentUserProvider currentUser;

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

	@GetMapping("/api/comics")
	public ComicPage list(@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		return comicQueries.list(currentUser.currentUserId(), cursor, size);
	}

	@GetMapping("/api/comics/{id}")
	public ComicResponse detail(@PathVariable Long id) {
		return comicQueries.detail(currentUser.currentUserId(), id);
	}

	@DeleteMapping("/api/comics/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		comicQueries.delete(currentUser.currentUserId(), id);
	}

}
