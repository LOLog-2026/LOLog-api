package com.lolog.api.blog;

import com.lolog.api.blog.FeedParser.Feed;
import com.lolog.api.blog.FeedParser.Item;
import java.net.URI;
import java.util.List;
import java.util.Locale;

// 블로그 플랫폼마다 다른 RSS 주소 규칙, 본문 칸, 카테고리 읽는 법(texts.md 4장 "플랫폼별 구현").
// 구현은 Spring이 @Order 순서로 BlogPlatforms에 넣어 주고, 맨 마지막은 늘 맞는 OtherPlatform이다.
public interface BlogPlatform {

	Platform platform();

	// 사용자가 넣은 주소가 이 플랫폼 주소인가
	boolean supports(URI siteUrl);

	// 주소로 모를 때(자기 도메인을 붙인 블로그) 찾은 피드의 generator 등으로 알아본다.
	default boolean supportsFeed(Feed feed) {
		return false;
	}

	// 넣은 주소를 읽기 전에 먼저 시도할 RSS 주소(순서대로)
	default List<String> feedCandidates(URI siteUrl) {
		return List.of();
	}

	// 본문 칸. content:encoded가 있으면 그것, 없으면 description(Other도 같음, 정함 11-14)
	default String body(Item item) {
		return item.body();
	}

	// 피드 하나를 읽을 때의 카테고리 규칙. WordPress처럼 사이트 단위로 목록을 받아 와야 하는 곳이 있어 피드마다 만든다.
	default Categories categories(URI siteUrl, Feed feed) {
		return Categories.plain();
	}

	static String host(URI uri) {
		return uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
	}

	static String generator(Feed feed) {
		return feed.generator() == null ? "" : feed.generator().toLowerCase(Locale.ROOT);
	}

}
