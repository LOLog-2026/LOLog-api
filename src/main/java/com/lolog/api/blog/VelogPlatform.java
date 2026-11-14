package com.lolog.api.blog;

import com.lolog.api.blog.FeedParser.Feed;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// velog: velog.io/@{id} → v2.velog.io/rss/@{id}. velog에는 카테고리가 없어(태그·시리즈만) 고를 수 없고 글을 전부 받는다(정함 11-14).
@Component
@Order(3)
class VelogPlatform implements BlogPlatform {

	private static final Pattern USER = Pattern.compile("^(?:/rss)?/@([^/]+)(/.*)?$");

	@Override
	public Platform platform() {
		return Platform.VELOG;
	}

	@Override
	public boolean supports(URI siteUrl) {
		String host = BlogPlatform.host(siteUrl);
		return host.equals("velog.io") || host.equals("v2.velog.io");
	}

	// velog 피드의 generator는 범용 라이브러리 이름이라, 블로그 첫 화면 주소(link)로 알아본다.
	@Override
	public boolean supportsFeed(Feed feed) {
		return feed.link() != null && feed.link().toLowerCase(java.util.Locale.ROOT).matches("^https?://velog\\.io(/.*)?$");
	}

	@Override
	public List<String> feedCandidates(URI siteUrl) {
		Matcher m = siteUrl.getPath() == null ? null : USER.matcher(siteUrl.getPath());
		return m != null && m.matches() ? List.of("https://v2.velog.io/rss/@" + m.group(1)) : List.of();
	}

	@Override
	public Categories categories(URI siteUrl, Feed feed) {
		return Categories.none();
	}

}
