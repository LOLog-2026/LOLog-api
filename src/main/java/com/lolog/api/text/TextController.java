package com.lolog.api.text;

import com.lolog.api.auth.CurrentUserProvider;
import com.lolog.api.text.TextDtos.TextAccepted;
import com.lolog.api.text.TextDtos.TextCreateRequest;
import com.lolog.api.text.TextDtos.TextResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 직접 쓴 글 API(texts.md 6장). 로그인이 필요하고 내 글만 보인다.
@RestController
public class TextController {

	private final TextService texts;
	private final CurrentUserProvider currentUser;

	public TextController(TextService texts, CurrentUserProvider currentUser) {
		this.texts = texts;
		this.currentUser = currentUser;
	}

	@PostMapping("/api/texts")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public TextAccepted create(@RequestBody TextCreateRequest request) {
		return texts.create(currentUser.currentUserId(), request);
	}

	@GetMapping("/api/texts/{id}")
	public TextResponse get(@PathVariable Long id) {
		return texts.get(currentUser.currentUserId(), id);
	}

}
