package com.lolog.api.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lolog.api.blog.BlogFetcher.FetchException;
import com.lolog.api.blog.FeedParser.Feed;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

// 내부 주소 막기(SSRF)와 피드 읽기를 서버 없이 확인한다. 플랫폼별 규칙은 BlogPlatformTests가 본다.
class BlogFetcherTests {

	@Test
	void 내부_주소는_막는다() throws Exception {
		for (String ip : new String[] { "127.0.0.1", "10.0.0.5", "172.16.3.4", "192.168.0.1", "169.254.169.254", "0.0.0.0",
				"100.64.0.1", "::1", "fc00::1", "fe80::1", "224.0.0.1" }) {
			assertThat(BlogFetcher.isInternal(InetAddress.getByName(ip))).as(ip).isTrue();
		}
		for (String ip : new String[] { "8.8.8.8", "125.209.222.141", "2001:4860:4860::8888" }) {
			assertThat(BlogFetcher.isInternal(InetAddress.getByName(ip))).as(ip).isFalse();
		}
	}

	@Test
	void 막기를_켜면_localhost와_내부_IP를_부르지_않는다() {
		BlogFetcher fetcher = new BlogFetcher(false);
		for (String url : new String[] { "http://localhost:6379/", "http://127.0.0.1/", "http://[::1]/", "http://10.1.2.3/" }) {
			assertThatThrownBy(() -> fetcher.get(url)).as(url)
				.isInstanceOfSatisfying(FetchException.class, e -> assertThat(e.isInternal()).isTrue());
		}
		assertThatThrownBy(() -> fetcher.get("file:///etc/passwd")).isInstanceOf(FetchException.class);
	}

	@Test
	void 주소에_https를_붙인다() {
		assertThat(FeedLocator.normalize("  blog.naver.com/minsu123 ")).isEqualTo("https://blog.naver.com/minsu123");
	}

	@Test
	void DOCTYPE이_있는_피드는_읽지_않는다() {
		String xxe = """
				<?xml version="1.0"?>
				<!DOCTYPE rss [<!ENTITY x SYSTEM "file:///etc/passwd">]>
				<rss><channel><title>&x;</title></channel></rss>
				""";
		assertThat(FeedParser.parse(xxe.getBytes(StandardCharsets.UTF_8))).isNull();
		assertThat(FeedParser.parse("<html><body>x</body></html>".getBytes(StandardCharsets.UTF_8))).isNull();
	}

	@Test
	void RSS의_guid가_없으면_link를_쓰고_날짜가_틀리면_비운다() {
		Feed feed = FeedParser.parse("""
				<rss><channel><title>t</title><item><title>a</title><link>https://x/1</link>
				<pubDate>어제</pubDate><category>일상</category><category> 일상 </category></item></channel></rss>
				""".getBytes(StandardCharsets.UTF_8));
		assertThat(feed.items().getFirst().guid()).isEqualTo("https://x/1");
		assertThat(feed.items().getFirst().publishedAt()).isNull();
		assertThat(feed.items().getFirst().categories()).containsExactly("일상");
	}

	@Test
	void 두_번_감싼_제목의_HTML_글자를_푼다() {
		// 티스토리 RSS 그대로(11-14 notice.tistory.com): XML을 풀어도 &lsquo;가 남는다
		Feed feed = FeedParser.parse("""
				<rss><channel><title>A&amp;amp;B 블로그</title>
				<item><title>더 깔끔해진 &amp;lsquo;장소&amp;rsquo; 기능</title><link>https://x/1</link></item>
				<item><title>글감 첨부&amp;nbsp;기능 종료</title><link>https://x/2</link></item>
				<item><title>그냥 제목 &amp; 보통</title><link>https://x/3</link></item></channel></rss>
				""".getBytes(StandardCharsets.UTF_8));
		assertThat(feed.title()).isEqualTo("A&B 블로그");
		assertThat(feed.items().get(0).title()).isEqualTo("더 깔끔해진 \u2018장소\u2019 기능");
		assertThat(feed.items().get(1).title()).isEqualTo("글감 첨부 기능 종료");
		assertThat(feed.items().get(2).title()).isEqualTo("그냥 제목 & 보통");
	}

	@Test
	void HTML을_글로_바꾼다() {
		assertThat(FeedParser.toText("<div>안녕&nbsp;하세요</div><p>둘째<br/>셋째</p><style>p{}</style>"))
			.isEqualTo("안녕 하세요\n\n둘째\n셋째");
	}

}
