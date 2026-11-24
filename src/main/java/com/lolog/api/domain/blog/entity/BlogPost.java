package com.lolog.api.domain.blog.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 가져온 블로그 글 하나(texts.md 7장 blog_posts). 같은 글은 (user_id, guid)로 한 번만 가져온다.
// 카드를 지워도, 연결을 끊어도 남긴다. 넣기는 on conflict 문장이라 리포지토리의 native query로 만든다.
@Entity
@Table(name = "blog_posts")
@Getter
@NoArgsConstructor
public class BlogPost {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 연결을 끊으면 DB가 null로 바꾼다(ON DELETE SET NULL).
	@Column(name = "source_id")
	private Long sourceId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(nullable = false, length = 500)
	private String guid;

	// 옛 글로 적어만 둔 글(skipped)은 제목·링크가 없다(ck_blog_posts_skipped).
	@Column(length = 300)
	private String title;

	@Column(length = 500)
	private String link;

	@Column(length = 100)
	private String category;

	@Column(name = "published_at")
	private OffsetDateTime publishedAt;

	// 가져올 때 복사해 둔 블로그 이름. 연결을 끊어도 남는다.
	@Column(name = "blog_title", length = 200)
	private String blogTitle;

	// 연결·카테고리 추가 때 최근 3개에 들지 않아 guid만 적어 둔 옛 글.
	@Column(nullable = false)
	private boolean skipped;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	// 글 카드의 날짜로 쓰는 시각: 쓴 시각(pubDate), 없으면 가져온 시각(texts.md 2장).
	public OffsetDateTime writtenAt() {
		return publishedAt != null ? publishedAt : createdAt;
	}

}
