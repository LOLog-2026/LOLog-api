package com.lolog.api.domain.text.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.text.entity.TextSource;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("TextRepository")
class TextRepositoryTest extends RepositoryTest {

	private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 1, 12, 0, 0, 0, ZoneOffset.UTC);

	@Autowired
	private TextRepository textRepository;

	@Autowired
	private JdbcTemplate jdbc;

	private long writtenText(long userId) {
		return jdbc.queryForObject("""
				insert into texts (user_id, source, title, body, place, situation, truncated)
				values (?, 'WRITTEN', '제목', '본문', '집', '저녁', true) returning id
				""", Long.class, userId);
	}

	private long blogText(long userId) {
		long postId = jdbc.queryForObject("""
				insert into blog_posts (user_id, guid, title, link, blog_title)
				values (?, gen_random_uuid()::text, '글', 'https://a.b/1', '내 블로그') returning id
				""", Long.class, userId);
		return jdbc.queryForObject("insert into texts (user_id, source, blog_post_id, title) values (?, 'BLOG', ?, '블로그 제목') returning id",
				Long.class, userId, postId);
	}

	private long card(long userId, long textId) {
		return jdbc.queryForObject("""
				insert into cards (text_id, user_id, kind, summary, recorded_at) values (?, ?, '웃김', '요약', ?) returning id
				""", Long.class, textId, userId, T0);
	}

	private void textJob(long userId, long textId, String status) {
		jdbc.update("insert into jobs (user_id, job_type, text_id, status) values (?, 'TEXT', ?, ?)", userId, textId, status);
	}

	@Test
	@DisplayName("findView는 카드도 실패한 작업도 없는 글을 QUEUED로, 첫 카드와 블로그 정보는 비워 읽는다")
	void findView_카드가_없으면_QUEUED다() {
		// given
		long id = writtenText(1L);
		textJob(1L, id, "RUNNING");

		// when
		TextView view = textRepository.findView(id, 1L).orElseThrow();

		// then
		assertThat(view.id()).isEqualTo(id);
		assertThat(view.source()).isEqualTo(TextSource.WRITTEN);
		assertThat(view.title()).isEqualTo("제목");
		assertThat(view.body()).isEqualTo("본문");
		assertThat(view.place()).isEqualTo("집");
		assertThat(view.situation()).isEqualTo("저녁");
		assertThat(view.truncated()).isTrue();
		assertThat(view.createdAt()).isNotNull();
		assertThat(view.cardId()).isNull();
		assertThat(view.status()).isEqualTo("QUEUED");
		assertThat(view.link()).isNull();
		assertThat(view.blogTitle()).isNull();
	}

	@Test
	@DisplayName("findView는 작업이 실패했고 카드가 없으면 FAILED로 읽는다")
	void findView_실패한_작업이_있으면_FAILED다() {
		// given
		long id = writtenText(1L);
		textJob(1L, id, "FAILED");

		// when & then
		assertThat(textRepository.findView(id, 1L).orElseThrow().status()).isEqualTo("FAILED");
	}

	@Test
	@DisplayName("findView는 카드가 있으면 작업이 실패했어도 DONE으로, 첫 카드는 가장 작은 카드 ID로 읽는다")
	void findView_카드가_있으면_DONE이고_첫_카드를_채운다() {
		// given
		long id = writtenText(1L);
		textJob(1L, id, "FAILED");
		long first = card(1L, id);
		card(1L, id);

		// when
		TextView view = textRepository.findView(id, 1L).orElseThrow();

		// then
		assertThat(view.status()).isEqualTo("DONE");
		assertThat(view.cardId()).isEqualTo(first);
	}

	@Test
	@DisplayName("findView는 블로그 글이면 원문 링크와 블로그 이름을 함께 읽는다")
	void findView_블로그_글이면_링크와_블로그_이름을_채운다() {
		// given
		long id = blogText(1L);

		// when
		TextView view = textRepository.findView(id, 1L).orElseThrow();

		// then
		assertThat(view.source()).isEqualTo(TextSource.BLOG);
		assertThat(view.link()).isEqualTo("https://a.b/1");
		assertThat(view.blogTitle()).isEqualTo("내 블로그");
	}

	@Test
	@DisplayName("findView는 남의 글이면 비어 있다")
	void findView_남의_글이면_비어_있다() {
		// given
		long id = writtenText(1L);

		// when & then
		assertThat(textRepository.findView(id, 2L)).isEmpty();
	}

	@Test
	@DisplayName("deleteAllByUserId는 그 회원의 글만 지운다")
	void deleteAllByUserId_그_회원의_글만_지운다() {
		// given
		writtenText(1L);
		writtenText(1L);
		long other = writtenText(2L);

		// when & then
		assertThat(textRepository.deleteAllByUserId(1L)).isEqualTo(2);

		// when & then
		assertThat(jdbc.queryForList("select id from texts where user_id in (1, 2)", Long.class)).containsExactly(other);
	}

}
