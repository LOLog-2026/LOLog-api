package com.lolog.api.domain.blog.repository;

import java.time.OffsetDateTime;

// 가져온 글 목록 한 줄(읽기 전용). 그 글로 만든 글(texts)의 첫 카드와 상태를 함께 읽는다.
// 글을 만들지 않은 블로그 글이면 textId가 null이고, 그때 status는 쓰지 않는다.
public record BlogPostView(long id, String title, String link, String category, OffsetDateTime publishedAt,
		Long textId, Long cardId, String status) {
}
