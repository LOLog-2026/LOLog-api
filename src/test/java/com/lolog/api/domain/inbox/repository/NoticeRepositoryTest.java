package com.lolog.api.domain.inbox.repository;

import static com.lolog.api.support.RepositoryRows.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("NoticeRepository")
class NoticeRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private NoticeRepository notices;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
	}

	@Test
	@DisplayName("공지가 없으면 읽지 않은 공지도 없다")
	void 공지가_없으면_읽지_않은_공지도_없다() {
		// when & then
		assertThat(notices.existsUnread(ME)).isFalse();
	}

	@Test
	@DisplayName("남이 읽은 공지는 나에게 아직 읽지 않은 공지다")
	void 남이_읽은_공지는_내가_읽지_않은_공지다() {
		// given
		long read = rows.notice("읽음", at(0));
		long readByOther = rows.notice("남만 읽음", at(1));
		rows.noticeRead(read, ME);
		rows.noticeRead(readByOther, OTHER);

		// when & then
		assertThat(notices.existsUnread(ME)).isTrue();
		rows.noticeRead(readByOther, ME);
		assertThat(notices.existsUnread(ME)).isFalse();
	}

	@Test
	@DisplayName("공지를 지우면 1줄이고 읽음 기록도 함께 지워지며, 없는 공지는 0줄이다")
	void 공지를_지운다() {
		// given
		long id = rows.notice("공지", at(0));
		rows.noticeRead(id, ME);

		// when & then
		assertThat(notices.deleteNotice(id)).isEqualTo(1);
		assertThat(notices.deleteNotice(id)).isZero();
		assertThat(jdbc.queryForObject("select count(*) from notice_reads", Long.class)).isZero();
	}

}
