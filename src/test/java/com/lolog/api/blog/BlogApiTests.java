package com.lolog.api.blog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.auth.TestTokens;
import com.lolog.api.notification.PushSender;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 블로그 연결과 글 가져오기(#72, texts.md 4장)를 실제 PostgreSQL·Redis와 내 컴퓨터에 띄운 가짜 블로그로 확인한다.
// 가짜 블로그가 127.0.0.1이라 이 테스트에서만 내부 주소 막기를 끈다(막기는 BlogFetcherTests가 본다).
@SpringBootTest(properties = { "lolog.blog.allow-private-hosts=true", "lolog.blog.poll-initial-delay-ms=3600000" })
@AutoConfigureMockMvc
@Testcontainers
class BlogApiTests {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Container
	@ServiceConnection(name = "redis")
	@SuppressWarnings("resource")
	static GenericContainer<?> redis = new GenericContainer<>("redis:latest").withExposedPorts(6379);

	private static final String S3_CONFIG = """
			{"identities":[{"name":"lolog-api","credentials":[{"accessKey":"test-access-key","secretKey":"test-secret-key"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
			""";

	@Container
	@SuppressWarnings("resource")
	static GenericContainer<?> seaweedfs = new GenericContainer<>("chrislusf/seaweedfs:4.48")
		.withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data")
		.withCopyToContainer(Transferable.of(S3_CONFIG), "/etc/seaweedfs/s3.json")
		.withExposedPorts(8333)
		.waitingFor(Wait.forListeningPort());

	@DynamicPropertySource
	static void storage(DynamicPropertyRegistry registry) {
		String url = "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333);
		registry.add("lolog.storage.endpoint", () -> url);
		registry.add("lolog.storage.public-endpoint", () -> url);
	}

	// 가짜 블로그: 경로 → 본문. 테스트마다 다른 경로를 쓴다.
	static final Map<String, String> PAGES = new ConcurrentHashMap<>();
	static HttpServer server;

	@BeforeAll
	static void startBlog() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			String path = exchange.getRequestURI().getPath();
			String body = PAGES.get(path);
			if (body != null && body.startsWith("REDIRECT ")) {
				exchange.getResponseHeaders().add("Location", body.substring(9));
				exchange.sendResponseHeaders(302, -1);
				exchange.close();
				return;
			}
			byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", path.endsWith(".html") ? "text/html" : "application/xml");
			exchange.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : bytes.length);
			if (body != null) {
				exchange.getResponseBody().write(bytes);
			}
			exchange.close();
		});
		server.start();
	}

	@AfterAll
	static void stopBlog() {
		server.stop(0);
	}

	static String base() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	private static final AtomicLong USERS = new AtomicLong(71000);
	private static final AtomicLong BLOGS = new AtomicLong();
	private static final String INTERNAL = "internal-test-token";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	ObjectMapper objectMapper;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Autowired
	BlogPoller poller;

	@MockitoBean
	PushSender sender;

	private ResultActions call(MockHttpServletRequestBuilder request, long userId) throws Exception {
		return mvc.perform(request.header("Authorization", TestTokens.bearer(userId)));
	}

	private ResultActions json(MockHttpServletRequestBuilder request, long userId, Object body) throws Exception {
		return call(request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)), userId);
	}

	private JsonNode read(ResultActions result) throws Exception {
		return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
	}

	static String item(String guid, String title, String category, String pubDate, String description) {
		return """
				<item><title>%s</title><link>%s/post/%s</link><guid>%s</guid>%s%s
				<description><![CDATA[%s]]></description></item>
				""".formatted(title, base(), guid, guid, category == null ? "" : "<category>" + category + "</category>",
				pubDate == null ? "" : "<pubDate>" + pubDate + "</pubDate>", description);
	}

	static String rss(String title, String... items) {
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<rss version="2.0" xmlns:atom="http://www.w3.org/2005/Atom"><channel><title>%s</title>
				<atom:link href="ignored" rel="self"/>%s</channel></rss>
				""".formatted(title, String.join("", items));
	}

	// 새 블로그 하나. 피드 주소는 /blogN/rss.xml
	private String blog(String feed) {
		String path = "/blog" + BLOGS.incrementAndGet();
		PAGES.put(path + "/rss.xml", feed);
		return path;
	}

	private String feedOf(String path) {
		return base() + path + "/rss.xml";
	}

	private List<JsonNode> messages(long textId) {
		return redisTemplate.opsForStream().range("lolog:detect", Range.unbounded()).stream()
			.map(r -> objectMapper.readTree((String) r.getValue().get("payload")))
			.filter(n -> n.has("textId") && n.get("textId").asLong() == textId)
			.toList();
	}

	private long textOf(String guid, long userId) {
		return jdbc.queryForObject(
				"select t.id from texts t join blog_posts p on p.id = t.blog_post_id where p.guid = ? and p.user_id = ?",
				Long.class, guid, userId);
	}

	private void sendCard(long textId) throws Exception {
		long jobId = jdbc.queryForObject("select id from jobs where text_id = ?", Long.class, textId);
		mvc.perform(post("/internal/texts/" + textId + "/cards").header("X-Internal-Token", INTERNAL)
			.contentType(MediaType.APPLICATION_JSON)
			.content("""
					{"jobId": %d, "cards": [{"kind": "추억", "summary": "블로그 이야기", "startMs": null, "endMs": null,
					  "speakers": ["화자1"], "lines": [{"lineNo": 1, "speaker": "화자1", "text": "그날 말이야"}]}]}
					""".formatted(jobId))).andExpect(status().isNoContent());
	}

	@Test
	void 링크_확인은_피드_주소와_카테고리를_돌려준다() throws Exception {
		String path = blog(rss("내 블로그", item("c1", "글1", "일상", null, "a"), item("c2", "글2", "여행", null, "b"),
				item("c3", "글3", "일상", null, "c"), item("c4", "글4", "가".repeat(31), null, "d")));
		long user = USERS.incrementAndGet();
		json(post("/api/blogs/check"), user, Map.of("url", feedOf(path))).andExpect(status().isOk())
			.andExpect(jsonPath("$.feedUrl").value(feedOf(path)))
			// 같은 이름은 한 번, 30자가 넘는 이름은 고를 수 없어 빠진다.
			.andExpect(jsonPath("$.categories.length()").value(2))
			.andExpect(jsonPath("$.categories[0]").value("일상"))
			.andExpect(jsonPath("$.categories[1]").value("여행"));
		assertThat(jdbc.queryForObject("select count(*) from blog_sources where user_id = ?", Long.class, user)).isZero();
	}

	@Test
	void 블로그_페이지에서_RSS_링크를_찾고_Atom도_읽는다() throws Exception {
		String path = "/atom" + BLOGS.incrementAndGet();
		PAGES.put(path + "/index.html", """
				<html><head><link rel="alternate" type="application/atom+xml" href="feed.xml"></head><body>블로그</body></html>
				""");
		PAGES.put(path + "/feed.xml", """
				<?xml version="1.0" encoding="UTF-8"?>
				<feed xmlns="http://www.w3.org/2005/Atom"><title>아톰 블로그</title>
				<entry><id>tag:a,1</id><title>아톰 글</title><link rel="alternate" href="%s/a1"/>
				<published>2026-11-01T10:00:00+09:00</published><category term="일기"/><content type="html">&lt;p&gt;안녕&lt;/p&gt;</content></entry>
				</feed>
				""".formatted(base()));
		long user = USERS.incrementAndGet();
		PAGES.put(path + "/go", "REDIRECT " + base() + path + "/index.html");
		json(post("/api/blogs/check"), user, Map.of("url", base() + path + "/go")).andExpect(status().isOk())
			.andExpect(jsonPath("$.feedUrl").value(base() + path + "/feed.xml"))
			.andExpect(jsonPath("$.categories[0]").value("일기"));

		JsonNode blog = read(json(post("/api/blogs"), user, Map.of("url", base() + path + "/index.html", "categories", List.of()))
			.andExpect(status().isCreated()));
		assertThat(blog.get("title").asString()).isEqualTo("아톰 블로그");
		long textId = textOf("tag:a,1", user);
		assertThat(messages(textId).getFirst().get("body").asString()).isEqualTo("안녕");
	}

	@Test
	void RSS가_없는_주소는_INVALID_BLOG다() throws Exception {
		String path = "/none" + BLOGS.incrementAndGet();
		PAGES.put(path + "/index.html", "<html><body>피드 없음</body></html>");
		long user = USERS.incrementAndGet();
		json(post("/api/blogs/check"), user, Map.of("url", base() + path + "/index.html")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_BLOG"));
		json(post("/api/blogs/check"), user, Map.of("url", "  ")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("url"));
		json(post("/api/blogs"), user, Map.of("categories", List.of())).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("url"));
		json(post("/api/blogs/check"), user, Map.of("url", "ftp://example.com")).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_BLOG"));
		json(post("/api/blogs"), user, Map.of("url", base() + "/nothing-here", "categories", List.of()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_BLOG"));
	}

	@Test
	void 연결하면_고른_카테고리_글만_바로_카드_작업이_된다() throws Exception {
		String path = blog(rss("여행 블로그",
				item("n3", "셋째 글", "일상", "Mon, 03 Nov 2026 10:00:00 +0900", "<p>셋째</p><p>둘째 줄</p>"),
				item("n2", "여행 글", "여행", "Sun, 02 Nov 2026 10:00:00 +0900", "여행"),
				item("n1", "첫 글", "일상", "Sat, 01 Nov 2026 10:00:00 +0900", "첫<br>글<script>x()</script>")));
		long user = USERS.incrementAndGet();
		JsonNode blog = read(json(post("/api/blogs"), user, Map.of("url", feedOf(path), "categories", List.of(" 일상 ")))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.siteUrl").value(feedOf(path)))
			.andExpect(jsonPath("$.title").value("여행 블로그"))
			.andExpect(jsonPath("$.categories[0]").value("일상")));
		long blogId = blog.get("id").asLong();

		assertThat(jdbc.queryForObject("select count(*) from blog_posts where source_id = ?", Long.class, blogId)).isEqualTo(2);
		JsonNode message = messages(textOf("n1", user)).getFirst();
		assertThat(message.get("kind").asString()).isEqualTo("text");
		assertThat(message.get("title").asString()).isEqualTo("첫 글");
		assertThat(message.get("body").asString()).isEqualTo("첫\n글");
		assertThat(message.has("place")).isFalse();
		assertThat(messages(textOf("n3", user)).getFirst().get("body").asString()).isEqualTo("셋째\n\n둘째 줄");

		// 최신 글부터
		call(get("/api/blogs/" + blogId + "/posts"), user).andExpect(status().isOk())
			.andExpect(jsonPath("$.posts.length()").value(2))
			.andExpect(jsonPath("$.posts[0].title").value("셋째 글"))
			.andExpect(jsonPath("$.posts[0].category").value("일상"))
			.andExpect(jsonPath("$.posts[0].link").value(base() + "/post/n3"))
			.andExpect(jsonPath("$.posts[0].status").value("QUEUED"))
			.andExpect(jsonPath("$.posts[1].title").value("첫 글"))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());

		// 같은 RSS를 다시 등록하면 거절
		json(post("/api/blogs"), user, Map.of("url", feedOf(path), "categories", List.of())).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("BLOG_EXISTS"));
		call(get("/api/blogs"), user).andExpect(jsonPath("$.blogs.length()").value(1))
			.andExpect(jsonPath("$.blogs[0].id").value(blogId));
	}

	@Test
	void 카테고리는_1에서_30자이고_같은_이름은_안_된다() throws Exception {
		String path = blog(rss("b"));
		long user = USERS.incrementAndGet();
		json(post("/api/blogs"), user, Map.of("url", feedOf(path), "categories", List.of("가".repeat(31))))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("categories[0]"));
		json(post("/api/blogs"), user, Map.of("url", feedOf(path), "categories", List.of("Daily", "daily")))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("categories[1]"));
		json(post("/api/blogs"), user, Map.of("url", feedOf(path), "categories", List.of("  ")))
			.andExpect(status().isBadRequest());
	}

	@Test
	void 블로그_글_카드는_쓴_시각이고_본문을_지우고_푸시는_없다() throws Exception {
		String path = blog(rss("일기장", item("d1", "그날", null, "Sat, 01 Nov 2026 10:00:00 +0900", "본문")));
		long user = USERS.incrementAndGet();
		json(post("/api/blogs"), user, Map.of("url", feedOf(path), "categories", List.of())).andExpect(status().isCreated());
		call(post("/api/me/badges/cards/read"), user).andExpect(status().isNoContent());
		long textId = textOf("d1", user);

		sendCard(textId);

		long cardId = jdbc.queryForObject("select id from cards where text_id = ?", Long.class, textId);
		JsonNode card = read(call(get("/api/cards/" + cardId), user).andExpect(status().isOk()));
		assertThat(card.get("source").asString()).isEqualTo("BLOG");
		assertThat(card.get("truncated").asBoolean()).isFalse();
		assertThat(OffsetDateTime.parse(card.get("recordedAt").asString()).toInstant())
			.isEqualTo(OffsetDateTime.parse("2026-11-01T10:00:00+09:00").toInstant());
		call(get("/api/texts/" + textId), user).andExpect(jsonPath("$.source").value("BLOG"))
			.andExpect(jsonPath("$.status").value("DONE"))
			.andExpect(jsonPath("$.body").doesNotExist())
			.andExpect(jsonPath("$.link").value(base() + "/post/d1"))
			.andExpect(jsonPath("$.blogTitle").value("일기장"));
		assertThat(jdbc.queryForObject("select body from texts where id = ?", String.class, textId)).isNull();
		// 푸시·알림 목록 없이 보관함 점만 켜진다.
		assertThat(jdbc.queryForObject("select count(*) from notifications where user_id = ?", Long.class, user)).isZero();
		call(get("/api/me/badges"), user).andExpect(jsonPath("$.cards").value(true));
		call(post("/api/me/badges/cards/read"), user).andExpect(status().isNoContent());
		call(get("/api/me/badges"), user).andExpect(jsonPath("$.cards").value(false));
	}

	@Test
	void 긴_글은_앞부분만_보내고_잘림을_표시한다() throws Exception {
		String path = blog(rss("긴 블로그", item("l1", "긴 글", null, null, "가".repeat(2500))));
		long user = USERS.incrementAndGet();
		json(post("/api/blogs"), user, Map.of("url", feedOf(path), "categories", List.of())).andExpect(status().isCreated());
		long textId = textOf("l1", user);
		assertThat(messages(textId).getFirst().get("body").asString()).hasSize(2000);
		sendCard(textId);
		long cardId = jdbc.queryForObject("select id from cards where text_id = ?", Long.class, textId);
		call(get("/api/cards/" + cardId), user).andExpect(jsonPath("$.truncated").value(true));
		call(get("/api/texts/" + textId), user).andExpect(jsonPath("$.truncated").value(true));
	}

	@Test
	void 주기적으로_읽으면_새_글과_새로_고른_카테고리_글만_가져온다() throws Exception {
		String one = item("p1", "하나", "일상", "Sat, 01 Nov 2026 10:00:00 +0900", "하나");
		String food = item("p2", "음식", "음식", "Sun, 02 Nov 2026 10:00:00 +0900", "음식");
		String path = blog(rss("주기 블로그", one, food));
		long user = USERS.incrementAndGet();
		long blogId = read(json(post("/api/blogs"), user, Map.of("url", feedOf(path), "categories", List.of("일상")))
			.andExpect(status().isCreated())).get("id").asLong();

		String three = item("p3", "셋", "일상", "Mon, 03 Nov 2026 10:00:00 +0900", "셋");
		PAGES.put(path + "/rss.xml", rss("주기 블로그", three, food, one));
		json(patch("/api/blogs/" + blogId), user, Map.of("categories", List.of("일상", "음식"))).andExpect(status().isOk())
			.andExpect(jsonPath("$.categories.length()").value(2));
		assertThat(poller.poll(blogId)).isEqualTo(2);
		// 다시 읽어도 같은 글은 가져오지 않는다.
		assertThat(poller.poll(blogId)).isZero();
		call(get("/api/blogs/" + blogId + "/posts").param("size", "2"), user)
			.andExpect(jsonPath("$.posts[0].title").value("셋"))
			.andExpect(jsonPath("$.posts[1].title").value("음식"))
			.andExpect(jsonPath("$.nextCursor").exists());
	}

	@Test
	void 카드를_지운_글은_cardId와_status가_null이고_다시_가져오지_않는다() throws Exception {
		String path = blog(rss("지우기 블로그", item("x1", "지울 글", null, null, "본문")));
		long user = USERS.incrementAndGet();
		long blogId = read(json(post("/api/blogs"), user, Map.of("url", feedOf(path), "categories", List.of()))
			.andExpect(status().isCreated())).get("id").asLong();
		long textId = textOf("x1", user);
		sendCard(textId);
		long cardId = jdbc.queryForObject("select id from cards where text_id = ?", Long.class, textId);
		call(get("/api/blogs/" + blogId + "/posts"), user).andExpect(jsonPath("$.posts[0].cardId").value(cardId))
			.andExpect(jsonPath("$.posts[0].status").value("DONE"));

		call(delete("/api/cards/" + cardId), user).andExpect(status().isNoContent());
		JsonNode post = read(call(get("/api/blogs/" + blogId + "/posts"), user)).get("posts").get(0);
		assertThat(post.get("cardId").isNull()).isTrue();
		assertThat(post.get("status").isNull()).isTrue();
		assertThat(poller.poll(blogId)).isZero();
	}

	@Test
	void 연결을_끊으면_블로그만_지우고_카드와_가져온_글은_남는다() throws Exception {
		String path = blog(rss("끊기 블로그", item("u1", "남을 글", null, null, "본문")));
		long user = USERS.incrementAndGet();
		long blogId = read(json(post("/api/blogs"), user, Map.of("url", feedOf(path), "categories", List.of()))
			.andExpect(status().isCreated())).get("id").asLong();
		long textId = textOf("u1", user);
		sendCard(textId);

		call(delete("/api/blogs/" + blogId), user).andExpect(status().isNoContent());
		call(delete("/api/blogs/" + blogId), user).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BLOG_NOT_FOUND"));
		call(get("/api/blogs/" + blogId + "/posts"), user).andExpect(status().isNotFound());
		assertThat(jdbc.queryForObject("select count(*) from blog_posts where user_id = ? and source_id is null", Long.class,
				user)).isEqualTo(1);
		assertThat(jdbc.queryForObject("select count(*) from cards where text_id = ?", Long.class, textId)).isEqualTo(1);
		call(get("/api/texts/" + textId), user).andExpect(jsonPath("$.blogTitle").value("끊기 블로그"));

		// 다시 연결해도 같은 글은 다시 가져오지 않고, 예전 글은 목록에 안 보인다.
		long again = read(json(post("/api/blogs"), user, Map.of("url", feedOf(path), "categories", List.of()))
			.andExpect(status().isCreated())).get("id").asLong();
		call(get("/api/blogs/" + again + "/posts"), user).andExpect(jsonPath("$.posts.length()").value(0));
	}

	@Test
	void 남의_블로그는_404이고_탈퇴하면_모두_지운다() throws Exception {
		String path = blog(rss("탈퇴 블로그", item("w1", "글", null, null, "본문")));
		long user = USERS.incrementAndGet();
		long blogId = read(json(post("/api/blogs"), user, Map.of("url", feedOf(path), "categories", List.of()))
			.andExpect(status().isCreated())).get("id").asLong();
		long other = USERS.incrementAndGet();
		call(get("/api/blogs/" + blogId + "/posts"), other).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BLOG_NOT_FOUND"));
		json(patch("/api/blogs/" + blogId), other, Map.of("categories", List.of())).andExpect(status().isNotFound());
		sendCard(textOf("w1", user));

		mvc.perform(delete("/internal/users/" + user).header("X-Internal-Token", INTERNAL))
			.andExpect(status().isNoContent());
		for (String table : List.of("blog_sources", "blog_posts", "texts", "cards")) {
			assertThat(jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Long.class, user))
				.as(table)
				.isZero();
		}
	}

}
