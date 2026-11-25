package com.lolog.api.domain.blog.dto;

import com.lolog.api.domain.text.dto.TextStatus;
import java.time.OffsetDateTime;
import java.util.List;

// 블로그 API의 요청·응답 모양(texts.md 6장).
public final class BlogDtos {

	private BlogDtos() {
	}

	/**
	 * 링크 확인 요청.
	 *
	 * @param url 사용자가 넣은 블로그 주소
	 */
	public record BlogCheckRequest(String url) {
	}

	/**
	 * optimized: 따로 맞춘 플랫폼(5개)이면 true, Other면 false(정함 11-14). 플랫폼 이름은 넣지 않는다.
	 *
	 * @param feedUrl 찾은 RSS 주소
	 * @param categories 고를 수 있는 카테고리 이름(1~30자). 고를 수 없는 플랫폼이면 빈 목록
	 * @param optimized 따로 맞춘 플랫폼이면 true
	 */
	public record BlogCheck(String feedUrl, List<String> categories, boolean optimized) {
	}

	/**
	 * 블로그 연결 요청.
	 *
	 * @param url 연결할 블로그 주소
	 * @param categories 고른 카테고리(3개까지). null이거나 비면 전부 받기
	 */
	public record BlogCreateRequest(String url, List<String> categories) {
	}

	/**
	 * 카테고리 바꾸기 요청.
	 *
	 * @param categories 새로 고른 카테고리 전체(3개까지). 비면 전부 받기, null이면 INVALID_REQUEST
	 */
	public record BlogUpdateRequest(List<String> categories) {
	}

	/**
	 * 블로그 응답 모양(정함 11-12, optimized는 11-14). title은 RSS channel의 title이다.
	 *
	 * @param id 블로그 ID
	 * @param siteUrl 정리한 블로그 주소(https 붙임)
	 * @param title 블로그 이름(RSS channel의 title). 없으면 null
	 * @param categories 고른 카테고리. 비면 전부 받기
	 * @param optimized 따로 맞춘 플랫폼이면 true
	 */
	public record Blog(Long id, String siteUrl, String title, List<String> categories, boolean optimized) {
	}

	/**
	 * 내 블로그 목록 응답.
	 *
	 * @param blogs 연결한 순서대로 내 블로그
	 */
	public record BlogList(List<Blog> blogs) {
	}

	/**
	 * 카드를 지운 글은 cardId·status가 둘 다 null이다(texts.md 2장).
	 *
	 * @param title 글 제목
	 * @param link 글 주소
	 * @param category 글의 카테고리. 없으면 null
	 * @param publishedAt 글을 쓴 시각(pubDate). 없으면 null
	 * @param cardId 이 글로 만든 첫 카드 ID. 없으면 null
	 * @param status 이 글로 만든 글(texts)의 상태. 없으면 null
	 */
	public record BlogPost(String title, String link, String category, OffsetDateTime publishedAt, Long cardId,
			TextStatus status) {
	}

	/**
	 * 가져온 글 목록 한 페이지.
	 *
	 * @param posts 최신순 가져온 글
	 * @param nextCursor 다음 페이지를 받을 cursor. 마지막 페이지면 null
	 */
	public record BlogPostPage(List<BlogPost> posts, String nextCursor) {
	}

}
