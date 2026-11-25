package com.lolog.api.domain.blog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lolog.api.domain.blog.platform.BlogPlatform;
import com.lolog.api.domain.blog.platform.BlogPlatforms;
import com.lolog.api.domain.blog.service.BlogFetcher.FetchException;
import com.lolog.api.domain.blog.service.BlogFetcher.Fetched;
import com.lolog.api.domain.blog.service.FeedLocator.Located;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@DisplayName("FeedLocator")
@ExtendWith(MockitoExtension.class)
class FeedLocatorTest {

	private static final String SITE = "https://blog.example";
	private static final String RSS = "<rss version=\"2.0\"><channel><title>블로그</title></channel></rss>";

	@Mock
	BlogFetcher fetcher;
	@Mock
	BlogPlatforms platforms;
	@Mock
	BlogPlatform byUrl;
	@Mock
	BlogPlatform resolved;

	private FeedLocator locator;

	@BeforeEach
	void setUp() {
		locator = new FeedLocator(fetcher, platforms);
		when(platforms.byUrl(URI.create(SITE))).thenReturn(byUrl);
	}

	@Test
	@DisplayName("플랫폼 후보를 차례로 읽고 넣은 주소는 맨 뒤에 둔다")
	void 플랫폼_후보부터_읽고_넣은_주소는_마지막이다() {
		// given
		when(byUrl.feedCandidates(URI.create(SITE))).thenReturn(List.of(SITE + "/rss", SITE + "/atom"));
		when(fetcher.get(anyString())).thenThrow(new FetchException("404"));

		// when & then
		assertThatThrownBy(() -> locator.locate(SITE)).isInstanceOf(FetchException.class);
		InOrder order = inOrder(fetcher);
		order.verify(fetcher).get(SITE + "/rss");
		order.verify(fetcher).get(SITE + "/atom");
		order.verify(fetcher).get(SITE);
	}

	@Test
	@DisplayName("앞 후보가 실패하면 다음 후보로 넘어가고, 피드를 찾으면 뒤 후보는 읽지 않는다")
	void 피드를_찾으면_멈춘다() {
		// given
		when(byUrl.feedCandidates(URI.create(SITE))).thenReturn(List.of(SITE + "/rss", SITE + "/atom"));
		when(fetcher.get(SITE + "/rss")).thenThrow(new FetchException("404"));
		when(fetcher.get(SITE + "/atom")).thenReturn(fetched(SITE + "/atom", RSS));
		when(platforms.resolve(eq(byUrl), any())).thenReturn(resolved);

		// when
		Located located = locator.locate(SITE);

		// then
		assertThat(located.feedUrl()).isEqualTo(SITE + "/atom");
		assertThat(located.platform()).isSameAs(resolved);
		assertThat(located.feed().title()).isEqualTo("블로그");
		verify(fetcher, never()).get(SITE);
	}

	@Test
	@DisplayName("넣은 주소가 이미 후보에 있으면 두 번 읽지 않는다")
	void 넣은_주소가_후보에_있으면_한_번만_읽는다() {
		// given
		when(byUrl.feedCandidates(URI.create(SITE))).thenReturn(List.of(SITE));
		when(fetcher.get(SITE)).thenThrow(new FetchException("404"));

		// when & then
		assertThatThrownBy(() -> locator.locate(SITE)).isInstanceOf(FetchException.class);
		verify(fetcher, times(1)).get(SITE);
	}

	@Test
	@DisplayName("내부 주소라 거절되면 다른 후보를 읽지 않고 그 예외를 던진다")
	void 내부_주소면_바로_멈춘다() {
		// given
		when(byUrl.feedCandidates(URI.create(SITE))).thenReturn(List.of(SITE + "/rss"));
		FetchException internal = new FetchException("내부", true);
		when(fetcher.get(SITE + "/rss")).thenThrow(internal);

		// when & then
		assertThatThrownBy(() -> locator.locate(SITE)).isSameAs(internal);
		verify(fetcher, never()).get(SITE);
	}

	@Test
	@DisplayName("모든 후보가 실패하면 마지막 실패를 던진다")
	void 모두_실패하면_마지막_실패를_던진다() {
		// given
		when(byUrl.feedCandidates(URI.create(SITE))).thenReturn(List.of(SITE + "/rss"));
		when(fetcher.get(SITE + "/rss")).thenThrow(new FetchException("첫째"));
		FetchException last = new FetchException("둘째");
		when(fetcher.get(SITE)).thenThrow(last);

		// when & then
		assertThatThrownBy(() -> locator.locate(SITE)).isSameAs(last);
	}

	@Test
	@DisplayName("피드가 아닌 페이지면 rel=alternate RSS 링크를 따라가 읽는다")
	void 페이지의_RSS_링크를_따라간다() {
		// given
		when(byUrl.feedCandidates(URI.create(SITE))).thenReturn(List.of());
		String html = "<html><head><link rel=\"stylesheet\" href=\"/a.css\">"
				+ "<link rel=\"alternate\" type=\"application/rss+xml\" href=\"/feed\"></head></html>";
		when(fetcher.get(SITE)).thenReturn(fetched(SITE, html));
		when(fetcher.get(SITE + "/feed")).thenReturn(fetched(SITE + "/feed", RSS));
		when(platforms.resolve(eq(byUrl), any())).thenReturn(resolved);

		// when
		Located located = locator.locate(SITE);

		// then
		assertThat(located.feedUrl()).isEqualTo(SITE + "/feed");
		verify(fetcher, never()).get(SITE + "/a.css");
	}

	@Test
	@DisplayName("피드도 RSS 링크도 없으면 FetchException이다")
	void 피드가_없으면_FetchException이다() {
		// given
		when(byUrl.feedCandidates(URI.create(SITE))).thenReturn(List.of());
		when(fetcher.get(SITE)).thenReturn(fetched(SITE, "<html></html>"));

		// when & then
		assertThatThrownBy(() -> locator.locate(SITE)).isInstanceOf(FetchException.class)
			.hasMessage("RSS를 찾지 못했습니다.");
	}

	private static Fetched fetched(String uri, String body) {
		return new Fetched(URI.create(uri), body.getBytes(StandardCharsets.UTF_8), "text/html");
	}

}
