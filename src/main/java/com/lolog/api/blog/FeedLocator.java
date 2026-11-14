package com.lolog.api.blog;

import com.lolog.api.blog.BlogFetcher.FetchException;
import com.lolog.api.blog.BlogFetcher.Fetched;
import com.lolog.api.blog.FeedParser.Feed;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

// 블로그 주소로 플랫폼을 고르고 RSS 주소를 찾는다(texts.md 4장 "연결 방식"·"플랫폼 고르기").
// 주소로 고른 플랫폼의 후보부터 읽고, 안 되면 넣은 주소 그대로 + <link rel="alternate"> 찾기로 읽는다.
@Component
public class FeedLocator {

	private final BlogFetcher fetcher;
	private final BlogPlatforms platforms;

	public FeedLocator(BlogFetcher fetcher, BlogPlatforms platforms) {
		this.fetcher = fetcher;
		this.platforms = platforms;
	}

	public record Located(String feedUrl, Feed feed, BlogPlatform platform) {
	}

	// 사용자가 넣은 주소. 앞뒤 공백을 빼고, http·https가 없으면 https를 붙인다.
	public static String normalize(String url) {
		String trimmed = url == null ? "" : url.strip();
		if (trimmed.isEmpty()) {
			throw new FetchException("주소가 비었습니다.");
		}
		String lower = trimmed.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
			trimmed = "https://" + trimmed;
		}
		BlogFetcher.parse(trimmed);
		return trimmed;
	}

	public Located locate(String siteUrl) {
		BlogPlatform byUrl = platforms.byUrl(URI.create(siteUrl));
		List<String> candidates = new ArrayList<>(byUrl.feedCandidates(URI.create(siteUrl)));
		if (!candidates.contains(siteUrl)) {
			candidates.add(siteUrl);
		}
		FetchException last = null;
		for (String candidate : candidates) {
			try {
				Fetched fetched = fetcher.get(candidate);
				Feed feed = FeedParser.parse(fetched.body());
				if (feed != null) {
					return new Located(fetched.uri().toString(), feed, platforms.resolve(byUrl, feed));
				}
				// 피드가 아니면 블로그 페이지로 보고 RSS 링크를 찾는다.
				for (String link : alternates(fetched)) {
					Fetched linked = fetcher.get(link);
					Feed linkedFeed = FeedParser.parse(linked.body());
					if (linkedFeed != null) {
						return new Located(linked.uri().toString(), linkedFeed, platforms.resolve(byUrl, linkedFeed));
					}
				}
			}
			catch (FetchException e) {
				// 내부 주소는 다른 후보를 볼 것 없이 거절한다.
				if (e.isInternal()) {
					throw e;
				}
				last = e;
			}
		}
		throw last != null ? last : new FetchException("RSS를 찾지 못했습니다.");
	}

	// HTML의 <link rel="alternate" type="application/rss+xml|atom+xml" href="...">
	private static List<String> alternates(Fetched page) {
		Document doc = Jsoup.parse(new String(page.body(), StandardCharsets.UTF_8), page.uri().toString());
		List<String> links = new ArrayList<>();
		for (Element link : doc.select("link[rel~=(?i)alternate][href]")) {
			String type = link.attr("type").toLowerCase(Locale.ROOT);
			if (type.contains("rss") || type.contains("atom")) {
				String href = link.absUrl("href");
				if (!href.isEmpty() && !links.contains(href)) {
					links.add(href);
				}
			}
		}
		return links.size() > 3 ? links.subList(0, 3) : links;
	}

}
