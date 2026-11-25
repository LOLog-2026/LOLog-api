package com.lolog.api.domain.blog.platform;

import com.lolog.api.domain.blog.service.FeedParser.Feed;
import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Component;

// 플랫폼 고르기(texts.md 4장 "플랫폼 고르기"). 구현은 @Order 순서이고 맨 마지막 OtherPlatform은 늘 맞는다.
@Component
public class BlogPlatforms {

	private final List<BlogPlatform> platforms;

	/** Spring이 @Order 순서로 넣어 준 플랫폼 구현을 받는다. */
	public BlogPlatforms(List<BlogPlatform> platforms) {
		this.platforms = List.copyOf(platforms);
	}

	/**
	 * 주소로 고른다. 맞는 것이 없으면 Other.
	 *
	 * @param siteUrl 사용자가 넣은 블로그 주소
	 * @return 주소에 맞는 첫 플랫폼 구현. 없으면 Other
	 */
	public BlogPlatform byUrl(URI siteUrl) {
		return platforms.stream().filter(p -> p.supports(siteUrl)).findFirst().orElseThrow();
	}

	/**
	 * 주소로 Other면 찾은 피드로 한 번 더 알아본다(자기 도메인을 붙인 블로그).
	 *
	 * @param byUrl 주소로 고른 플랫폼 구현
	 * @param feed 찾은 피드
	 * @return 최종 플랫폼 구현. 피드로도 모르면 byUrl 그대로
	 */
	public BlogPlatform resolve(BlogPlatform byUrl, Feed feed) {
		if (byUrl.platform() != Platform.OTHER) {
			return byUrl;
		}
		return platforms.stream()
			.filter(p -> p.platform() != Platform.OTHER && p.supportsFeed(feed))
			.findFirst()
			.orElse(byUrl);
	}

	/**
	 * 저장한 플랫폼 이름으로 구현을 찾는다.
	 *
	 * @param platform blog_sources에 저장한 플랫폼
	 * @return 그 플랫폼 구현
	 */
	public BlogPlatform get(Platform platform) {
		return platforms.stream().filter(p -> p.platform() == platform).findFirst().orElseThrow();
	}

}
