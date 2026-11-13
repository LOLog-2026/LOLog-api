package com.lolog.api.blog;

import com.lolog.api.blog.BlogFetcher.FetchException;
import com.lolog.api.blog.BlogFetcher.Fetched;
import com.lolog.api.blog.FeedParser.Feed;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

// 블로그 주소로 RSS 주소를 찾는다(texts.md 4장 "연결 방식"): 플랫폼별 주소 규칙 + 자동 찾기.
// 플랫폼 이름은 찾을 때만 쓰고 저장하지 않는다.
@Component
public class FeedLocator {

	private static final Pattern NAVER_PATH = Pattern.compile("^/([A-Za-z0-9_-]+)(/.*)?$");
	private static final Pattern NAVER_QUERY = Pattern.compile("(?:^|&)blogId=([A-Za-z0-9_-]+)");

	private final BlogFetcher fetcher;

	public FeedLocator(BlogFetcher fetcher) {
		this.fetcher = fetcher;
	}

	public record Located(String feedUrl, Feed feed) {
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
		List<String> candidates = new ArrayList<>();
		String known = platformFeed(URI.create(siteUrl));
		if (known != null) {
			candidates.add(known);
		}
		candidates.add(siteUrl);
		FetchException last = null;
		for (String candidate : candidates) {
			try {
				Fetched fetched = fetcher.get(candidate);
				Feed feed = FeedParser.parse(fetched.body());
				if (feed != null) {
					return new Located(fetched.uri().toString(), feed);
				}
				// 피드가 아니면 블로그 페이지로 보고 RSS 링크를 찾는다.
				for (String link : alternates(fetched)) {
					Fetched linked = fetcher.get(link);
					Feed linkedFeed = FeedParser.parse(linked.body());
					if (linkedFeed != null) {
						return new Located(linked.uri().toString(), linkedFeed);
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

	// 네이버 블로그: blog.naver.com/{id} → rss.blog.naver.com/{id}.xml, 티스토리: {이름}.tistory.com → /rss
	static String platformFeed(URI uri) {
		String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
		if (host.equals("blog.naver.com") || host.equals("m.blog.naver.com")) {
			String id = null;
			if (uri.getRawQuery() != null) {
				Matcher q = NAVER_QUERY.matcher(uri.getRawQuery());
				if (q.find()) {
					id = q.group(1);
				}
			}
			if (id == null && uri.getPath() != null) {
				Matcher p = NAVER_PATH.matcher(uri.getPath());
				if (p.matches() && !p.group(1).endsWith(".naver")) {
					id = p.group(1);
				}
			}
			return id == null ? null : "https://rss.blog.naver.com/" + id + ".xml";
		}
		if (host.endsWith(".tistory.com")) {
			return "https://" + host + "/rss";
		}
		return null;
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
