package com.lolog.api.domain.inbox.repository;

import static com.lolog.api.support.RepositoryRows.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("NoticeReadRepository")
class NoticeReadRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private NoticeReadRepository reads;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
	}

	@Test
	@DisplayName("같은 공지를 두 번 읽어도 읽음 줄은 하나다")
	void 같은_공지를_두_번_읽어도_한_줄이다() {
		// given
		long notice = rows.notice("공지", at(0));

		// when
		reads.markRead(notice, ME);
		reads.markRead(notice, ME);

		// then
		assertThat(reads.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("모두 읽음은 모든 공지에 읽음을 남기고, 이미 읽은 공지의 읽은 시각은 그대로 둔다")
	void 모두_읽음은_이미_읽은_공지를_그대로_둔다() {
		// given
		long first = rows.notice("하나", at(0));
		rows.notice("둘", at(1));
		rows.notice("셋", at(2));
		OffsetDateTime earlier = at(5);
		jdbc.update("insert into notice_reads (notice_id, user_id, read_at) values (?, ?, ?)", first, ME, earlier);

		// when
		reads.markAllRead(ME);

		// then
		assertThat(countOf(ME)).isEqualTo(3);
		assertThat(countOf(OTHER)).isZero();
		OffsetDateTime kept = jdbc.queryForObject(
				"select read_at from notice_reads where notice_id = ? and user_id = ?", OffsetDateTime.class, first, ME);
		assertThat(kept).isAtSameInstantAs(earlier);
	}

	@Test
	@DisplayName("탈퇴하면 그 회원의 읽음 기록만 지운다")
	void 탈퇴하면_그_회원의_읽음_기록만_지운다() {
		// given
		long notice = rows.notice("공지", at(0));
		rows.noticeRead(notice, ME);
		rows.noticeRead(notice, OTHER);

		// when & then
		assertThat(reads.deleteAllByUserId(ME)).isEqualTo(1);
		assertThat(countOf(OTHER)).isEqualTo(1);
	}

	private long countOf(long userId) {
		return jdbc.queryForObject("select count(*) from notice_reads where user_id = ?", Long.class, userId);
	}

}
