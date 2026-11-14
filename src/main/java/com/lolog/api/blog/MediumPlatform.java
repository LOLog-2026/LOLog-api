package com.lolog.api.blog;

import com.lolog.api.blog.FeedParser.Feed;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// Medium: medium.com/@{id} → medium.com/feed/@{id}, medium.com/{출판물} → medium.com/feed/{출판물}, {id}.medium.com → /feed.
// Medium의 <category>는 모두 태그라(카테고리가 없음) 고를 수 없고 글을 전부 받는다(정함 11-14).
@Component
@Order(4)
class MediumPlatform implements BlogPlatform {

	private static final Pattern FIRST = Pattern.compile("^/([^/]+)(/.*)?$");

	@Override
	public Platform platform() {
		return Platform.MEDIUM;
	}

	@Override
	public boolean supports(URI siteUrl) {
		String host = BlogPlatform.host(siteUrl);
		return host.equals("medium.com") || host.endsWith(".medium.com");
	}

	@Override
	public boolean supportsFeed(Feed feed) {
		return BlogPlatform.generator(feed).equals("medium");
	}

	@Override
	public List<String> feedCandidates(URI siteUrl) {
		String host = BlogPlatform.host(siteUrl);
		if (!host.equals("medium.com")) {
			return List.of("https://" + host + "/feed");
		}
		Matcher m = siteUrl.getPath() == null ? null : FIRST.matcher(siteUrl.getPath());
		if (m == null || !m.matches() || m.group(1).equals("feed")) {
			return List.of();
		}
		return List.of("https://medium.com/feed/" + m.group(1));
	}

	@Override
	public Categories categories(URI siteUrl, Feed feed) {
		return Categories.none();
	}

}
