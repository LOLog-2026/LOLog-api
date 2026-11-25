package com.lolog.api.domain.blog.service;

import com.lolog.api.domain.blog.dto.BlogDtos.Blog;
import com.lolog.api.domain.blog.dto.BlogDtos.BlogCheck;
import com.lolog.api.domain.blog.dto.BlogDtos.BlogPost;
import com.lolog.api.domain.blog.dto.BlogDtos.BlogPostPage;
import com.lolog.api.domain.blog.entity.BlogSource;
import com.lolog.api.domain.blog.platform.BlogPlatform;
import com.lolog.api.domain.blog.platform.BlogPlatforms;
import com.lolog.api.domain.blog.platform.Categories;
import com.lolog.api.domain.blog.platform.Platform;
import com.lolog.api.domain.blog.repository.BlogPostRepository;
import com.lolog.api.domain.blog.repository.BlogSourceRepository;
import com.lolog.api.domain.blog.service.BlogFetcher.FetchException;
import com.lolog.api.domain.blog.service.FeedLocator.Located;
import com.lolog.api.domain.blog.service.FeedParser.Feed;
import com.lolog.api.domain.blog.service.FeedParser.Item;
import com.lolog.api.domain.text.dto.TextStatus;
import com.lolog.api.domain.text.service.TextService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.web.PageRequestParams;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

// 블로그 연결과 글 가져오기(texts.md 4장, FR-TEXT-02·03).
// 고른 카테고리의 글은 가져오는 대로 글(texts)로 저장해 카드 작업을 큐에 넣는다. 같은 글은 (user_id, guid)로 한 번만 가져온다.
@Service
public class BlogService {

	static final int MAX_CATEGORY = 30;
	static final int MAX_URL = 500;
	static final int MAX_TITLE = 300;
	static final int MAX_BLOG_TITLE = 200;
	static final int MAX_POST_CATEGORY = 100;
	// 블로그 하나에 고르는 카테고리 수, 연결·카테고리 추가 때 카테고리마다 불러오는 최근 글 수(정함 11-14)
	static final int MAX_CATEGORIES = 3;
	static final int INITIAL_POSTS = 3;

	private final BlogSourceRepository sources;
	private final BlogPostRepository blogPosts;
	private final FeedLocator locator;
	private final TextService texts;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;
	private final BlogFetcher fetcher;
	private final BlogPlatforms platforms;

	/** 쓰는 저장소와 도구를 받는다. */
	public BlogService(BlogSourceRepository sources, BlogPostRepository blogPosts, FeedLocator locator,
			TextService texts, ObjectMapper objectMapper, TransactionTemplate tx, BlogFetcher fetcher,
			BlogPlatforms platforms) {
		this.fetcher = fetcher;
		this.platforms = platforms;
		this.tx = tx;
		this.sources = sources;
		this.blogPosts = blogPosts;
		this.locator = locator;
		this.texts = texts;
		this.objectMapper = objectMapper;
	}

	/**
	 * 링크 확인 버튼: RSS를 찾아 그 플랫폼 방식으로 읽은 카테고리를 돌려준다. 저장하지 않는다.
	 * Medium·velog는 카테고리를 고를 수 없어 빈 목록, WordPress는 REST로 받은 진짜 카테고리 전체다.
	 *
	 * @param url 사용자가 넣은 블로그 주소
	 * @return 찾은 RSS 주소, 고를 수 있는 카테고리, 따로 맞춘 플랫폼인지
	 */
	public BlogCheck check(String url) {
		String siteUrl = normalize(url);
		Located located = locate(siteUrl);
		Categories rule = located.platform().categories(URI.create(siteUrl), located.feed());
		List<String> categories = new ArrayList<>();
		for (String category : rule.options(located.feed())) {
			// 고를 수 있는 이름(1~30자)만 보여 준다.
			if (TextServiceLength.of(category) <= MAX_CATEGORY && !categories.contains(category)) {
				categories.add(category);
			}
		}
		return new BlogCheck(located.feedUrl(), categories, located.platform().platform().optimized());
	}

