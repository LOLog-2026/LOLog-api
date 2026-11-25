package com.lolog.api.domain.blog.entity;

import com.lolog.api.domain.blog.platform.Platform;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 연결한 블로그 하나(texts.md 7장 blog_sources). 한 사용자가 같은 피드를 두 번 연결하지 못한다(uk_blog_sources_user_id_feed_url).
@Entity
@Table(name = "blog_sources")
@Getter
@NoArgsConstructor
public class BlogSource {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "site_url", nullable = false, length = 500)
	private String siteUrl;

	@Column(length = 200)
	private String title;

	@Column(name = "feed_url", nullable = false, length = 500)
	private String feedUrl;

	// 연결할 때 한 번 정한다.
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private Platform platform;

	// 고른 카테고리의 JSON 배열. 비면 전부 받는다. 읽고 쓰는 것은 BlogService가 한다.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String categories;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	/**
	 * 새로 연결한 블로그를 만든다.
	 *
	 * @param userId 연결한 회원 ID
	 * @param siteUrl 정리한 블로그 주소
	 * @param title 블로그 이름. 없으면 null
	 * @param feedUrl 찾은 RSS 주소
	 * @param platform 연결할 때 고른 플랫폼
	 * @param categories 고른 카테고리의 JSON 배열. 비면 전부 받기
	 */
	public BlogSource(Long userId, String siteUrl, String title, String feedUrl, Platform platform, String categories) {
		this.userId = userId;
		this.siteUrl = siteUrl;
		this.title = title;
		this.feedUrl = feedUrl;
		this.platform = platform;
		this.categories = categories;
	}

	/**
	 * 가져올 카테고리를 바꾼다.
	 *
	 * @param categories 새로 고른 카테고리의 JSON 배열. 비면 전부 받기
	 */
	public void changeCategories(String categories) {
		this.categories = categories;
	}

}
