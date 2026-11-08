package com.lolog.api.comic;

import com.lolog.api.auth.CurrentUserProvider;
import com.lolog.api.comic.ComicService.Created;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ComicController {

	private final ComicService comicService;
	private final CurrentUserProvider currentUser;

	public ComicController(ComicService comicService, CurrentUserProvider currentUser) {
		this.comicService = comicService;
		this.currentUser = currentUser;
	}

	// 요청 본문 없음. 202 { comicId, status: QUEUED }
	@PostMapping("/api/cards/{id}/comics")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public Created request(@PathVariable Long id) {
		return comicService.request(currentUser.currentUserId(), id);
	}

}
