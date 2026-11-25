package com.lolog.api.domain.blog.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("BlogSourceRepository")
class BlogSourceRepositoryTest extends RepositoryTest {

	@Autowired
	private BlogSourceRepository blogSourceRepository;

	@Autowired
	private JdbcTemplate jdbc;

	private long source(long userId) {
		return jdbc.queryForObject("""
				insert into blog_sources (user_id, site_url, feed_url) values (?, 'https://a.b', gen_random_uuid()::text) returning id
				""", Long.class, userId);
	}

	private long count(long userId) {
		return jdbc.queryForObject("select count(*) from blog_sources where user_id = ?", Long.class, userId);
	}

	@Test
	@DisplayName("findAllIds는 모든 회원의 블로그 ID를 오름차순으로 돌려준다")
	void findAllIds_모든_블로그_ID를_오름차순으로_돌려준다() {
		// given
		long a = source(1L);
		long b = source(2L);
		long c = source(1L);

		// when & then
		assertThat(blogSourceRepository.findAllIds()).containsSubsequence(a, b, c);
	}

	@Test
	@DisplayName("deleteMine은 내 블로그면 1을 돌려주며 지우고, 남의 블로그나 없는 블로그면 0을 돌려준다")
	void deleteMine_내_블로그만_지운다() {
		// given
		long mine = source(1L);
		long others = source(2L);

		// when & then
		assertThat(blogSourceRepository.deleteMine(others, 1L)).isZero();
		assertThat(blogSourceRepository.deleteMine(mine, 1L)).isEqualTo(1);
		assertThat(blogSourceRepository.deleteMine(mine, 1L)).isZero();

		// when & then
		assertThat(count(1L)).isZero();
		assertThat(count(2L)).isEqualTo(1);
	}

	@Test
	@DisplayName("deleteMine은 블로그를 지워도 가져온 글은 남기고 블로그 연결만 비운다")
	void deleteMine_가져온_글은_남긴다() {
		// given
		long sourceId = source(1L);
		long postId = jdbc.queryForObject("""
				insert into blog_posts (source_id, user_id, guid, title, link) values (?, 1, 'g', '글', 'https://a.b/g') returning id
				""", Long.class, sourceId);

		// when
		blogSourceRepository.deleteMine(sourceId, 1L);

		// then
		assertThat(jdbc.queryForObject("select source_id from blog_posts where id = ?", Long.class, postId)).isNull();
	}

	@Test
	@DisplayName("deleteAllByUserId는 그 회원의 블로그 연결만 지운다")
	void deleteAllByUserId_그_회원의_블로그만_지운다() {
		// given
		source(1L);
		source(1L);
		source(2L);

		// when & then
		assertThat(blogSourceRepository.deleteAllByUserId(1L)).isEqualTo(2);

		// when & then
		assertThat(count(1L)).isZero();
		assertThat(count(2L)).isEqualTo(1);
	}

}
