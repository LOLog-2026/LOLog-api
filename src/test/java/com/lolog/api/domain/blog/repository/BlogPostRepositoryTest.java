package com.lolog.api.domain.blog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("BlogPostRepository")
class BlogPostRepositoryTest extends RepositoryTest {

	private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 1, 12, 0, 0, 0, ZoneOffset.UTC);

	@Autowired
	private BlogPostRepository blogPostRepository;

	@Autowired
	private JdbcTemplate jdbc;

	private long source(long userId) {
		return jdbc.queryForObject("""
				insert into blog_sources (user_id, site_url, feed_url) values (?, 'https://a.b', gen_random_uuid()::text) returning id
				""", Long.class, userId);
	}

	private Optional<Long> insertNew(long sourceId, long userId, String guid, String title) {
		return blogPostRepository.insertNew(sourceId, userId, guid, title, "https://a.b/" + guid, "일상", T0, "블로그");
	}

	private Optional<Long> insertOrRevive(long sourceId, long userId, String guid, String title) {
		return blogPostRepository.insertOrRevive(sourceId, userId, guid, title, "https://a.b/" + guid, "여행", T0, "새 이름");
	}

	private Map<String, Object> row(long userId, String guid) {
		return jdbc.queryForMap("select id, source_id, title, link, category, blog_title, skipped from blog_posts "
				+ "where user_id = ? and guid = ?", userId, guid);
	}

	private long count(long userId) {
		return jdbc.queryForObject("select count(*) from blog_posts where user_id = ?", Long.class, userId);
	}

	private long text(long userId, long postId) {
		return jdbc.queryForObject("insert into texts (user_id, source, blog_post_id, title) values (?, 'BLOG', ?, '제목') returning id",
				Long.class, userId, postId);
	}

	private long card(long userId, long textId) {
		return jdbc.queryForObject("""
				insert into cards (text_id, user_id, kind, summary, recorded_at) values (?, ?, '웃김', '요약', ?) returning id
				""", Long.class, textId, userId, T0);
	}

	private static List<Long> ids(List<BlogPostView> views) {
		return views.stream().map(BlogPostView::id).toList();
	}

	@Test
	@DisplayName("insertSkipped는 guid만 적은 옛 글을 넣고, 같은 guid가 있으면 그대로 둔다")
	void insertSkipped_이미_있으면_그대로_둔다() {
		// given
		long sourceId = source(1L);
		insertNew(sourceId, 1L, "g1", "가져온 글");

		// when
		blogPostRepository.insertSkipped(sourceId, 1L, "g1");
		blogPostRepository.insertSkipped(sourceId, 1L, "g2");
		blogPostRepository.insertSkipped(sourceId, 1L, "g2");

		// then
		assertThat(count(1L)).isEqualTo(2);
		assertThat(row(1L, "g1")).containsEntry("skipped", false).containsEntry("title", "가져온 글");
		assertThat(row(1L, "g2")).containsEntry("skipped", true).containsEntry("title", null)
				.containsEntry("link", null);
	}

	@Test
	@DisplayName("insertNew는 처음 보는 글이면 넣고 ID를 돌려준다")
	void insertNew_처음_보는_글이면_넣는다() {
		// given
		long sourceId = source(1L);

		// given
		Optional<Long> id = insertNew(sourceId, 1L, "g1", "글");

		// when & then
		assertThat(id).isPresent();
		assertThat(row(1L, "g1")).containsEntry("id", id.get()).containsEntry("source_id", sourceId)
				.containsEntry("category", "일상").containsEntry("blog_title", "블로그").containsEntry("skipped", false);
	}

	@Test
	@DisplayName("insertNew는 같은 회원의 같은 guid가 있으면 비어 있고, 옛 글(skipped)도 되살리지 않는다")
	void insertNew_이미_있으면_비어_있다() {
		// given
		long sourceId = source(1L);
		insertNew(sourceId, 1L, "g1", "글");
		blogPostRepository.insertSkipped(sourceId, 1L, "old");

		// when & then
		assertThat(insertNew(sourceId, 1L, "g1", "바뀐 글")).isEmpty();
		assertThat(insertNew(sourceId, 1L, "old", "옛 글")).isEmpty();
		assertThat(row(1L, "g1")).containsEntry("title", "글");
		assertThat(row(1L, "old")).containsEntry("skipped", true);
	}

	@Test
	@DisplayName("insertNew는 다른 회원이면 같은 guid라도 넣는다")
	void insertNew_다른_회원이면_같은_guid라도_넣는다() {
		// given
		insertNew(source(1L), 1L, "g1", "글");

		// when & then
		assertThat(insertNew(source(2L), 2L, "g1", "글")).isPresent();
	}

	@Test
	@DisplayName("insertOrRevive는 처음 보는 글이면 넣고 ID를 돌려준다")
	void insertOrRevive_처음_보는_글이면_넣는다() {
		// given
		long sourceId = source(1L);

		// given
		Optional<Long> id = insertOrRevive(sourceId, 1L, "g1", "글");

		// when & then
		assertThat(id).isPresent();
		assertThat(row(1L, "g1")).containsEntry("id", id.get()).containsEntry("skipped", false);
	}

	@Test
	@DisplayName("insertOrRevive는 옛 글(skipped)이면 같은 ID로 되살리고 내용을 채운다")
	void insertOrRevive_옛_글이면_되살린다() {
		// given
		long oldSource = source(1L);
		long newSource = source(1L);
		blogPostRepository.insertSkipped(oldSource, 1L, "g1");
		long skippedId = (Long) row(1L, "g1").get("id");

		// given
		Optional<Long> id = insertOrRevive(newSource, 1L, "g1", "되살린 글");

		// when & then
		assertThat(id).contains(skippedId);
		assertThat(row(1L, "g1")).containsEntry("skipped", false).containsEntry("source_id", newSource)
				.containsEntry("title", "되살린 글").containsEntry("link", "https://a.b/g1")
				.containsEntry("category", "여행").containsEntry("blog_title", "새 이름");
	}

	@Test
	@DisplayName("insertOrRevive는 이미 가져온 글이면 비어 있고 내용을 바꾸지 않는다")
	void insertOrRevive_이미_가져온_글이면_비어_있다() {
		// given
		long sourceId = source(1L);
		insertNew(sourceId, 1L, "g1", "글");

		// when & then
		assertThat(insertOrRevive(sourceId, 1L, "g1", "바뀐 글")).isEmpty();
		assertThat(row(1L, "g1")).containsEntry("title", "글").containsEntry("category", "일상");
	}

	@Test
	@DisplayName("findPage는 그 블로그의 옛 글이 아닌 글을 최신순으로 읽고 before보다 작은 ID만 읽는다")
	void findPage_옛_글을_빼고_최신순으로_읽는다() {
		// given
		long sourceId = source(1L);
		long a = insertNew(sourceId, 1L, "a", "가").orElseThrow();
		blogPostRepository.insertSkipped(sourceId, 1L, "old");
		long b = insertNew(sourceId, 1L, "b", "나").orElseThrow();
		long c = insertNew(sourceId, 1L, "c", "다").orElseThrow();
		insertNew(source(1L), 1L, "d", "라");

		// when & then
		assertThat(ids(blogPostRepository.findPage(sourceId, null, Limit.of(10)))).containsExactly(c, b, a);
		assertThat(ids(blogPostRepository.findPage(sourceId, c, Limit.of(10)))).containsExactly(b, a);
		assertThat(ids(blogPostRepository.findPage(sourceId, null, Limit.of(2)))).containsExactly(c, b);
	}

	@Test
	@DisplayName("findPage는 글로 만들지 않은 글의 글 ID·첫 카드를 비우고, 만든 글은 첫 카드와 상태를 채운다")
	void findPage_만든_글의_첫_카드와_상태를_채운다() {
		// given
		long sourceId = source(1L);
		long notMade = insertNew(sourceId, 1L, "a", "가").orElseThrow();
		long queued = insertNew(sourceId, 1L, "b", "나").orElseThrow();
		long done = insertNew(sourceId, 1L, "c", "다").orElseThrow();
		long queuedText = text(1L, queued);
		long doneText = text(1L, done);
		long firstCard = card(1L, doneText);
		card(1L, doneText);

		// when
		Map<Long, BlogPostView> views = new java.util.HashMap<>();
		blogPostRepository.findPage(sourceId, null, Limit.of(10)).forEach(v -> views.put(v.id(), v));

		// then
		assertThat(views.get(notMade).textId()).isNull();
		assertThat(views.get(notMade).cardId()).isNull();
		assertThat(views.get(queued).textId()).isEqualTo(queuedText);
		assertThat(views.get(queued).status()).isEqualTo("QUEUED");
		assertThat(views.get(done).textId()).isEqualTo(doneText);
		assertThat(views.get(done).cardId()).isEqualTo(firstCard);
		assertThat(views.get(done).status()).isEqualTo("DONE");
		assertThat(views.get(done).title()).isEqualTo("다");
		assertThat(views.get(done).link()).isEqualTo("https://a.b/c");
		assertThat(views.get(done).category()).isEqualTo("일상");
		assertThat(views.get(done).publishedAt()).isEqualTo(T0);
	}

	@Test
	@DisplayName("deleteAllByUserId는 그 회원의 블로그 글만 지운다")
	void deleteAllByUserId_그_회원의_글만_지운다() {
		// given
		long sourceId = source(1L);
		insertNew(sourceId, 1L, "a", "가");
		blogPostRepository.insertSkipped(sourceId, 1L, "old");
		insertNew(source(2L), 2L, "a", "가");

		// when & then
		assertThat(blogPostRepository.deleteAllByUserId(1L)).isEqualTo(2);

		// when & then
		assertThat(count(1L)).isZero();
		assertThat(count(2L)).isEqualTo(1);
	}

}
