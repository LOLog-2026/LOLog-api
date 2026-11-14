package com.lolog.api.blog;

import com.lolog.api.blog.BlogFetcher.FetchException;
import com.lolog.api.blog.FeedParser.Feed;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// 등록한 블로그를 주기적으로(기본 30분, texts.md 4장 "가져오는 때" 초안) 읽어 고른 카테고리의 새 글을 카드로 만든다.
// 블로그 하나를 읽지 못해도 다음 블로그는 계속 읽는다. 블로그 주소·글은 로그에 남기지 않는다.
@Component
public class BlogPoller {

	private static final Logger log = LoggerFactory.getLogger(BlogPoller.class);

	private final JdbcTemplate jdbc;
	private final BlogFetcher fetcher;
	private final BlogService blogs;
	private final ObjectMapper objectMapper;
	private final BlogPlatforms platforms;

	public BlogPoller(JdbcTemplate jdbc, BlogFetcher fetcher, BlogService blogs, ObjectMapper objectMapper,
			BlogPlatforms platforms) {
		this.platforms = platforms;
		this.jdbc = jdbc;
		this.fetcher = fetcher;
		this.blogs = blogs;
		this.objectMapper = objectMapper;
	}

	@Scheduled(initialDelayString = "${lolog.blog.poll-initial-delay-ms:300000}",
			fixedDelayString = "${lolog.blog.poll-delay-ms:1800000}")
	void pollAll() {
		List<Long> ids = jdbc.queryForList("select id from blog_sources order by id", Long.class);
		int added = 0;
		for (Long id : ids) {
			added += poll(id);
		}
		if (!ids.isEmpty()) {
			log.info("블로그 {}개를 읽어 새 글 {}개를 가져왔습니다.", ids.size(), added);
		}
	}

	// 블로그 하나를 읽는다. 그사이 연결을 끊었으면 넘어간다.
	public int poll(long sourceId) {
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select user_id, title, site_url, feed_url, platform, categories::text as categories from blog_sources where id = ?",
				sourceId);
		if (rows.isEmpty()) {
			return 0;
		}
		Map<String, Object> source = rows.getFirst();
		try {
			Feed feed = FeedParser.parse(fetcher.get((String) source.get("feed_url")).body());
			if (feed == null) {
				log.warn("블로그 {}의 피드를 읽지 못했습니다(RSS·Atom이 아님).", sourceId);
				return 0;
			}
			List<String> categories = objectMapper.readValue((String) source.get("categories"),
					new TypeReference<List<String>>() {
					});
			// 연결할 때 고른 플랫폼으로 읽는다. WordPress는 여기서 REST로 카테고리 목록을 받는다(트랜잭션 밖).
			BlogPlatform platform = platforms.get(Platform.valueOf((String) source.get("platform")));
			Categories rule = platform.categories(URI.create((String) source.get("site_url")), feed);
			return blogs.importIfConnected(sourceId, ((Number) source.get("user_id")).longValue(),
					(String) source.get("title"), categories, feed, platform, rule);
		}
		catch (FetchException e) {
			log.warn("블로그 {}의 피드를 받지 못했습니다: {}", sourceId, e.getMessage());
			return 0;
		}
		catch (RuntimeException e) {
			log.error("블로그 {}의 글을 가져오지 못했습니다.", sourceId, e);
			return 0;
		}
	}

}
