package com.lolog.api.domain.room.repository;

import static com.lolog.api.support.RepositoryRows.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("RoomPostLikeRepository")
class RoomPostLikeRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private RoomPostLikeRepository likes;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	private long post;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
		long room = rows.room("방", ME, at(0), null);
		post = rows.post(room, rows.comic(ME, "만화", null), ME, at(0));
	}

	@Test
	@DisplayName("좋아요를 처음 누르면 1줄, 이미 눌렀으면 0줄이다")
	void 이미_눌렀으면_0줄이다() {
		// when & then
		assertThat(likes.insertIfAbsent(post, ME)).isEqualTo(1);
		assertThat(likes.insertIfAbsent(post, ME)).isZero();
		assertThat(likes.insertIfAbsent(post, OTHER)).isEqualTo(1);
		assertThat(likes.count()).isEqualTo(2);
	}

	@Test
	@DisplayName("좋아요를 빼면 1줄, 누르지 않았으면 0줄이다")
	void 누르지_않았으면_0줄이다() {
		// given
		rows.like(post, ME);

		// when & then
		assertThat(likes.deleteLike(post, OTHER)).isZero();
		assertThat(likes.deleteLike(post, ME)).isEqualTo(1);
		assertThat(likes.deleteLike(post, ME)).isZero();
	}

	@Test
	@DisplayName("탈퇴하면 그 회원의 좋아요만 지운다")
	void 탈퇴하면_그_회원의_좋아요만_지운다() {
		// given
		rows.like(post, ME);
		rows.like(post, OTHER);

		// when & then
		assertThat(likes.deleteAllByUserId(ME)).isEqualTo(1);
		assertThat(likes.findAll()).extracting(l -> l.getUserId()).containsExactly(OTHER);
	}

}
