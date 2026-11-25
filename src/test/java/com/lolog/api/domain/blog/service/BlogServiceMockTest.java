package com.lolog.api.domain.blog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lolog.api.domain.blog.dto.BlogDtos.Blog;
import com.lolog.api.domain.blog.entity.BlogSource;
import com.lolog.api.domain.blog.platform.BlogPlatform;
import com.lolog.api.domain.blog.platform.BlogPlatforms;
import com.lolog.api.domain.blog.platform.Categories;
import com.lolog.api.domain.blog.platform.Platform;
import com.lolog.api.domain.blog.repository.BlogPostRepository;
import com.lolog.api.domain.blog.repository.BlogSourceRepository;
import com.lolog.api.domain.blog.service.BlogFetcher.FetchException;
import com.lolog.api.domain.blog.service.BlogFetcher.Fetched;
import com.lolog.api.domain.blog.service.FeedLocator.Located;
import com.lolog.api.domain.blog.service.FeedParser.Feed;
import com.lolog.api.domain.blog.service.FeedParser.Item;
import com.lolog.api.domain.text.service.TextService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@DisplayName("BlogService")
@ExtendWith(MockitoExtension.class)
class BlogServiceMockTest {

	private static final long USER = 7L;
	private static final long SOURCE = 30L;
	private static final String SITE = "https://blog.example";
	private static final String FEED_URL = "https://blog.example/rss";
	private static final OffsetDateTime BASE = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);

	@Mock
	BlogSourceRepository sources;
	@Mock
	BlogPostRepository blogPosts;
	@Mock
	FeedLocator locator;
	@Mock
	TextService texts;
	@Mock
	TransactionTemplate tx;
	@Mock
	BlogFetcher fetcher;
	@Mock
	BlogPlatforms platforms;

	private final ObjectMapper objectMapper = new ObjectMapper();
	private BlogService service;

	@BeforeEach
	void setUp() {
		service = new BlogService(sources, blogPosts, locator, texts, objectMapper, tx, fetcher, platforms);
		// 트랜잭션 콜백은 바로 실행한다.
		lenient().when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
	}

	// ---- create: 입력 검사 ----

	@Test
	@DisplayName("주소가 비어 있으면 피드를 찾지 않고 url이 틀린 것이다")
	void 빈_주소는_INVALID다() {
		// when & then
		assertError(() -> service.create(USER, " ", null), ErrorCode.INVALID_REQUEST, "url");
		verify(locator, never()).locate(anyString());
	}

	@Test
	@DisplayName("카테고리를 4개 보내면 categories가 틀린 것이다")
	void 카테고리_4개는_INVALID다() {
		// when & then
		assertError(() -> service.create(USER, SITE, List.of("a", "b", "c", "d")), ErrorCode.INVALID_REQUEST,
				"categories");
	}

	@Test
	@DisplayName("대소문자만 다른 카테고리는 같은 것으로 보고 두 번째가 틀린 것이다")
	void 대소문자만_다른_카테고리는_중복이다() {
		// when & then
		assertError(() -> service.create(USER, SITE, List.of("Dev", "dev")), ErrorCode.INVALID_REQUEST,
				"categories[1]");
	}

	@Test
	@DisplayName("빈 카테고리나 30자를 넘는 카테고리는 그 자리가 틀린 것이다")
	void 빈_카테고리와_긴_카테고리는_INVALID다() {
		// when & then
		assertError(() -> service.create(USER, SITE, Arrays.asList("개발", null)), ErrorCode.INVALID_REQUEST,
				"categories[1]");
		assertError(() -> service.create(USER, SITE, List.of("가".repeat(31))), ErrorCode.INVALID_REQUEST,
				"categories[0]");
	}

	@Test
	@DisplayName("주소가 500자를 넘으면 피드를 찾지 않고 url이 틀린 것이다")
	void 긴_주소는_INVALID다() {
		// when & then
		assertError(() -> service.create(USER, SITE + "/" + "a".repeat(500), null), ErrorCode.INVALID_REQUEST,
				"url");
		verify(locator, never()).locate(anyString());
	}

	@Test
	@DisplayName("피드를 찾지 못하면 INVALID_BLOG다")
	void 피드를_못_찾으면_INVALID_BLOG다() {
		// when
		when(locator.locate(SITE)).thenThrow(new FetchException("없음"));

		// then
		assertError(() -> service.create(USER, SITE, null), ErrorCode.INVALID_BLOG, null);
	}

	@Test
	@DisplayName("찾은 피드 주소가 500자를 넘으면 INVALID_BLOG다")
	void 피드_주소가_길면_INVALID_BLOG다() {
		// when
		when(locator.locate(SITE)).thenReturn(new Located(SITE + "/" + "a".repeat(500), feed(), plain()));

		// then
		assertError(() -> service.create(USER, SITE, null), ErrorCode.INVALID_BLOG, null);
	}

	@Test
	@DisplayName("카테고리를 고를 수 없는 블로그에 카테고리를 보내면 CATEGORY_NOT_SELECTABLE이다")
	void 고를_수_없는_블로그에_카테고리를_보내면_거절한다() {
		// when
		when(locator.locate(SITE)).thenReturn(new Located(FEED_URL, feed(), platform(Platform.VELOG,
				Categories.none())));

		// then
		assertError(() -> service.create(USER, SITE, List.of("개발")), ErrorCode.CATEGORY_NOT_SELECTABLE,
				"categories");
		verify(sources, never()).existsByUserIdAndFeedUrl(anyLong(), anyString());
	}

	@Test
	@DisplayName("같은 피드를 이미 연결했으면 트랜잭션을 열지 않고 BLOG_EXISTS다")
	void 이미_연결한_피드는_BLOG_EXISTS다() {
		// when
		when(locator.locate(SITE)).thenReturn(new Located(FEED_URL, feed(), plain()));
		when(sources.existsByUserIdAndFeedUrl(USER, FEED_URL)).thenReturn(true);

		// then
		assertError(() -> service.create(USER, SITE, null), ErrorCode.BLOG_EXISTS, null);
		verify(tx, never()).execute(any());
	}

	@Test
	@DisplayName("동시에 연결해 유일 키에 걸리면 BLOG_EXISTS다")
	void 동시_연결은_BLOG_EXISTS다() {
		// when
		when(locator.locate(SITE)).thenReturn(new Located(FEED_URL, feed(), plain()));
		when(sources.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk"));

		// then
		assertError(() -> service.create(USER, SITE, null), ErrorCode.BLOG_EXISTS, null);
		verify(blogPosts, never()).insertOrRevive(anyLong(), anyLong(), anyString(), anyString(), anyString(), any(),
				any(), any());
	}

	// ---- create: 저장 ----

	@Test
	@DisplayName("연결하면 https를 붙인 주소·자른 제목·카테고리 JSON으로 저장하고 고른 카테고리의 최근 글을 가져온다")
	void 연결하면_저장하고_최근_글을_가져온다() {
		// when
		Item dev = item("g1", 1, "개발");
		Item life = item("g2", 2, "일상");
		Feed feed = new Feed("  " + "제".repeat(201), SITE, null, List.of(life, dev));
		when(locator.locate(SITE)).thenReturn(new Located(FEED_URL, feed, plain()));
		when(sources.saveAndFlush(any())).thenAnswer(inv -> withId(inv.getArgument(0), SOURCE));
		when(blogPosts.insertOrRevive(eq(SOURCE), eq(USER), eq("g1"), any(), any(), any(), any(), any()))
			.thenReturn(Optional.of(500L));

		// then
		Blog blog = service.create(USER, "blog.example", List.of(" 개발 "));

		ArgumentCaptor<BlogSource> saved = ArgumentCaptor.forClass(BlogSource.class);
		verify(sources).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getSiteUrl()).isEqualTo(SITE);
		assertThat(saved.getValue().getTitle()).isEqualTo("제".repeat(200));
		assertThat(saved.getValue().getCategories()).isEqualTo("[\"개발\"]");
		assertThat(saved.getValue().getPlatform()).isEqualTo(Platform.NAVER);
		assertThat(blog.id()).isEqualTo(SOURCE);
		assertThat(blog.categories()).containsExactly("개발");
		verify(blogPosts, never()).insertOrRevive(anyLong(), anyLong(), eq("g2"), any(), any(), any(), any(), any());
		verify(texts).createFromBlog(eq(USER), eq(500L), eq("g1"), anyString(), eq(false));
	}

	// ---- update ----

	@Test
	@DisplayName("categories를 빼고 보내면 categories가 틀린 것이다")
	void 카테고리_null은_INVALID다() {
		// when & then
		assertError(() -> service.update(USER, SOURCE, null), ErrorCode.INVALID_REQUEST, "categories");
		verify(sources, never()).findByIdAndUserId(anyLong(), anyLong());
	}

	@Test
	@DisplayName("내 블로그가 아니면 BLOG_NOT_FOUND다")
	void 없는_블로그는_BLOG_NOT_FOUND다() {
		// given
		when(sources.findByIdAndUserId(SOURCE, USER)).thenReturn(Optional.empty());

		// when & then
		assertError(() -> service.update(USER, SOURCE, List.of()), ErrorCode.BLOG_NOT_FOUND, null);
	}

	@Test
	@DisplayName("velog 블로그에 카테고리를 고르면 CATEGORY_NOT_SELECTABLE이다")
	void velog에_카테고리를_고르면_거절한다() {
		// given
		when(sources.findByIdAndUserId(SOURCE, USER)).thenReturn(Optional.of(source(Platform.VELOG, "[]")));

		// when & then
		assertError(() -> service.update(USER, SOURCE, List.of("개발")), ErrorCode.CATEGORY_NOT_SELECTABLE,
				"categories");
	}

	@Test
	@DisplayName("새로 더한 카테고리가 없으면 피드를 받지 않고 카테고리만 바꾼다")
	void 더한_카테고리가_없으면_피드를_받지_않는다() {
		// given
		BlogSource source = source(Platform.NAVER, "[\"개발\",\"일상\"]");
		when(sources.findByIdAndUserId(SOURCE, USER)).thenReturn(Optional.of(source));
		when(sources.findByIdAndUserIdForUpdate(SOURCE, USER)).thenReturn(Optional.of(source));

		// when
		Blog blog = service.update(USER, SOURCE, List.of("개발"));

		// then
		verify(fetcher, never()).get(anyString());
		assertThat(source.getCategories()).isEqualTo("[\"개발\"]");
		assertThat(blog.categories()).containsExactly("개발");
	}

	@Test
	@DisplayName("더한 카테고리가 있는데 피드를 받지 못하면 트랜잭션을 열지 않고 INVALID_BLOG다")
	void 피드를_못_받으면_INVALID_BLOG다() {
		// given
		when(sources.findByIdAndUserId(SOURCE, USER)).thenReturn(Optional.of(source(Platform.NAVER, "[\"개발\"]")));
		when(platforms.get(Platform.NAVER)).thenReturn(plain());
		when(fetcher.get(FEED_URL)).thenThrow(new FetchException("끊김"));

		// when & then
		assertError(() -> service.update(USER, SOURCE, List.of("개발", "일상")), ErrorCode.INVALID_BLOG, null);
		verify(tx, never()).execute(any());
	}

	@Test
	@DisplayName("받은 것이 피드가 아니면 INVALID_BLOG다")
	void 피드가_아니면_INVALID_BLOG다() {
		// given
		when(sources.findByIdAndUserId(SOURCE, USER)).thenReturn(Optional.of(source(Platform.NAVER, "[\"개발\"]")));
		when(platforms.get(Platform.NAVER)).thenReturn(plain());
		when(fetcher.get(FEED_URL)).thenReturn(fetched("<html></html>"));

		// when & then
		assertError(() -> service.update(USER, SOURCE, List.of("개발", "일상")), ErrorCode.INVALID_BLOG, null);
	}

	@Test
	@DisplayName("트랜잭션 안에서 잠가 읽을 때 지워졌으면 BLOG_NOT_FOUND다")
	void 잠글_때_지워졌으면_BLOG_NOT_FOUND다() {
		// given
		when(sources.findByIdAndUserId(SOURCE, USER)).thenReturn(Optional.of(source(Platform.NAVER, "[]")));
		when(sources.findByIdAndUserIdForUpdate(SOURCE, USER)).thenReturn(Optional.empty());

		// when & then
		assertError(() -> service.update(USER, SOURCE, List.of()), ErrorCode.BLOG_NOT_FOUND, null);
	}

	@Test
	@DisplayName("카테고리를 더하면 더한 카테고리의 글만 가져오고 이미 고른 카테고리의 글은 건드리지 않는다")
	void 더한_카테고리의_글만_가져온다() {
		// given
		BlogSource source = source(Platform.NAVER, "[\"개발\"]");
		when(sources.findByIdAndUserId(SOURCE, USER)).thenReturn(Optional.of(source));
		when(sources.findByIdAndUserIdForUpdate(SOURCE, USER)).thenReturn(Optional.of(source));
		when(platforms.get(Platform.NAVER)).thenReturn(plain());
		when(fetcher.get(FEED_URL)).thenReturn(fetched(rss(rssItem("dev1", "개발"), rssItem("life1", "일상"))));
		when(blogPosts.insertOrRevive(eq(SOURCE), eq(USER), eq("life1"), any(), any(), any(), any(), any()))
			.thenReturn(Optional.of(501L));

		// when
		service.update(USER, SOURCE, List.of("개발", "일상"));

		// then
		verify(blogPosts, never()).insertOrRevive(anyLong(), anyLong(), eq("dev1"), any(), any(), any(), any(), any());
		verify(blogPosts, never()).insertSkipped(anyLong(), anyLong(), eq("dev1"));
		verify(texts).createFromBlog(eq(USER), eq(501L), eq("life1"), anyString(), eq(false));
		assertThat(source.getCategories()).isEqualTo("[\"개발\",\"일상\"]");
	}

	// ---- importPosts ----

	@Test
	@DisplayName("링크나 guid가 없거나 500자를 넘는 글은 건너뛴다")
	void 저장할_수_없는_글은_건너뛴다() {
		// given
		Item noLink = new Item("g1", "t", null, "본문", null, BASE, List.of());
		Item noGuid = new Item(null, "t", "https://x/1", "본문", null, BASE, List.of());
		Item longLink = new Item("g3", "t", "https://x/" + "a".repeat(500), "본문", null, BASE, List.of());

		// when
		int added = service.importPosts(SOURCE, USER, "블로그", List.of(), new Feed("t", SITE, null,
				List.of(noLink, noGuid, longLink)), plain(), Categories.plain(), null);

		// then
		assertThat(added).isZero();
		verify(blogPosts, never()).insertNew(anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("주기적으로 읽을 때는 insertNew로 넣고, 고른 카테고리가 아닌 글과 이미 있는 글은 글을 만들지 않는다")
	void 주기적으로_읽을_때는_새_글만_만든다() {
		// given
		Item dev = item("dev", 1, "개발");
		Item life = item("life", 2, "일상");
		Item old = item("old", 3, "개발");
		when(blogPosts.insertNew(eq(SOURCE), eq(USER), eq("dev"), any(), any(), eq("개발"), any(), eq("블로그")))
			.thenReturn(Optional.of(600L));
		when(blogPosts.insertNew(eq(SOURCE), eq(USER), eq("old"), any(), any(), any(), any(), any()))
			.thenReturn(Optional.empty());

		// when
		int added = service.importPosts(SOURCE, USER, "블로그", List.of("개발"), new Feed("t", SITE, null,
				List.of(old, life, dev)), plain(), Categories.plain(), null);

		// then
		assertThat(added).isEqualTo(1);
		verify(blogPosts, never()).insertNew(anyLong(), anyLong(), eq("life"), any(), any(), any(), any(), any());
		verify(blogPosts, never()).insertSkipped(anyLong(), anyLong(), anyString());
		verify(blogPosts, never()).insertOrRevive(anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
		verify(texts).createFromBlog(eq(USER), eq(600L), eq("dev"), anyString(), eq(false));
	}

	@Test
	@DisplayName("처음 가져올 때 최근 3개보다 오래된 글은 건너뛴 글로만 적는다")
	void 처음_가져올_때_오래된_글은_skipped로_적는다() {
		// given
		List<Item> items = new ArrayList<>();
		for (int day = 1; day <= 4; day++) {
			items.add(item("p" + day, day));
		}
		when(blogPosts.insertOrRevive(eq(SOURCE), eq(USER), anyString(), any(), any(), any(), any(), any()))
			.thenReturn(Optional.of(700L));

		// when
		int added = service.importPosts(SOURCE, USER, "블로그", List.of(), new Feed("t", SITE, null, items), plain(),
				Categories.plain(), List.of());

		// then
		assertThat(added).isEqualTo(3);
		verify(blogPosts).insertSkipped(SOURCE, USER, "p1");
		verify(blogPosts, never()).insertOrRevive(anyLong(), anyLong(), eq("p1"), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("제목이 없으면 \"제목 없음\"이고, 본문이 비면 제목을 본문으로 쓴다")
	void 제목_없음과_빈_본문() {
		// given
		Item item = new Item("g", null, "https://x/1", null, null, BASE, List.of());
		when(blogPosts.insertNew(anyLong(), anyLong(), eq("g"), eq("제목 없음"), any(), isNull(), any(), any()))
			.thenReturn(Optional.of(800L));

		// when
		service.importPosts(SOURCE, USER, "블로그", List.of(), new Feed("t", SITE, null, List.of(item)), plain(),
				Categories.plain(), null);

		// then
		verify(texts).createFromBlog(USER, 800L, "제목 없음", "제목 없음", false);
	}

	@Test
	@DisplayName("본문이 2000자를 넘으면 2000자로 자르고 잘렸다고 알린다")
	void 긴_본문은_잘라서_만든다() {
		// given
		Item item = new Item("g", "제목", "https://x/1", "가".repeat(2001), null, BASE, List.of());
		when(blogPosts.insertNew(anyLong(), anyLong(), eq("g"), any(), any(), any(), any(), any()))
			.thenReturn(Optional.of(800L));

		// when
		service.importPosts(SOURCE, USER, "블로그", List.of(), new Feed("t", SITE, null, List.of(item)), plain(),
				Categories.plain(), null);

		// then
		verify(texts).createFromBlog(USER, 800L, "제목", "가".repeat(2000), true);
	}

	@Test
	@DisplayName("연결이 지워졌으면 글을 가져오지 않는다")
	void 연결이_없으면_가져오지_않는다() {
		// given
		when(sources.findByIdForUpdate(SOURCE)).thenReturn(Optional.empty());

		// when
		int added = service.importIfConnected(SOURCE, USER, "블로그", List.of(), feed(item("g", 1)), plain(),
				Categories.plain());

		// then
		assertThat(added).isZero();
		verify(blogPosts, never()).insertNew(anyLong(), anyLong(), any(), any(), any(), any(), any(), any());
		verify(texts, never()).createFromBlog(anyLong(), anyLong(), any(), any(), anyBoolean());
	}

	// ---- delete ----

	@Test
	@DisplayName("지운 줄이 없으면 BLOG_NOT_FOUND다")
	void 지운_줄이_없으면_BLOG_NOT_FOUND다() {
		// given
		when(sources.deleteMine(SOURCE, USER)).thenReturn(0);

		// when & then
		assertError(() -> service.delete(USER, SOURCE), ErrorCode.BLOG_NOT_FOUND, null);
	}

	@Test
	@DisplayName("내 블로그를 지우면 예외가 없다")
	void 내_블로그를_지운다() {
		// given
		when(sources.deleteMine(SOURCE, USER)).thenReturn(1);

		// when
		service.delete(USER, SOURCE);

		// then
		verify(sources).deleteMine(SOURCE, USER);
	}

	// ---- 도움 ----

	private static BlogPlatform plain() {
		return platform(Platform.NAVER, Categories.plain());
	}

	private static BlogPlatform platform(Platform platform, Categories rule) {
		return new BlogPlatform() {

			@Override
			public Platform platform() {
				return platform;
			}

			@Override
			public boolean supports(URI siteUrl) {
				return true;
			}

			@Override
			public Categories categories(URI siteUrl, Feed feed) {
				return rule;
			}

		};
	}

	private static BlogSource source(Platform platform, String categories) {
		return withId(new BlogSource(USER, SITE, "블로그", FEED_URL, platform, categories), SOURCE);
	}

	private static BlogSource withId(BlogSource source, long id) {
		ReflectionTestUtils.setField(source, "id", id);
		return source;
	}

	private static Feed feed(Item... items) {
		return new Feed("블로그", SITE, null, List.of(items));
	}

	private static Item item(String guid, int day, String... categories) {
		return new Item(guid, guid, SITE + "/" + guid, "본문 " + guid, null, BASE.plusDays(day), List.of(categories));
	}

	private static Fetched fetched(String body) {
		return new Fetched(URI.create(FEED_URL), body.getBytes(StandardCharsets.UTF_8), "application/xml");
	}

	private static String rss(String... items) {
		return "<rss version=\"2.0\"><channel><title>블로그</title><link>" + SITE + "</link>" + String.join("", items)
				+ "</channel></rss>";
	}

	private static String rssItem(String guid, String category) {
		return "<item><title>" + guid + "</title><link>" + SITE + "/" + guid + "</link><guid>" + guid
				+ "</guid><description>본문</description><category>" + category + "</category></item>";
	}

	private static void assertError(ThrowingCallable call, ErrorCode code, String field) {
		assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class, e -> {
			assertThat(e.getErrorCode()).isEqualTo(code);
			assertThat(e.getField()).isEqualTo(field);
		});
	}

}
