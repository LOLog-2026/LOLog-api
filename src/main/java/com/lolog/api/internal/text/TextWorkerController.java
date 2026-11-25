package com.lolog.api.internal.text;

import com.lolog.api.domain.text.dto.TextDtos.TextResult;
import com.lolog.api.domain.text.service.TextService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 워커가 글 결과(카드 1장)를 보내는 주소(texts.md 5장). 실패는 /internal/jobs/{id}/fail이다.
@RestController
public class TextWorkerController {

	private final TextService texts;

	/** 쓰는 서비스를 받는다. */
	public TextWorkerController(TextService texts) {
		this.texts = texts;
	}

	/**
	 * 글 결과 카드를 받는다.
	 *
	 * @param id 글 ID(경로)
	 * @param result 워커가 만든 카드 1장과 작업 ID
	 */
	@PostMapping("/internal/texts/{id}/cards")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cards(@PathVariable Long id, @Valid @RequestBody TextResult result) {
		texts.receive(id, result);
	}

}
