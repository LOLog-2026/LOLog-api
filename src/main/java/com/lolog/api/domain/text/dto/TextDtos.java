package com.lolog.api.domain.text.dto;

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

	/**
	 * POST /api/texts. 빈 값·길이는 서비스에서 본다(본문이 길면 400 TEXT_TOO_LONG이라 어노테이션으로 막지 않는다).
	 *
	 * @param title 글 제목(필수, 300자 이하)
	 * @param body 글 본문(필수, 2,000자 이하)
	 * @param place 장소(선택, 20자 이하)
	 * @param situation 상황(선택, 500자 이하)
	 */
	public record TextCreateRequest(String title, String body, String place, String situation) {
	}

	/**
	 * 글을 받았을 때 돌려주는 값.
	 *
	 * @param textId 저장한 글 ID
	 * @param status 글 상태(받을 때는 QUEUED)
	 */
	public record TextAccepted(Long textId, TextStatus status) {
	}

	/**
	 * GET /api/texts/{id}. 블로그 글이면 body 대신 link·blogTitle이 온다.
	 *
	 * @param textId 글 ID
	 * @param source 글 출처(WRITTEN 또는 BLOG)
	 * @param status 글 상태
	 * @param cardId 이 글로 만든 첫 카드 ID. 없으면 null
	 * @param title 글 제목
	 * @param body 글 본문. 블로그 글이면 null
	 * @param place 장소(null 가능)
	 * @param situation 상황(null 가능)
	 * @param link 블로그 원문 링크. 직접 쓴 글이면 null
	 * @param blogTitle 블로그 이름. 직접 쓴 글이면 null
	 * @param truncated 블로그 본문이 길어 앞부분만 넣었는지
	 * @param createdAt 글을 저장한 시각
	 */
	public record TextResponse(Long textId, String source, TextStatus status, Long cardId, String title, String body,
			String place, String situation, String link, String blogTitle, boolean truncated, OffsetDateTime createdAt) {
	}

	/**
	 * POST /internal/texts/{id}/cards. 카드 한 장이고 모양은 녹음 카드(DetectResult)와 같다. 시간 값은 null이다.
	 *
	 * @param jobId 워커가 처리한 글 작업 ID
	 * @param cards 만든 카드(꼭 한 장)
	 */
	public record TextResult(@NotNull Long jobId, @NotNull @Size(min = 1, max = 1) List<@Valid @NotNull TextCard> cards) {
	}

	/**
	 * 워커가 만든 카드 한 장.
	 *
	 * @param kind 카드 종류(웃김, 황당, 추억)
	 * @param summary 카드 요약(30자 이하)
	 * @param startMs 시작 시각(ms). 글 카드는 null
	 * @param endMs 끝 시각(ms). 글 카드는 null
	 * @param speakers 화자 이름 목록(1~4명, 화자1부터 차례로)
	 * @param lines 대사 목록(1줄 이상)
	 */
	public record TextCard(
			@NotNull @Pattern(regexp = "^(웃김|황당|추억)$") String kind,
			@NotBlank @Size(max = 30) String summary,
			Long startMs,
			Long endMs,
			@NotNull @Size(min = 1, max = 4) List<@NotNull String> speakers,
			@NotNull @Size(min = 1) List<@Valid @NotNull TextLine> lines) {
	}

	/**
	 * 카드의 대사 한 줄.
	 *
	 * @param lineNo 대사 번호(1부터 차례로)
	 * @param speaker 말한 화자(speakers 중 하나)
	 * @param text 대사 글
	 * @param startMs 시작 시각(ms). 글 카드는 null
	 * @param endMs 끝 시각(ms). 글 카드는 null
	 */
	public record TextLine(@NotNull @Min(1) Integer lineNo, @NotNull String speaker, @NotBlank String text, Long startMs,
			Long endMs) {
	}

}