	/**
	 * 블로그 등록. 고른 카테고리마다 최근 글 3개를 바로 카드로 만든다(정함 11-14).
	 * 피드를 받는 동안 DB 연결을 붙잡지 않게, 받은 뒤에 트랜잭션을 연다.
	 *
	 * @param userId 연결하는 회원 ID(토큰의 sub)
	 * @param url 사용자가 넣은 블로그 주소
	 * @param requested 고른 카테고리(3개까지). null이거나 비면 전부 받기
	 * @return 연결한 블로그
	 */
	public Blog create(long userId, String url, List<String> requested) {
		List<String> categories = categories(requested);
		String siteUrl = normalize(url);
		if (siteUrl.length() > MAX_URL) {
			throw ApiException.invalid("url");
		}
		Located located = locate(siteUrl);
		if (located.feedUrl().length() > MAX_URL) {
			throw invalidBlog();
		}
		// WordPress는 여기서 REST로 카테고리 목록을 받아 온다. 트랜잭션 밖에서 받는다.
		Categories rule = located.platform().categories(URI.create(siteUrl), located.feed());
		requireSelectable(rule, categories);
		if (sources.existsByUserIdAndFeedUrl(userId, located.feedUrl())) {
			throw exists();
		}
		String title = cut(located.feed().title(), MAX_BLOG_TITLE);
		return tx.execute(status -> save(userId, siteUrl, title, categories, located, rule));
	}

	/**
	 * 블로그를 저장하고 처음 글을 가져온다.
	 *
	 * @param userId 연결하는 회원 ID
	 * @param siteUrl 정리한 블로그 주소
	 * @param title 블로그 이름(200자로 자름). 없으면 null
	 * @param categories 검사한 고른 카테고리
	 * @param located 찾은 RSS 주소, 피드, 플랫폼
	 * @param rule 이 피드의 카테고리 규칙
	 * @return 연결한 블로그
	 */
	private Blog save(long userId, String siteUrl, String title, List<String> categories, Located located,
			Categories rule) {
		Platform platform = located.platform().platform();
		long id;
		try {
			// 바로 넣어서(flush) 같은 피드를 동시에 연결하면 여기서 uk_blog_sources_user_id_feed_url에 걸리게 한다.
			id = sources.saveAndFlush(new BlogSource(userId, siteUrl, title, located.feedUrl(), platform,
					objectMapper.writeValueAsString(categories))).getId();
		}
		catch (DataIntegrityViolationException e) {
			throw exists();
		}
		// 고른 카테고리마다 최근 글 3개(안 골랐으면 블로그 전체에서 3개)만 카드로 만든다.
		importPosts(id, userId, title, categories, located.feed(), located.platform(), rule, categories);
		return new Blog(id, siteUrl, title, categories, platform.optimized());
	}

	/**
	 * 내 블로그 목록을 돌려준다.
	 *
	 * @param userId 요청한 회원 ID
	 * @return 연결한 순서대로 내 블로그
	 */
	@Transactional(readOnly = true)
	public List<Blog> list(long userId) {
		return sources.findAllByUserIdOrderById(userId).stream()
			.map(s -> new Blog(s.getId(), s.getSiteUrl(), s.getTitle(), read(s.getCategories()),
					s.getPlatform().optimized()))
			.toList();
	}

