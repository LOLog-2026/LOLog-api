package com.lolog.api.domain.friend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("UserBlockRepository")
class UserBlockRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;

	@Autowired
	private UserBlockRepository blocks;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
	}

	@Test
	@DisplayName("주어진 회원 중 내가 차단한 사람만 돌려주고, 나를 차단한 사람은 뺀다")
	void 주어진_회원_중_내가_차단한_사람만_돌려준다() {
		// given
		rows.block(ME, 2L, false);
		rows.block(ME, 3L, true);
		rows.block(4L, ME, false);

		// when
		List<Long> ids = blocks.findBlockedIdsAmong(ME, List.of(2L, 4L, 5L));

		// then
		assertThat(ids).containsExactly(2L);
	}

	@Test
	@DisplayName("방 글을 숨기기로 한 차단만 돌려준다")
	void 방_글을_숨기기로_한_차단만_돌려준다() {
		// given
		rows.block(ME, 2L, false);
		rows.block(ME, 3L, true);
		rows.block(4L, 5L, true);

		// when & then
		assertThat(blocks.findHiddenInRoomsIds(ME)).containsExactly(3L);
	}

	@Test
	@DisplayName("차단을 풀면 1줄, 차단한 적 없으면 0줄이다")
	void 차단을_푼다() {
		// given
		rows.block(ME, 2L, false);
		rows.block(2L, ME, false);

		// when & then
		assertThat(blocks.deleteBlock(ME, 2L)).isEqualTo(1);
		assertThat(blocks.deleteBlock(ME, 2L)).isZero();
		assertThat(blocks.count()).isEqualTo(1);
	}

	@Test
	@DisplayName("탈퇴하면 그 회원이 차단한 줄과 차단당한 줄을 모두 지운다")
	void 탈퇴하면_양쪽_차단을_모두_지운다() {
		// given
		rows.block(ME, 2L, false);
		rows.block(3L, ME, false);
		rows.block(2L, 3L, false);

		// when
		int deleted = blocks.deleteAllOf(ME);

		// then
		assertThat(deleted).isEqualTo(2);
		assertThat(blocks.count()).isEqualTo(1);
	}

}
