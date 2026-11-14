package com.lolog.api.blog;

import com.lolog.api.blog.BlogFetcher.FetchException;
import com.lolog.api.blog.FeedParser.Feed;
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

	WordPressPlatform(BlogFetcher fetcher, ObjectMapper objectMapper) {
		this.fetcher = fetcher;
		this.objectMapper = objectMapper;
	}

	@Override
	public Platform platform() {
		return Platform.WORDPRESS;
	}

	@Override
	public boolean supports(URI siteUrl) {
		return BlogPlatform.host(siteUrl).endsWith(".wordpress.com");
	}

	@Override
	public boolean supportsFeed(Feed feed) {
		String generator = BlogPlatform.generator(feed);
		return generator.contains("wordpress.org") || generator.contains("wordpress.com");
	}

	@Override
	public List<String> feedCandidates(URI siteUrl) {
		return List.of("https://" + BlogPlatform.host(siteUrl) + "/feed/");
	}

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

	// wordpress.com 블로그는 공용 REST, 자체 설치는 블로그 첫 화면 주소(channel link, 없으면 넣은 주소) 뒤 /wp-json
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
