package com.lolog.api.domain.blog.platform;

import com.lolog.api.domain.blog.service.FeedParser.Feed;
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

	/**
	 * medium.com이나 {id}.medium.com 주소인지 본다.
	 *
	 * @param siteUrl 사용자가 넣은 블로그 주소
	 * @return Medium 주소면 true
	 */
	@Override
	public boolean supports(URI siteUrl) {
		String host = BlogPlatform.host(siteUrl);
		return host.equals("medium.com") || host.endsWith(".medium.com");
	}

	/**
	 * 피드의 generator가 medium인지 본다.
	 *
	 * @param feed 찾은 피드
	 * @return Medium 피드면 true
	 */
	@Override
	public boolean supportsFeed(Feed feed) {
		return BlogPlatform.generator(feed).equals("medium");
	}

	/**
	 * 주소 모양에 맞는 Medium RSS 주소를 만든다.
	 *
	 * @param siteUrl 사용자가 넣은 블로그 주소
	 * @return 먼저 읽어 볼 RSS 주소. 모양이 맞지 않으면 빈 목록
	 */
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

	/**
	 * 카테고리를 고를 수 없는 규칙을 돌려준다.
	 *
	 * @param siteUrl 블로그 주소(쓰지 않음)
	 * @param feed 읽은 피드(쓰지 않음)
	 * @return 고를 수 없는 규칙
	 */
	@Override
	public Categories categories(URI siteUrl, Feed feed) {
		return Categories.none();
	}

}
