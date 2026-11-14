package com.lolog.api.blog;

import com.lolog.api.blog.FeedParser.Feed;
import java.net.URI;
import java.util.List;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 티스토리: {이름}.tistory.com → /rss. 하위 카테고리는 <category>에 "상위/하위"로 오고, 태그는 RSS에 오지 않는다.
// 상위를 고르면 하위 카테고리 글도 가져온다(정함 11-14).
@Component
@Order(2)
class TistoryPlatform implements BlogPlatform {

	@Override
	public Platform platform() {
		return Platform.TISTORY;
	}

	@Override
	public boolean supports(URI siteUrl) {
		return BlogPlatform.host(siteUrl).endsWith(".tistory.com");
	}

	@Override
	public boolean supportsFeed(Feed feed) {
		return BlogPlatform.generator(feed).equals("tistory");
	}

	@Override
	public List<String> feedCandidates(URI siteUrl) {
		return List.of("https://" + BlogPlatform.host(siteUrl) + "/rss");
	}

	@Override
	public Categories categories(URI siteUrl, Feed feed) {
		return Categories.hierarchy();
	}

}
