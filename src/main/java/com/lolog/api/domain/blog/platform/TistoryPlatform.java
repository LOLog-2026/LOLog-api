package com.lolog.api.domain.blog.platform;

import com.lolog.api.domain.blog.service.FeedParser.Feed;
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

	/**
	 * {이름}.tistory.com 주소인지 본다.
	 *
	 * @param siteUrl 사용자가 넣은 블로그 주소
	 * @return 티스토리 주소면 true
	 */
	@Override
	public boolean supports(URI siteUrl) {
		return BlogPlatform.host(siteUrl).endsWith(".tistory.com");
	}

	/**
	 * 피드의 generator가 tistory인지 본다.
	 *
	 * @param feed 찾은 피드
	 * @return 티스토리 피드면 true
	 */
	@Override
	public boolean supportsFeed(Feed feed) {
		return BlogPlatform.generator(feed).equals("tistory");
	}

	/**
	 * 호스트 뒤에 /rss를 붙인 주소를 만든다.
	 *
	 * @param siteUrl 사용자가 넣은 블로그 주소
	 * @return 먼저 읽어 볼 RSS 주소
	 */
	@Override
	public List<String> feedCandidates(URI siteUrl) {
		return List.of("https://" + BlogPlatform.host(siteUrl) + "/rss");
	}

	/**
	 * "상위/하위" 카테고리 규칙을 돌려준다.
	 *
	 * @param siteUrl 블로그 주소(쓰지 않음)
	 * @param feed 읽은 피드(쓰지 않음)
	 * @return 하위 카테고리 규칙
	 */
	@Override
	public Categories categories(URI siteUrl, Feed feed) {
		return Categories.hierarchy();
	}

}
