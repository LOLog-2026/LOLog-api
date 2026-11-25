package com.lolog.api.domain.blog.platform;

import com.lolog.api.domain.blog.service.BlogFetcher.FetchException;
import com.lolog.api.domain.blog.service.BlogFetcher;
import com.lolog.api.domain.blog.service.FeedParser.Feed;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.jsoup.parser.Parser;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// WordPress: {이름}.wordpress.com → /feed/. 자체 설치는 주소로 모르니 피드 generator(wordpress.org·wordpress.com)로 알아본다.
// RSS의 <category>에 카테고리와 태그가 섞여 와서, WordPress REST API로 진짜 카테고리 이름을 받아 그 이름만 카테고리로 본다(정함 11-14).
// REST를 받지 못하면(끈 사이트 등) <category>를 그대로 쓴다. REST 주소도 BlogFetcher로 불러 내부 주소는 거절한다.
@Component
@Order(5)
class WordPressPlatform implements BlogPlatform {

	// 많이 쓴 카테고리부터 100개(REST 한 쪽의 최대)
	static final String QUERY = "?per_page=100&orderby=count&order=desc&_fields=name";

	private final BlogFetcher fetcher;
	private final ObjectMapper objectMapper;

	/** 쓰는 도구를 받는다. */
	WordPressPlatform(BlogFetcher fetcher, ObjectMapper objectMapper) {
		this.fetcher = fetcher;
		this.objectMapper = objectMapper;
	}

	@Override
	public Platform platform() {
		return Platform.WORDPRESS;
	}

	/**
	 * {이름}.wordpress.com 주소인지 본다.
	 *
	 * @param siteUrl 사용자가 넣은 블로그 주소
	 * @return wordpress.com 주소면 true
	 */
	@Override
	public boolean supports(URI siteUrl) {
		return BlogPlatform.host(siteUrl).endsWith(".wordpress.com");
	}

	/**
	 * 피드의 generator에 wordpress.org나 wordpress.com이 있는지 본다.
	 *
	 * @param feed 찾은 피드
	 * @return WordPress 피드면 true
	 */
	@Override
	public boolean supportsFeed(Feed feed) {
		String generator = BlogPlatform.generator(feed);
		return generator.contains("wordpress.org") || generator.contains("wordpress.com");
	}

	/**
	 * 호스트 뒤에 /feed/를 붙인 주소를 만든다.
	 *
	 * @param siteUrl 사용자가 넣은 블로그 주소
	 * @return 먼저 읽어 볼 RSS 주소
	 */
	@Override
	public List<String> feedCandidates(URI siteUrl) {
		return List.of("https://" + BlogPlatform.host(siteUrl) + "/feed/");
	}

	/**
	 * REST로 진짜 카테고리 이름을 받아 규칙을 만든다. 받지 못하면 {@code <category>}를 그대로 보는 규칙이다.
	 *
	 * @param siteUrl 사용자가 넣은 블로그 주소
	 * @param feed 읽은 피드(REST 주소를 정할 때 link·generator를 본다)
	 * @return 진짜 카테고리 이름만 보는 규칙. 받지 못했으면 {@code <category>} 그대로 보는 규칙
	 */
	@Override
	public Categories categories(URI siteUrl, Feed feed) {
		try {
			JsonNode list = objectMapper.readTree(fetcher.get(restUrl(siteUrl, feed)).body());
			List<String> names = new ArrayList<>();
			for (JsonNode node : list) {
				JsonNode name = node.get("name");
				if (name != null && name.isString()) {
					// REST는 이름을 HTML 문자(&amp; 등)로 준다. RSS의 <category>는 풀린 글자라 맞춰 푼다.
					String value = Parser.unescapeEntities(name.asString(), false).strip();
					if (!value.isEmpty() && !names.contains(value)) {
						names.add(value);
					}
				}
			}
			return names.isEmpty() ? Categories.plain() : Categories.only(names);
		}
		catch (RuntimeException e) {
			// 받지 못했거나(FetchException) JSON 목록이 아님
			return Categories.plain();
		}
	}

	/**
	 * wordpress.com 블로그는 공용 REST, 자체 설치는 블로그 첫 화면 주소(channel link, 없으면 넣은 주소) 뒤 /wp-json
	 *
	 * @param siteUrl 사용자가 넣은 블로그 주소
	 * @param feed 읽은 피드
	 * @return 카테고리 목록을 받을 REST 주소
	 */
	static String restUrl(URI siteUrl, Feed feed) {
		URI home = siteUrl;
		if (feed.link() != null) {
			try {
				home = BlogFetcher.parse(feed.link());
			}
			catch (FetchException e) {
				// 모양이 틀린 link는 넣은 주소로 대신한다.
			}
		}
		String host = BlogPlatform.host(home);
		if (host.endsWith(".wordpress.com") || BlogPlatform.generator(feed).contains("wordpress.com")) {
			return "https://public-api.wordpress.com/wp/v2/sites/" + host + "/categories" + QUERY;
		}
		String path = home.getRawPath() == null ? "" : home.getRawPath().replaceAll("/+$", "");
		String port = home.getPort() == -1 ? "" : ":" + home.getPort();
		return home.getScheme() + "://" + home.getHost() + port + path + "/wp-json/wp/v2/categories" + QUERY;
	}

}
