package com.lolog.api.blog;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.blog.FeedParser.Feed;
import com.lolog.api.blog.FeedParser.Item;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

// 플랫폼별 주소 규칙·알아보기·카테고리(#82, texts.md 4장 "플랫폼별 구현")를 서버 없이 확인한다.
// 플랫폼별 피드 모양은 11-14에 공개 피드(네이버·티스토리·velog·Medium·WordPress)를 받아 본 것에 맞췄다.
class BlogPlatformTests {

	private final NaverPlatform naver = new NaverPlatform();
	private final TistoryPlatform tistory = new TistoryPlatform();
	private final VelogPlatform velog = new VelogPlatform();
	private final MediumPlatform medium = new MediumPlatform();
	private final WordPressPlatform wordpress = new WordPressPlatform(new BlogFetcher(false), JsonMapper.builder().build());
	private final OtherPlatform other = new OtherPlatform();
	private final BlogPlatforms platforms = new BlogPlatforms(List.of(naver, tistory, velog, medium, wordpress, other));

	private static URI uri(String value) {
		return URI.create(value);
	}

	private static Feed feed(String generator, String link) {
		return new Feed("t", link, generator, List.of());
	}

	private static Item item(String... categories) {
		return new Item("g", "t", "https://x/1", "요약", null, null, List.of(categories));
	}

	@Test
	void 주소로_플랫폼을_고르고_RSS_주소_후보를_만든다() {
		assertThat(platforms.byUrl(uri("https://blog.naver.com/minsu123"))).isSameAs(naver);
		assertThat(naver.feedCandidates(uri("https://blog.naver.com/minsu123")))
			.containsExactly("https://rss.blog.naver.com/minsu123.xml");
		assertThat(naver.feedCandidates(uri("https://m.blog.naver.com/minsu123/223000000000")))
			.containsExactly("https://rss.blog.naver.com/minsu123.xml");
		assertThat(naver.feedCandidates(uri("https://blog.naver.com/PostList.naver?blogId=minsu123&from=x")))
			.containsExactly("https://rss.blog.naver.com/minsu123.xml");
		assertThat(naver.feedCandidates(uri("https://blog.naver.com/PostList.naver"))).isEmpty();

		assertThat(platforms.byUrl(uri("https://hello.tistory.com/12"))).isSameAs(tistory);
		assertThat(tistory.feedCandidates(uri("https://hello.tistory.com/12"))).containsExactly("https://hello.tistory.com/rss");

		assertThat(platforms.byUrl(uri("https://velog.io/@velopert/posts"))).isSameAs(velog);
		assertThat(velog.feedCandidates(uri("https://velog.io/@velopert/posts")))
			.containsExactly("https://v2.velog.io/rss/@velopert");
		assertThat(velog.feedCandidates(uri("https://velog.io/"))).isEmpty();

		assertThat(platforms.byUrl(uri("https://medium.com/@kim"))).isSameAs(medium);
		assertThat(medium.feedCandidates(uri("https://medium.com/@kim"))).containsExactly("https://medium.com/feed/@kim");
		assertThat(medium.feedCandidates(uri("https://medium.com/netflix-techblog/some-post")))
			.containsExactly("https://medium.com/feed/netflix-techblog");
		assertThat(medium.feedCandidates(uri("https://kim.medium.com/"))).containsExactly("https://kim.medium.com/feed");

		assertThat(platforms.byUrl(uri("https://hello.wordpress.com/2026/11/01/a"))).isSameAs(wordpress);
		assertThat(wordpress.feedCandidates(uri("https://hello.wordpress.com/2026/11/01/a")))
			.containsExactly("https://hello.wordpress.com/feed/");

		assertThat(platforms.byUrl(uri("https://brunch.co.kr/@kim"))).isSameAs(other);
		assertThat(other.feedCandidates(uri("https://brunch.co.kr/@kim"))).isEmpty();
	}

	@Test
	void 주소로_모르면_피드_generator로_알아본다() {
		assertThat(platforms.resolve(other, feed("TISTORY", null))).isSameAs(tistory);
		assertThat(platforms.resolve(other, feed("Naver Blog", null))).isSameAs(naver);
		assertThat(platforms.resolve(other, feed("Medium", null))).isSameAs(medium);
		assertThat(platforms.resolve(other, feed("https://wordpress.org/?v=7.2", null))).isSameAs(wordpress);
		assertThat(platforms.resolve(other, feed("http://wordpress.com/", null))).isSameAs(wordpress);
		assertThat(platforms.resolve(other, feed("https://github.com/jpmonette/feed", "https://velog.io/"))).isSameAs(velog);
		assertThat(platforms.resolve(other, feed("Blogger", "https://x.blogspot.com/"))).isSameAs(other);
		// 주소로 고른 플랫폼은 그대로다.
		assertThat(platforms.resolve(naver, feed("TISTORY", null))).isSameAs(naver);
	}