	/**
	 * 가져올 카테고리를 바꾼다. 새로 더한 카테고리마다 최근 글 3개를 바로 카드로 만든다(정함 11-14).
	 * 카테고리를 모두 빼서 "전부 받기"가 되면 블로그 전체에서 최근 3개다(초안). 빼기만 하면 피드를 읽지 않는다.
	 * 피드를 받는 동안 DB 연결을 붙잡지 않게, 받은 뒤에 트랜잭션을 연다. 피드를 받지 못하면 400 INVALID_BLOG이고 바꾸지 않는다(초안).
	 *
	 * @param userId 요청한 회원 ID
	 * @param id 바꿀 내 블로그 ID
	 * @param requested 새로 고른 카테고리 전체(3개까지). 비면 전부 받기, null이면 INVALID_REQUEST
	 * @return 바꾼 뒤의 블로그
	 */
	public Blog update(long userId, long id, List<String> requested) {
		if (requested == null) {
			throw ApiException.invalid("categories");
		}
		List<String> categories = categories(requested);
		BlogSource source = sources.findByIdAndUserId(id, userId).orElseThrow(BlogService::notFound);
		// 카테고리를 고를 수 없는지는 플랫폼만으로 정해진다(Medium·velog).
		if (!source.getPlatform().categorySelectable()) {
			requireSelectable(Categories.none(), categories);
		}
		List<String> added = added(read(source.getCategories()), categories);
		BlogPlatform platform = platforms.get(source.getPlatform());
		Feed feed = added == null ? null : feed(source.getFeedUrl());
		Categories rule = feed == null ? null : platform.categories(URI.create(source.getSiteUrl()), feed);
		return tx.execute(status -> {
			BlogSource locked = sources.findByIdAndUserIdForUpdate(id, userId).orElseThrow(BlogService::notFound);
			locked.changeCategories(objectMapper.writeValueAsString(categories));
			if (feed != null) {
				importPosts(id, userId, locked.getTitle(), categories, feed, platform, rule, added);
			}
			return new Blog(id, locked.getSiteUrl(), locked.getTitle(), categories, locked.getPlatform().optimized());
		});
	}

	/**
	 * 새로 더한 카테고리. 없으면 null(피드를 읽지 않음). 카테고리를 모두 빼서 전부 받기가 되면 빈 목록(블로그 전체).
	 *
	 * @param before 바꾸기 전 카테고리
	 * @param after 바꾼 뒤 카테고리
	 * @return 새로 더한 카테고리. 없으면 null, 전부 받기가 되면 빈 목록
	 */
	static List<String> added(List<String> before, List<String> after) {
		if (after.isEmpty()) {
			return before.isEmpty() ? null : List.of();
		}
		if (before.isEmpty()) {
			// 전부 받던 블로그에 카테고리를 고르면 고른 카테고리를 모두 새로 더한 것으로 본다(초안).
			return after;
		}
		List<String> added = after.stream().filter(c -> !before.contains(c)).toList();
		return added.isEmpty() ? null : added;
	}

	/**
	 * 피드를 받아 읽는다. 못 읽으면 INVALID_BLOG.
	 *
	 * @param feedUrl RSS 주소
	 * @return 읽은 피드
	 */
	private Feed feed(String feedUrl) {
		try {
			Feed feed = FeedParser.parse(fetcher.get(feedUrl).body());
			if (feed == null) {
				throw invalidBlog();
			}
			return feed;
		}
		catch (FetchException e) {
			throw invalidBlog();
		}
	}

	/**
	 * 연결 끊기: blog_sources 줄만 지운다. 가져온 글(blog_posts)은 source_id만 비워져 남고, 만든 카드도 남는다.
	 *
	 * @param userId 요청한 회원 ID
	 * @param id 끊을 내 블로그 ID
	 */
	@Transactional
	public void delete(long userId, long id) {
		if (sources.deleteMine(id, userId) == 0) {
			throw notFound();
		}
	}

	/**
	 * 가져온 글 목록. 최신 글(나중에 가져온 글)부터이고, 커서는 마지막 글의 ID다.
	 *
	 * @param userId 요청한 회원 ID
	 * @param id 글을 볼 내 블로그 ID
	 * @param cursor 앞 페이지에서 받은 nextCursor. null이면 처음부터
	 * @param size 한 페이지 글 수(1~50). null이면 20
	 * @return 글 목록 한 페이지와 다음 cursor(마지막 페이지면 null)
	 */
	@Transactional(readOnly = true)
	public BlogPostPage posts(long userId, long id, String cursor, Integer size) {
		PageRequestParams params = PageRequestParams.of(cursor, size);
		if (!sources.existsByIdAndUserId(id, userId)) {
			throw notFound();
		}
		List<Row> rows = blogPosts.findPage(id, params.before(), Limit.of(params.size() + 1))
			.stream()
			.map(p -> new Row(p.id(), new BlogPost(p.title(), p.link(), p.category(), p.publishedAt(), p.cardId(),
					p.textId() == null ? null : TextStatus.valueOf(p.status()))))
			.toList();
		boolean more = rows.size() > params.size();
		List<Row> shown = more ? rows.subList(0, params.size()) : rows;
		return new BlogPostPage(shown.stream().map(Row::post).toList(),
				more ? String.valueOf(shown.getLast().id()) : null);
	}

