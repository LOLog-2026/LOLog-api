package com.lolog.api.blog;

import com.lolog.api.blog.FeedParser.Feed;
import java.net.URI;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 네이버 블로그: blog.naver.com/{id} → rss.blog.naver.com/{id}.xml. 카테고리는 <category>, 태그는 <tag> 칸에 따로 와서 <category>만 본다.
@Component
@Order(1)
class NaverPlatform implements BlogPlatform {

	private static final Pattern PATH = Pattern.compile("^/([A-Za-z0-9_-]+)(/.*)?$");
	private static final Pattern QUERY = Pattern.compile("(?:^|&)blogId=([A-Za-z0-9_-]+)");

	@Override
	public Platform platform() {
		return Platform.NAVER;
	}

	@Override
	public boolean supports(URI siteUrl) {
		String host = BlogPlatform.host(siteUrl);
		return host.equals("blog.naver.com") || host.equals("m.blog.naver.com") || host.equals("rss.blog.naver.com");
	}

	@Override
	public boolean supportsFeed(Feed feed) {
		return BlogPlatform.generator(feed).startsWith("naver blog");
	}

	@Override
	public List<String> feedCandidates(URI siteUrl) {
		if (BlogPlatform.host(siteUrl).equals("rss.blog.naver.com")) {
			return List.of();
		}
		String id = null;
		if (siteUrl.getRawQuery() != null) {
			Matcher q = QUERY.matcher(siteUrl.getRawQuery());
			if (q.find()) {
				id = q.group(1);
			}
		}
		if (id == null && siteUrl.getPath() != null) {
			Matcher p = PATH.matcher(siteUrl.getPath());
			if (p.matches() && !p.group(1).endsWith(".naver")) {
				id = p.group(1);
			}
		}
		return id == null ? List.of() : List.of("https://rss.blog.naver.com/" + id + ".xml");
	}

}
