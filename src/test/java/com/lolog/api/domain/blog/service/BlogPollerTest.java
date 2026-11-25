package com.lolog.api.domain.blog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lolog.api.domain.blog.entity.BlogSource;
import com.lolog.api.domain.blog.platform.BlogPlatform;
import com.lolog.api.domain.blog.platform.BlogPlatforms;
import com.lolog.api.domain.blog.platform.Categories;
import com.lolog.api.domain.blog.platform.Platform;
import com.lolog.api.domain.blog.repository.BlogSourceRepository;
import com.lolog.api.domain.blog.service.BlogFetcher.FetchException;
import com.lolog.api.domain.blog.service.BlogFetcher.Fetched;
import com.lolog.api.domain.blog.service.FeedParser.Feed;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

@DisplayName("BlogPoller")
@ExtendWith(MockitoExtension.class)
class BlogPollerTest {

	private static final String SITE = "https://blog.example";
	private static final String FEED_URL = "https://blog.example/rss";
	private static final String RSS = "<rss version=\"2.0\"><channel><title>블로그</title>"
			+ "<item><title>t</title><link>https://blog.example/1</link><guid>g1</guid></item></channel></rss>";

	@Mock
	BlogSourceRepository sources;
	@Mock
	BlogFetcher fetcher;
	@Mock
	BlogService blogs;
	@Mock
	BlogPlatforms platforms;
	@Mock
	BlogPlatform platform;

	private BlogPoller poller;

	@BeforeEach
	void setUp() {
		poller = new BlogPoller(sources, fetcher, blogs, new ObjectMapper(), platforms);
	}

	@Test
	@DisplayName("연결이 없으면 피드를 받지 않고 0이다")
	void 연결이_없으면_0이다() {
		// given
		when(sources.findById(1L)).thenReturn(Optional.empty());

		// when & then
		assertThat(poller.poll(1L)).isZero();
		verify(fetcher, never()).get(anyString());
	}

	@Test
	@DisplayName("받은 것이 피드가 아니면 가져오지 않고 0이다")
	void 피드가_아니면_0이다() {
		// given
		when(sources.findById(1L)).thenReturn(Optional.of(source()));
		when(fetcher.get(FEED_URL)).thenReturn(fetched("<html></html>"));

		// when & then
		assertThat(poller.poll(1L)).isZero();
		verify(blogs, never()).importIfConnected(anyLong(), anyLong(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("피드를 받지 못하면 0이다")
	void 피드를_못_받으면_0이다() {
		// given
		when(sources.findById(1L)).thenReturn(Optional.of(source()));
		when(fetcher.get(FEED_URL)).thenThrow(new FetchException("끊김"));

		// when & then
		assertThat(poller.poll(1L)).isZero();
	}

	@Test
	@DisplayName("글을 가져오다 다른 예외가 나도 던지지 않고 0이다")
	void 가져오다_예외가_나면_0이다() {
		// given
		when(sources.findById(1L)).thenReturn(Optional.of(source()));
		when(fetcher.get(FEED_URL)).thenReturn(fetched(RSS));
		when(platforms.get(Platform.TISTORY)).thenReturn(platform);
		when(blogs.importIfConnected(anyLong(), anyLong(), any(), any(), any(), any(), any()))
			.thenThrow(new IllegalStateException("DB"));

		// when & then
		assertThat(poller.poll(1L)).isZero();
	}

	@Test
	@DisplayName("연결한 플랫폼의 규칙과 저장한 카테고리로 가져오고, 가져온 수를 돌려준다")
	void 저장한_설정으로_가져온다() {
		// given
		when(sources.findById(1L)).thenReturn(Optional.of(source()));
		when(fetcher.get(FEED_URL)).thenReturn(fetched(RSS));
		when(platforms.get(Platform.TISTORY)).thenReturn(platform);
		Categories rule = Categories.hierarchy();
		when(platform.categories(eq(URI.create(SITE)), any())).thenReturn(rule);
		when(blogs.importIfConnected(eq(1L), eq(7L), eq("블로그"), eq(List.of("개발")), any(), eq(platform),
				eq(rule))).thenReturn(2);

		// when
		int added = poller.poll(1L);

		// then
		assertThat(added).isEqualTo(2);
		ArgumentCaptor<Feed> feed = ArgumentCaptor.forClass(Feed.class);
		verify(blogs).importIfConnected(eq(1L), eq(7L), any(), any(), feed.capture(), any(), any());
		assertThat(feed.getValue().items()).extracting("guid").containsExactly("g1");
	}

	@Test
	@DisplayName("블로그 하나를 읽지 못해도 다음 블로그를 읽는다")
	void 하나가_실패해도_다음_블로그를_읽는다() {
		// given
		when(sources.findAllIds()).thenReturn(List.of(1L, 2L));
		when(sources.findById(1L)).thenReturn(Optional.of(source()));
		when(sources.findById(2L)).thenReturn(Optional.empty());
		when(fetcher.get(FEED_URL)).thenThrow(new FetchException("끊김"));

		// when
		poller.pollAll();

		// then
		verify(sources).findById(2L);
	}

	private static BlogSource source() {
		return new BlogSource(7L, SITE, "블로그", FEED_URL, Platform.TISTORY, "[\"개발\"]");
	}

	private static Fetched fetched(String body) {
		return new Fetched(URI.create(FEED_URL), body.getBytes(StandardCharsets.UTF_8), "application/xml");
	}

}
