package com.lolog.api.domain.blog.service;

import com.lolog.api.domain.blog.entity.BlogSource;
import com.lolog.api.domain.blog.platform.BlogPlatform;
import com.lolog.api.domain.blog.platform.BlogPlatforms;
import com.lolog.api.domain.blog.platform.Categories;
import com.lolog.api.domain.blog.repository.BlogSourceRepository;
import com.lolog.api.domain.blog.service.BlogFetcher.FetchException;
import com.lolog.api.domain.blog.service.FeedParser.Feed;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// 등록한 블로그를 주기적으로(기본 30분, texts.md 4장 "가져오는 때" 초안) 읽어 고른 카테고리의 새 글을 카드로 만든다.
// 블로그 하나를 읽지 못해도 다음 블로그는 계속 읽는다. 블로그 주소·글은 로그에 남기지 않는다.
@Component
public class BlogPoller {

	private static final Logger log = LoggerFactory.getLogger(BlogPoller.class);

	private final BlogSourceRepository sources;
	private final BlogFetcher fetcher;
	private final BlogService blogs;
	private final ObjectMapper objectMapper;
	private final BlogPlatforms platforms;

	/** 쓰는 저장소와 도구를 받는다. */
	public BlogPoller(BlogSourceRepository sources, BlogFetcher fetcher, BlogService blogs, ObjectMapper objectMapper,
			BlogPlatforms platforms) {
		this.platforms = platforms;
		this.sources = sources;
		this.fetcher = fetcher;
		this.blogs = blogs;
		this.objectMapper = objectMapper;
	}

	// 등록한 블로그를 모두 차례로 읽는다.
	@Scheduled(initialDelayString = "${lolog.blog.poll-initial-delay-ms:300000}",
			fixedDelayString = "${lolog.blog.poll-delay-ms:1800000}")
	void pollAll() {
		List<Long> ids = sources.findAllIds();
		int added = 0;
		for (Long id : ids) {
			added += poll(id);
		}
		if (!ids.isEmpty()) {
			log.info("블로그 {}개를 읽어 새 글 {}개를 가져왔습니다.", ids.size(), added);
		}
	}

	/**
	 * 블로그 하나를 읽는다. 그사이 연결을 끊었으면 넘어간다.
	 *
	 * @param sourceId 읽을 블로그 ID
	 * @return 새로 가져온 글 수. 읽지 못했으면 0
	 */
	public int poll(long sourceId) {
		BlogSource source = sources.findById(sourceId).orElse(null);
		if (source == null) {
			return 0;
		}
		try {
			Feed feed = FeedParser.parse(fetcher.get(source.getFeedUrl()).body());
			if (feed == null) {
				log.warn("블로그 {}의 피드를 읽지 못했습니다(RSS·Atom이 아님).", sourceId);
				return 0;
			}
			List<String> categories = objectMapper.readValue(source.getCategories(),
					new TypeReference<List<String>>() {
					});
			// 연결할 때 고른 플랫폼으로 읽는다. WordPress는 여기서 REST로 카테고리 목록을 받는다(트랜잭션 밖).
			BlogPlatform platform = platforms.get(source.getPlatform());
			Categories rule = platform.categories(URI.create(source.getSiteUrl()), feed);
			return blogs.importIfConnected(sourceId, source.getUserId(), source.getTitle(), categories, feed, platform,
					rule);
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
