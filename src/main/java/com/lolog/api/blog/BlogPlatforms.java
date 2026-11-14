package com.lolog.api.blog;

import com.lolog.api.blog.FeedParser.Feed;
import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Component;

// 플랫폼 고르기(texts.md 4장 "플랫폼 고르기"). 구현은 @Order 순서이고 맨 마지막 OtherPlatform은 늘 맞는다.
@Component
public class BlogPlatforms {

	private final List<BlogPlatform> platforms;

	public BlogPlatforms(List<BlogPlatform> platforms) {
		this.platforms = List.copyOf(platforms);
	}

	// 주소로 고른다. 맞는 것이 없으면 Other.
	public BlogPlatform byUrl(URI siteUrl) {
		return platforms.stream().filter(p -> p.supports(siteUrl)).findFirst().orElseThrow();
	}

	// 주소로 Other면 찾은 피드로 한 번 더 알아본다(자기 도메인을 붙인 블로그).
	public BlogPlatform resolve(BlogPlatform byUrl, Feed feed) {
		if (byUrl.platform() != Platform.OTHER) {
			return byUrl;
		}
		return platforms.stream()
			.filter(p -> p.platform() != Platform.OTHER && p.supportsFeed(feed))
			.findFirst()
			.orElse(byUrl);
	}

	public BlogPlatform get(Platform platform) {
		return platforms.stream().filter(p -> p.platform() == platform).findFirst().orElseThrow();
	}

}
