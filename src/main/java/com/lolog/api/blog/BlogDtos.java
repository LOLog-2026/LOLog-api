package com.lolog.api.blog;

import com.lolog.api.text.TextStatus;
import java.time.OffsetDateTime;
import java.util.List;

// 블로그 API의 요청·응답 모양(texts.md 6장).
public final class BlogDtos {

	private BlogDtos() {
	}

	public record BlogCheckRequest(String url) {
	}

	public record BlogCheck(String feedUrl, List<String> categories) {
	}

	public record BlogCreateRequest(String url, List<String> categories) {
	}

	public record BlogUpdateRequest(List<String> categories) {
	}

	// 블로그 응답 모양(정함 11-12). title은 RSS channel의 title이다.
	public record Blog(Long id, String siteUrl, String title, List<String> categories) {
	}

	public record BlogList(List<Blog> blogs) {
	}

	// 카드를 지운 글은 cardId·status가 둘 다 null이다(texts.md 2장).
	public record BlogPost(String title, String link, String category, OffsetDateTime publishedAt, Long cardId,
			TextStatus status) {
	}

	public record BlogPostPage(List<BlogPost> posts, String nextCursor) {
	}

}
