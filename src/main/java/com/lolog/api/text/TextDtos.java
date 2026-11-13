package com.lolog.api.text;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;

// 글 API의 요청·응답과 워커 결과 모양(texts.md 5·6장).
public final class TextDtos {

	private TextDtos() {
	}

	// POST /api/texts. 빈 값·길이는 서비스에서 본다(본문이 길면 400 TEXT_TOO_LONG이라 어노테이션으로 막지 않는다).
	public record TextCreateRequest(String title, String body, String place, String situation) {
	}

	public record TextAccepted(Long textId, TextStatus status) {
	}

	// GET /api/texts/{id}. 블로그 글이면 body 대신 link·blogTitle이 온다.
	public record TextResponse(Long textId, String source, TextStatus status, Long cardId, String title, String body,
			String place, String situation, String link, String blogTitle, boolean truncated, OffsetDateTime createdAt) {
	}

	// POST /internal/texts/{id}/cards. 카드 한 장이고 모양은 녹음 카드(DetectResult)와 같다. 시간 값은 null이다.
	public record TextResult(@NotNull Long jobId, @NotNull @Size(min = 1, max = 1) List<@Valid @NotNull TextCard> cards) {
	}

	public record TextCard(
			@NotNull @Pattern(regexp = "^(웃김|황당|추억)$") String kind,
			@NotBlank @Size(max = 30) String summary,
			Long startMs,
			Long endMs,
			@NotNull @Size(min = 1, max = 4) List<@NotNull String> speakers,
			@NotNull @Size(min = 1) List<@Valid @NotNull TextLine> lines) {
	}

	public record TextLine(@NotNull @Min(1) Integer lineNo, @NotNull String speaker, @NotBlank String text, Long startMs,
			Long endMs) {
	}

}
