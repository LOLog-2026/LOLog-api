package com.lolog.api.domain.text.controller;

import com.lolog.api.domain.text.dto.TextDtos.TextAccepted;
import com.lolog.api.domain.text.dto.TextDtos.TextCreateRequest;
import com.lolog.api.domain.text.dto.TextDtos.TextResponse;
import com.lolog.api.domain.text.service.TextService;
import com.lolog.api.global.auth.CurrentUserProvider;
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

	/** 글 서비스와 현재 사용자 정보를 받는다. */
	public TextController(TextService texts, CurrentUserProvider currentUser) {
		this.texts = texts;
		this.currentUser = currentUser;
	}

	/**
	 * 글을 받아 카드 만들기 작업을 맡긴다.
	 *
	 * @param request 올릴 글(본문)
	 * @return 저장한 글 ID와 상태 QUEUED(202)
	 */
	@PostMapping("/api/texts")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public TextAccepted create(@RequestBody TextCreateRequest request) {
		return texts.create(currentUser.currentUserId(), request);
	}

	/**
	 * 내 글 하나를 돌려준다.
	 *
	 * @param id 글 ID(경로)
	 * @return 글 내용과 상태
	 */
	@GetMapping("/api/texts/{id}")
	public TextResponse get(@PathVariable Long id) {
		return texts.get(currentUser.currentUserId(), id);
	}

}