	@Test
	void 티스토리는_상위를_고르면_하위_카테고리_글도_맞는다() {
		Categories rule = tistory.categories(uri("https://a.tistory.com"), feed("TISTORY", null));
		Feed feed = new Feed("t", null, "TISTORY", List.of(item("새로운 소식/업데이트 소식"), item("운영 정책 안내")));
		assertThat(rule.options(feed)).containsExactly("새로운 소식", "새로운 소식/업데이트 소식", "운영 정책 안내");
		assertThat(rule.matched(item("새로운 소식/업데이트 소식"), List.of("새로운 소식"))).isEqualTo("새로운 소식/업데이트 소식");
		assertThat(rule.matched(item("새로운 소식/업데이트 소식"), List.of("새로운 소식/업데이트 소식")))
			.isEqualTo("새로운 소식/업데이트 소식");
		assertThat(rule.matched(item("새로운 소식들"), List.of("새로운 소식"))).isNull();
		// 하위 카테고리가 없는 플랫폼은 글자가 같아야 맞는다.
		assertThat(Categories.plain().matched(item("새로운 소식/업데이트 소식"), List.of("새로운 소식"))).isNull();
	}

	@Test
	void Medium과_velog는_카테고리를_고를_수_없다() {
		Categories rule = medium.categories(uri("https://medium.com/@kim"), feed("Medium", null));
		Feed feed = new Feed("t", null, "Medium", List.of(item("data-engineering", "pensive")));
		assertThat(rule.selectable()).isFalse();
		assertThat(rule.options(feed)).isEmpty();
		// 전부 받고, 태그를 카테고리로 적지 않는다.
		assertThat(rule.matched(item("data-engineering"), List.of())).isNull();
		assertThat(velog.categories(uri("https://velog.io/@a"), feed(null, null)).selectable()).isFalse();
		assertThat(Platform.MEDIUM.categorySelectable()).isFalse();
		assertThat(Platform.VELOG.categorySelectable()).isFalse();
		assertThat(Platform.TISTORY.categorySelectable()).isTrue();
	}

	@Test
	void WordPress는_진짜_카테고리_이름만_카테고리로_본다() {
		Categories rule = Categories.only(List.of("Development", "Releases"));
		Item mixed = item("Development", "Releases", "7.2", "development");
		assertThat(rule.of(mixed)).containsExactly("Development", "Releases");
		assertThat(rule.matched(item("7.2"), List.of("7.2"))).isNull();
		assertThat(rule.matched(mixed, List.of())).isEqualTo("Development");
		assertThat(rule.options(new Feed("t", null, null, List.of(item("7.2"))))).containsExactly("Development", "Releases");
	}

	@Test
	void WordPress_REST_주소는_자체_설치와_wordpress_com이_다르다() {
		assertThat(WordPressPlatform.restUrl(uri("https://wordpress.org/news/2026/x"),
				feed("https://wordpress.org/?v=7.2", "https://wordpress.org/news/")))
			.isEqualTo("https://wordpress.org/news/wp-json/wp/v2/categories" + WordPressPlatform.QUERY);
		assertThat(WordPressPlatform.restUrl(uri("https://hello.wordpress.com/"), feed(null, "https://hello.wordpress.com")))
			.isEqualTo("https://public-api.wordpress.com/wp/v2/sites/hello.wordpress.com/categories" + WordPressPlatform.QUERY);
		// wordpress.com에 자기 도메인을 붙인 블로그
		assertThat(WordPressPlatform.restUrl(uri("https://blog.example.com/"),
				feed("http://wordpress.com/", "https://blog.example.com")))
			.isEqualTo("https://public-api.wordpress.com/wp/v2/sites/blog.example.com/categories" + WordPressPlatform.QUERY);
	}

	@Test
	void 본문은_content_encoded가_있으면_그것이다() {
		Feed rss = FeedParser.parse("""
				<rss xmlns:content="http://purl.org/rss/1.0/modules/content/"><channel><title>t</title>
				<link>https://b.example.com/</link><generator>https://wordpress.org/?v=7.2</generator>
				<item><title>a</title><link>https://x/1</link><description>요약</description>
				<content:encoded><![CDATA[<p>전체 글</p>]]></content:encoded></item>
				<item><title>b</title><link>https://x/2</link><description>요약만</description></item></channel></rss>
				""".getBytes(StandardCharsets.UTF_8));
		assertThat(rss.link()).isEqualTo("https://b.example.com/");
		assertThat(rss.generator()).isEqualTo("https://wordpress.org/?v=7.2");
		assertThat(other.body(rss.items().get(0))).isEqualTo("<p>전체 글</p>");
		assertThat(other.body(rss.items().get(1))).isEqualTo("요약만");

		Feed atom = FeedParser.parse("""
				<feed xmlns="http://www.w3.org/2005/Atom"><title>t</title><generator>Blogger</generator>
				<entry><id>1</id><title>a</title><summary>요약</summary><content>전체</content></entry>
				<entry><id>2</id><title>b</title><summary>요약만</summary></entry></feed>
				""".getBytes(StandardCharsets.UTF_8));
		assertThat(atom.generator()).isEqualTo("Blogger");
		assertThat(other.body(atom.items().get(0))).isEqualTo("전체");
		assertThat(other.body(atom.items().get(1))).isEqualTo("요약만");
	}

}
