package com.lolog.api.blog;

import com.lolog.api.blog.BlogDtos.Blog;
import com.lolog.api.blog.BlogDtos.BlogCheck;
import com.lolog.api.blog.BlogDtos.BlogPost;
import com.lolog.api.blog.BlogDtos.BlogPostPage;
import com.lolog.api.blog.BlogFetcher.FetchException;
import com.lolog.api.blog.FeedLocator.Located;
import com.lolog.api.blog.FeedParser.Feed;
import com.lolog.api.blog.FeedParser.Item;
import com.lolog.api.common.ApiException;
import com.lolog.api.common.PageRequestParams;
import com.lolog.api.text.TextService;
import com.lolog.api.text.TextStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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

	private final JdbcTemplate jdbc;
	private final FeedLocator locator;
	private final TextService texts;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;

	public BlogService(JdbcTemplate jdbc, FeedLocator locator, TextService texts, ObjectMapper objectMapper,
			TransactionTemplate tx) {
		this.tx = tx;
		this.jdbc = jdbc;
		this.locator = locator;
		this.texts = texts;
		this.objectMapper = objectMapper;
	}

	// 링크 확인 버튼: RSS를 찾아 피드에 나온 카테고리를 돌려준다. 저장하지 않는다.
	public BlogCheck check(String url) {
		Located located = locate(url);
		List<String> categories = new ArrayList<>();
		for (Item item : located.feed().items()) {
			for (String category : item.categories()) {
				// 고를 수 있는 이름(1~30자)만 보여 준다.
				if (TextServiceLength.of(category) <= MAX_CATEGORY && !categories.contains(category)) {
					categories.add(category);
				}
			}
		}
		return new BlogCheck(located.feedUrl(), categories);
	}

	// 블로그 등록. 피드에 이미 있는 글(고른 카테고리)도 바로 카드로 만든다.
	// 피드를 받는 동안 DB 연결을 붙잡지 않게, 받은 뒤에 트랜잭션을 연다.
	public Blog create(long userId, String url, List<String> requested) {
		List<String> categories = categories(requested);
		String siteUrl = normalize(url);
		if (siteUrl.length() > MAX_URL) {
			throw ApiException.invalid("url", "주소는 " + MAX_URL + "자까지예요.");
		}
		Located located = locate(siteUrl);
		if (located.feedUrl().length() > MAX_URL) {
			throw invalidBlog("RSS 주소가 너무 깁니다.");
		}
		Boolean exists = jdbc.queryForObject("select exists (select 1 from blog_sources where user_id = ? and feed_url = ?)",
				Boolean.class, userId, located.feedUrl());
		if (Boolean.TRUE.equals(exists)) {
			throw exists();
		}
		String title = cut(located.feed().title(), MAX_BLOG_TITLE);
		return tx.execute(status -> save(userId, siteUrl, title, categories, located));
	}

	private Blog save(long userId, String siteUrl, String title, List<String> categories, Located located) {
		long id;
		try {
			id = jdbc.queryForObject("""
					insert into blog_sources (user_id, site_url, title, feed_url, categories) values (?, ?, ?, ?, ?::jsonb)
					returning id
					""", Long.class, userId, siteUrl, title, located.feedUrl(), objectMapper.writeValueAsString(categories));
		}
		catch (DuplicateKeyException e) {
			throw exists();
		}
		importPosts(id, userId, title, categories, located.feed());
		return new Blog(id, siteUrl, title, categories);
	}

	@Transactional(readOnly = true)
	public List<Blog> list(long userId) {
		return jdbc.query("select id, site_url, title, categories::text from blog_sources where user_id = ? order by id",
				(rs, n) -> new Blog(rs.getLong("id"), rs.getString("site_url"), rs.getString("title"),
						read(rs.getString("categories"))), userId);
	}

	// 가져올 카테고리를 바꾼다. 새로 고른 카테고리의 피드 글은 다음에 읽을 때 카드로 만든다.
	@Transactional
	public Blog update(long userId, long id, List<String> requested) {
		if (requested == null) {
			throw ApiException.invalid("categories", "categories가 필요해요.");
		}
		List<String> categories = categories(requested);
		int updated = jdbc.update("update blog_sources set categories = ?::jsonb where id = ? and user_id = ?",
				objectMapper.writeValueAsString(categories), id, userId);
		if (updated == 0) {
			throw notFound();
		}
		return jdbc.queryForObject("select id, site_url, title from blog_sources where id = ?",
				(rs, n) -> new Blog(rs.getLong("id"), rs.getString("site_url"), rs.getString("title"), categories), id);
	}

	// 연결 끊기: blog_sources 줄만 지운다. 가져온 글(blog_posts)은 source_id만 비워져 남고, 만든 카드도 남는다.
	@Transactional
	public void delete(long userId, long id) {
		if (jdbc.update("delete from blog_sources where id = ? and user_id = ?", id, userId) == 0) {
			throw notFound();
		}
	}

	// 가져온 글 목록. 최신 글(나중에 가져온 글)부터이고, 커서는 마지막 글의 ID다.
	@Transactional(readOnly = true)
	public BlogPostPage posts(long userId, long id, String cursor, Integer size) {
		PageRequestParams params = PageRequestParams.of(cursor, size);
		Boolean mine = jdbc.queryForObject("select exists (select 1 from blog_sources where id = ? and user_id = ?)",
				Boolean.class, id, userId);
		if (!Boolean.TRUE.equals(mine)) {
			throw notFound();
		}
		List<Row> rows = jdbc.query("""
				select p.id, p.title, p.link, p.category, p.published_at, t.id as text_id,
				       (select c.id from cards c where c.text_id = t.id order by c.id limit 1) as card_id,
				       case when t.id is null then null else %s end as status
				from blog_posts p left join texts t on t.blog_post_id = p.id
				where p.source_id = ? and (?::bigint is null or p.id < ?)
				order by p.id desc limit ?
				""".formatted(TextService.STATUS_SQL), (rs, n) -> new Row(rs.getLong("id"),
				new BlogPost(rs.getString("title"), rs.getString("link"), rs.getString("category"),
						rs.getObject("published_at", OffsetDateTime.class), rs.getObject("card_id", Long.class),
						rs.getString("status") == null ? null : TextStatus.valueOf(rs.getString("status")))),
				id, params.before(), params.before(), params.size() + 1);
		boolean more = rows.size() > params.size();
		List<Row> shown = more ? rows.subList(0, params.size()) : rows;
		return new BlogPostPage(shown.stream().map(Row::post).toList(),
				more ? String.valueOf(shown.getLast().id()) : null);
	}

	private record Row(long id, BlogPost post) {
	}

	// 피드의 글 중 고른 카테고리(비면 전부)의 새 글을 저장하고 카드 작업을 큐에 넣는다. 넣은 글 수를 돌려준다.
	// 오래된 글부터 넣어서 가져온 글 목록(ID 내림차순)이 최신 글부터 보이게 한다.
	@Transactional
	public int importPosts(long sourceId, long userId, String blogTitle, List<String> categories, Feed feed) {
		int added = 0;
		List<Item> items = new ArrayList<>(feed.items());
		// 피드는 보통 최신 글이 위라 먼저 뒤집고, 날짜가 있으면 날짜순으로 맞춘다(날짜 없는 글은 앞, 같은 날짜는 그대로).
		java.util.Collections.reverse(items);
		items.sort(java.util.Comparator.comparing(Item::publishedAt,
				java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())));
		for (Item item : items) {
			String category = matched(item, categories);
			if (category == null && !categories.isEmpty()) {
				continue;
			}
			// 링크나 구분 값이 없거나 너무 긴 글은 저장할 수 없어 건너뛴다.
			if (item.link() == null || item.guid() == null || item.link().length() > MAX_URL
					|| item.guid().length() > MAX_URL) {
				continue;
			}
			String title = item.title() == null ? "제목 없음" : cut(item.title(), MAX_TITLE);
			List<Long> inserted = jdbc.queryForList("""
					insert into blog_posts (source_id, user_id, guid, title, link, category, published_at, blog_title)
					values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (user_id, guid) do nothing returning id
					""", Long.class, sourceId, userId, item.guid(), title, item.link(), cut(category, MAX_POST_CATEGORY),
					item.publishedAt(), blogTitle);
			if (inserted.isEmpty()) {
				continue;
			}
			String body = FeedParser.toText(item.body());
			boolean truncated = TextServiceLength.of(body) > TextService.MAX_BODY;
			if (truncated) {
				body = TextServiceLength.head(body, TextService.MAX_BODY);
			}
			// 본문이 비어 있으면(제목만 있는 피드) 제목을 본문으로 쓴다.
			if (body.isBlank()) {
				body = title;
			}
			long textId = jdbc.queryForObject("""
					insert into texts (user_id, source, blog_post_id, title, body, truncated) values (?, 'BLOG', ?, ?, ?, ?)
					returning id
					""", Long.class, userId, inserted.getFirst(), title, body, truncated);
			texts.queue(textId, userId);
			added++;
		}
		return added;
	}

	// 주기적으로 읽을 때: 피드를 받는 사이 연결을 끊었으면 넣지 않는다. 블로그 줄을 잠가 끊기와 엇갈리지 않게 한다.
	@Transactional
	public int importIfConnected(long sourceId, long userId, String blogTitle, List<String> categories, Feed feed) {
		List<Long> locked = jdbc.queryForList("select id from blog_sources where id = ? for update", Long.class, sourceId);
		if (locked.isEmpty()) {
			return 0;
		}
		return importPosts(sourceId, userId, blogTitle, categories, feed);
	}

	// 글의 카테고리 중 고른 것과 같은 첫 이름. 고른 것이 없으면(전부 받음) 글의 첫 카테고리.
	private static String matched(Item item, List<String> categories) {
		if (categories.isEmpty()) {
			return item.categories().isEmpty() ? null : item.categories().getFirst();
		}
		return item.categories().stream().filter(categories::contains).findFirst().orElse(null);
	}

	// 1~30자, 앞뒤 공백을 뺀 값. 같은 이름(대소문자 무시)은 두 번 넣지 못한다.
	private static List<String> categories(List<String> requested) {
		if (requested == null) {
			return List.of();
		}
		List<String> result = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < requested.size(); i++) {
			String value = requested.get(i) == null ? "" : requested.get(i).strip();
			String at = "categories[" + i + "]";
			if (value.isEmpty() || TextServiceLength.of(value) > MAX_CATEGORY) {
				throw ApiException.invalid(at, "카테고리는 1~" + MAX_CATEGORY + "자예요.");
			}
			if (!seen.add(value.toLowerCase(Locale.ROOT))) {
				throw ApiException.invalid(at, "이미 있는 카테고리예요.");
			}
			result.add(value);
		}
		return result;
	}

	private Located locate(String url) {
		try {
			return locator.locate(normalize(url));
		}
		catch (FetchException e) {
			throw invalidBlog(e.getMessage());
		}
	}

	private static String normalize(String url) {
		try {
			return FeedLocator.normalize(url);
		}
		catch (FetchException e) {
			throw invalidBlog(e.getMessage());
		}
	}

	private List<String> read(String json) {
		return objectMapper.readValue(json, new TypeReference<List<String>>() {
		});
	}

	static String cut(String value, int max) {
		if (value == null) {
			return null;
		}
		String trimmed = value.strip();
		return TextServiceLength.of(trimmed) > max ? TextServiceLength.head(trimmed, max) : trimmed;
	}

	private static ApiException invalidBlog(String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_BLOG", message);
	}

	private static ApiException exists() {
		return new ApiException(HttpStatus.CONFLICT, "BLOG_EXISTS", "이미 연결한 블로그예요.");
	}

	static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "BLOG_NOT_FOUND", "블로그를 찾을 수 없어요.");
	}

	// 글자 수는 코드포인트로 센다(TextService와 같음).
	static final class TextServiceLength {

		private TextServiceLength() {
		}

		static int of(String value) {
			return value.codePointCount(0, value.length());
		}

		static String head(String value, int max) {
			return value.substring(0, value.offsetByCodePoints(0, max));
		}

	}

}
