package com.lolog.api.domain.text.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

// 카드로 만들 글 하나(texts.md 7장). 직접 쓴 글이거나 가져온 블로그 글이다.
// 글 상태는 칸으로 두지 않고 카드·작업으로 채운다(TextRepository.STATUS_JPQL).
@Entity
@Table(name = "texts")
@Getter
@NoArgsConstructor
public class Text {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private TextSource source;

	// 블로그 글이면 가져온 글(blog_posts), 직접 쓴 글이면 null(ck_texts_blog_post).
	@Column(name = "blog_post_id")
	private Long blogPostId;

	@Column(nullable = false, length = 300)
	private String title;

	// 블로그 글은 카드가 생기면 지운다(texts.md 2장 "글 본문 남기기").
	@Column(columnDefinition = "text")
	private String body;

	@Column(length = 200)
	private String place;

	@Column(length = 500)
	private String situation;

	// 블로그 본문이 길이 상한을 넘어 앞부분만 넣었는지.
	@Column(nullable = false)
	private boolean truncated;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	// 직접 쓴 글을 만든다.
	public static Text written(Long userId, String title, String body, String place, String situation) {
		Text text = new Text();
		text.userId = userId;
		text.source = TextSource.WRITTEN;
		text.title = title;
		text.body = body;
		text.place = place;
		text.situation = situation;
		return text;
	}

	// 가져온 블로그 글로 글을 만든다.
	public static Text blog(Long userId, Long blogPostId, String title, String body, boolean truncated) {
		Text text = new Text();
		text.userId = userId;
		text.source = TextSource.BLOG;
		text.blogPostId = blogPostId;
		text.title = title;
		text.body = body;
		text.truncated = truncated;
		return text;
	}

	// 블로그 글인지 본다.
	public boolean isBlog() {
		return source == TextSource.BLOG;
	}

	// 본문을 지운다(블로그 글로 카드가 생긴 뒤).
	public void clearBody() {
		body = null;
	}

}