	/**
	 * 글 목록 한 줄과 커서로 쓸 ID.
	 *
	 * @param id 가져온 글(blog_posts) ID. 커서로 쓴다
	 * @param post 응답에 넣을 글 한 줄
	 */
	private record Row(long id, BlogPost post) {
	}

	/**
	 * 피드의 글 중 고른 카테고리(비면 전부)의 글을 저장하고 카드 작업을 큐에 넣는다. 넣은 글 수를 돌려준다.
	 * 오래된 글부터 넣어서 가져온 글 목록(ID 내림차순)이 최신 글부터 보이게 한다.
	 * initial이 null이면 주기적으로 읽을 때라 처음 보는 글을 모두 넣는다.
	 * initial이 있으면 연결·카테고리 추가 때라(texts.md 4장 "가져오는 때", 정함 11-14) 그 카테고리마다 최근 글 3개만 카드로 만들고,
	 * 더 옛 글은 다음에 새 글로 잡히지 않게 guid만 적어 둔다(skipped). initial이 빈 목록이면 블로그 전체에서 최근 3개다.
	 *
	 * @param sourceId 글을 가져올 블로그 ID
	 * @param userId 블로그 주인 회원 ID
	 * @param blogTitle 글에 복사해 둘 블로그 이름
	 * @param categories 고른 카테고리 전체. 비면 전부 받기
	 * @param feed 읽은 피드
	 * @param platform 본문 칸을 고를 플랫폼 구현
	 * @param rule 이 피드의 카테고리 규칙
	 * @param initial 이번에 새로 더한 카테고리. 주기적으로 읽을 때는 null
	 * @return 새로 넣어 카드 작업을 맡긴 글 수
	 */
	@Transactional
	public int importPosts(long sourceId, long userId, String blogTitle, List<String> categories, Feed feed,
			BlogPlatform platform, Categories rule, List<String> initial) {
		int added = 0;
		List<Item> items = new ArrayList<>();
		for (Item item : feed.items()) {
			// 링크나 구분 값이 없거나 너무 긴 글은 저장할 수 없어 건너뛴다.
			if (item.link() != null && item.guid() != null && item.link().length() <= MAX_URL
					&& item.guid().length() <= MAX_URL) {
				items.add(item);
			}
		}
		// 피드는 보통 최신 글이 위라 먼저 뒤집고, 날짜가 있으면 날짜순으로 맞춘다(날짜 없는 글은 앞, 같은 날짜는 그대로).
		java.util.Collections.reverse(items);
		items.sort(java.util.Comparator.comparing(Item::publishedAt,
				java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
		Set<Item> recent = initial == null ? null : recent(items, initial, rule);
		Set<Item> scope = initial == null ? null : inScope(items, initial, rule);
		for (Item item : items) {
			String category = rule.matched(item, categories);
			if (category == null && !categories.isEmpty()) {
				continue;
			}
			if (scope != null && !scope.contains(item)) {
				// 이번에 더한 카테고리가 아닌 글(이미 고른 카테고리의 글)은 주기적으로 읽을 때처럼 두지 않고 건드리지 않는다.
				continue;
			}
			if (recent != null && !recent.contains(item)) {
				blogPosts.insertSkipped(sourceId, userId, item.guid());
				continue;
			}
			String title = item.title() == null ? "제목 없음" : cut(item.title(), MAX_TITLE);
			// 연결·추가 때는 예전에 옛 글로 적어 둔 글(skipped)이 이번 최근 3개에 들면 가져온다. 주기적으로 읽을 때는 건드리지 않는다.
			String postCategory = cut(category, MAX_POST_CATEGORY);
			Optional<Long> inserted = initial == null
					? blogPosts.insertNew(sourceId, userId, item.guid(), title, item.link(), postCategory,
							item.publishedAt(), blogTitle)
					: blogPosts.insertOrRevive(sourceId, userId, item.guid(), title, item.link(), postCategory,
							item.publishedAt(), blogTitle);
			if (inserted.isEmpty()) {
				continue;
			}
			String body = FeedParser.toText(platform.body(item));
			boolean truncated = TextServiceLength.of(body) > TextService.MAX_BODY;
			if (truncated) {
				body = TextServiceLength.head(body, TextService.MAX_BODY);
			}
			// 본문이 비어 있으면(제목만 있는 피드) 제목을 본문으로 쓴다.
			if (body.isBlank()) {
				body = title;
			}
			texts.createFromBlog(userId, inserted.get(), title, body, truncated);
			added++;
		}
		return added;
	}

	/**
	 * 주기적으로 읽을 때: 피드를 받는 사이 연결을 끊었으면 넣지 않는다. 블로그 줄을 잠가 끊기와 엇갈리지 않게 한다.
	 *
	 * @param sourceId 글을 가져올 블로그 ID
	 * @param userId 블로그 주인 회원 ID
	 * @param blogTitle 글에 복사해 둘 블로그 이름
	 * @param categories 고른 카테고리 전체. 비면 전부 받기
	 * @param feed 읽은 피드
	 * @param platform 본문 칸을 고를 플랫폼 구현
	 * @param rule 이 피드의 카테고리 규칙
	 * @return 새로 넣은 글 수. 그사이 연결을 끊었으면 0
	 */
	@Transactional
	public int importIfConnected(long sourceId, long userId, String blogTitle, List<String> categories, Feed feed,
			BlogPlatform platform, Categories rule) {
		if (sources.findByIdForUpdate(sourceId).isEmpty()) {
			return 0;
		}
		return importPosts(sourceId, userId, blogTitle, categories, feed, platform, rule, null);
	}

	/**
	 * 카테고리마다(initial이 비면 블로그 전체에서) 최근 글 3개. items는 오래된 글부터다. 두 카테고리에 든 글은 한 번이다.
	 *
	 * @param items 오래된 글부터 정렬한 피드 글
	 * @param initial 이번에 새로 더한 카테고리. 비면 블로그 전체
	 * @param rule 이 피드의 카테고리 규칙
	 * @return 카드로 만들 최근 글(객체 동일성으로 비교하는 집합)
	 */
	static Set<Item> recent(List<Item> items, List<String> initial, Categories rule) {
		Set<Item> result = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		List<Item> newest = new ArrayList<>(items);
		java.util.Collections.reverse(newest);
		if (initial.isEmpty()) {
			newest.stream().limit(INITIAL_POSTS).forEach(result::add);
			return result;
		}
		for (String category : initial) {
			newest.stream()
				.filter(item -> rule.matched(item, List.of(category)) != null)
				.limit(INITIAL_POSTS)
				.forEach(result::add);
		}
		return result;
	}

	/**
	 * 이번에 불러올 범위: initial 카테고리 중 하나에 드는 글(initial이 비면 전부)
	 *
	 * @param items 피드 글
	 * @param initial 이번에 새로 더한 카테고리. 비면 전부
	 * @param rule 이 피드의 카테고리 규칙
	 * @return 범위에 드는 글(객체 동일성으로 비교하는 집합)
	 */
	static Set<Item> inScope(List<Item> items, List<String> initial, Categories rule) {
		Set<Item> result = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		for (Item item : items) {
			if (initial.isEmpty() || rule.matched(item, initial) != null) {
				result.add(item);
			}
		}
		return result;
	}

	/**
	 * Medium·velog처럼 카테고리가 없는 블로그에 카테고리를 고르면 400 CATEGORY_NOT_SELECTABLE(field categories)로 거절한다
	 * (texts.md 4장 "플랫폼별 카테고리").
	 *
	 * @param rule 이 블로그의 카테고리 규칙
	 * @param categories 고른 카테고리
	 */
	private static void requireSelectable(Categories rule, List<String> categories) {
		if (!rule.selectable() && !categories.isEmpty()) {
			throw new ApiException(ErrorCode.CATEGORY_NOT_SELECTABLE, "categories");
		}
	}

	/**
	 * 1~30자, 앞뒤 공백을 뺀 값. 같은 이름(대소문자 무시)은 두 번 넣지 못한다. 3개까지다(정함 11-14, 넘으면 400은 초안).
	 *
	 * @param requested 요청에 온 카테고리. null이면 빈 목록
	 * @return 앞뒤 공백을 뺀 카테고리
	 */
	private static List<String> categories(List<String> requested) {
		if (requested == null) {
			return List.of();
		}
		if (requested.size() > MAX_CATEGORIES) {
			throw ApiException.invalid("categories");
		}
		List<String> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < requested.size(); i++) {
			String value = requested.get(i) == null ? "" : requested.get(i).strip();
			String at = "categories[" + i + "]";
			if (value.isEmpty() || TextServiceLength.of(value) > MAX_CATEGORY) {
				throw ApiException.invalid(at);
			}
			if (!seen.add(value.toLowerCase(Locale.ROOT))) {
				throw ApiException.invalid(at);
			}
			result.add(value);
		}
		return result;
	}

	/**
	 * url은 normalize한 주소
	 *
	 * @param url normalize한 블로그 주소
	 * @return 찾은 RSS 주소, 피드, 플랫폼. 못 찾으면 INVALID_BLOG
	 */
	private Located locate(String url) {
		try {
			return locator.locate(url);
		}
		catch (FetchException e) {
			throw invalidBlog();
		}
	}

	/**
	 * 빠지거나 빈 주소는 다른 빠진 칸처럼 400 INVALID_REQUEST(field url)이다(api-dto.md). 주소 모양이 틀리면 INVALID_BLOG.
	 *
	 * @param url 사용자가 넣은 블로그 주소
	 * @return 앞뒤 공백을 빼고 https를 붙인 주소
	 */
	private static String normalize(String url) {
		if (url == null || url.isBlank()) {
			throw ApiException.invalid("url");
		}
		try {
			return FeedLocator.normalize(url);
		}
		catch (FetchException e) {
			throw invalidBlog();
		}
	}

	/**
	 * JSON 카테고리 목록을 읽는다.
	 *
	 * @param json blog_sources.categories의 JSON 배열
	 * @return 카테고리 목록
	 */
	private List<String> read(String json) {
		return objectMapper.readValue(json, new TypeReference<List<String>>() {
		});
	}

	/**
	 * 앞뒤 공백을 빼고 max자를 넘으면 자른다.
	 *
	 * @param value 다듬을 값. null이면 null을 돌려준다
	 * @param max 최대 글자 수(코드포인트 기준)
	 * @return 다듬은 값
	 */
	static String cut(String value, int max) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		return TextServiceLength.of(trimmed) > max ? TextServiceLength.head(trimmed, max) : trimmed;
	}

	// INVALID_BLOG 예외를 만든다.
	private static ApiException invalidBlog() {
		return new ApiException(ErrorCode.INVALID_BLOG);
	}

	// BLOG_EXISTS 예외를 만든다.
	private static ApiException exists() {
		return new ApiException(ErrorCode.BLOG_EXISTS);
	}

	// BLOG_NOT_FOUND 예외를 만든다.
	static ApiException notFound() {
		return new ApiException(ErrorCode.BLOG_NOT_FOUND);
	}

	// 글자 수는 코드포인트로 센다(TextService와 같음).
	static final class TextServiceLength {

		private TextServiceLength() {
		}

		/**
		 * 글자 수를 센다.
		 *
		 * @param value 셀 글자
		 * @return 코드포인트 수
		 */
		static int of(String value) {
			return value.codePointCount(0, value.length());
		}

		/**
		 * 앞에서 max자만 남긴다.
		 *
		 * @param value 자를 글자
		 * @param max 남길 글자 수(코드포인트 기준)
		 * @return 앞 max자
		 */
		static String head(String value, int max) {
			return value.substring(0, value.offsetByCodePoints(0, max));
		}

	}

}
