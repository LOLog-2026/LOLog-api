package com.lolog.api.domain.blog.platform;

import com.lolog.api.domain.blog.service.FeedParser.Feed;
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

	/**
	 * 네이버 블로그 주소인지 본다.
	 *
	 * @param siteUrl 사용자가 넣은 블로그 주소
	 * @return 네이버 블로그 주소면 true
	 */
	@Override
	public boolean supports(URI siteUrl) {
		String host = BlogPlatform.host(siteUrl);
		return host.equals("blog.naver.com") || host.equals("m.blog.naver.com") || host.equals("rss.blog.naver.com");
	}

	/**
	 * 피드의 generator가 naver blog로 시작하는지 본다.
	 *
	 * @param feed 찾은 피드
	 * @return 네이버 블로그 피드면 true
	 */
	@Override
	public boolean supportsFeed(Feed feed) {
		return BlogPlatform.generator(feed).startsWith("naver blog");
	}

	/**
	 * 주소의 블로그 ID로 네이버 RSS 주소를 만든다.
	 *
	 * @param siteUrl 사용자가 넣은 블로그 주소
	 * @return 먼저 읽어 볼 RSS 주소. 이미 RSS 주소거나 ID를 못 찾으면 빈 목록
	 */
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
