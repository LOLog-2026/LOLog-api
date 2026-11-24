package com.lolog.api.domain.text.repository;

import com.lolog.api.domain.text.entity.TextSource;
import java.time.OffsetDateTime;

// 글 하나(읽기 전용). 첫 카드와 상태, 블로그 글이면 원문 링크와 블로그 이름을 함께 읽는다.
public record TextView(long id, TextSource source, String title, String body, String place, String situation,
		boolean truncated, OffsetDateTime createdAt, Long cardId, String status, String link, String blogTitle) {
}
