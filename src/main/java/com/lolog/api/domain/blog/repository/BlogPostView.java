package com.lolog.api.domain.blog.repository;

import java.time.OffsetDateTime;

/**
 * 가져온 글 목록 한 줄(읽기 전용). 그 글로 만든 글(texts)의 첫 카드와 상태를 함께 읽는다.
 * 글을 만들지 않은 블로그 글이면 textId가 null이고, 그때 status는 쓰지 않는다.
 *
 * @param id 가져온 글(blog_posts) ID
 * @param title 글 제목
 * @param link 글 주소
 * @param category 글의 카테고리. 없으면 null
 * @param publishedAt 글을 쓴 시각. 없으면 null
 * @param textId 이 글로 만든 글(texts) ID. 없으면 null
 * @param cardId 그 글의 첫 카드 ID. 없으면 null
 * @param status 그 글의 상태 이름(TextStatus). textId가 null이면 쓰지 않음
 */
public record BlogPostView(long id, String title, String link, String category, OffsetDateTime publishedAt,
		Long textId, Long cardId, String status) {
}
