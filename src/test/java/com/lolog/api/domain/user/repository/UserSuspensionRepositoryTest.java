package com.lolog.api.domain.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("UserSuspensionRepository")
class UserSuspensionRepositoryTest extends RepositoryTest {

	@Autowired
	private UserSuspensionRepository suspensions;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
	}

	@Test
	@DisplayName("정지가 없으면 넣고, 있으면 기한만 바꿔 줄은 하나다")
	void 없으면_넣고_있으면_기한을_바꾼다() {
		// given
		OffsetDateTime until = OffsetDateTime.parse("2030-01-01T00:00:00Z");

		// when
		suspensions.upsert(1L, null);
		suspensions.upsert(1L, until);

		// then
		assertThat(suspensions.count()).isEqualTo(1);
		OffsetDateTime saved = jdbc.queryForObject("select suspended_until from user_suspensions where user_id = 1",
				OffsetDateTime.class);
		assertThat(saved).isAtSameInstantAs(until);
	}

	@Test
	@DisplayName("영구 정지로 바꾸면 기한이 비워진다")
	void 영구_정지로_바꾸면_기한이_비워진다() {
		// given
		rows.suspension(1L, OffsetDateTime.parse("2030-01-01T00:00:00Z"));

		// when
		suspensions.upsert(1L, null);

		// then
		OffsetDateTime saved = jdbc.queryForObject("select suspended_until from user_suspensions where user_id = 1",
				OffsetDateTime.class);
		assertThat(saved).isNull();
	}

	@Test
	@DisplayName("지금 정지된 회원은 영구 정지와 기한이 남은 정지이고, 기한이 지난 정지는 뺀다")
	void 기한이_지난_정지는_정지_아님이다() {
		// given
		OffsetDateTime now = OffsetDateTime.now();
		rows.suspension(1L, null);
		rows.suspension(2L, now.plusDays(1));
		rows.suspension(3L, now.minusDays(1));
		rows.suspension(4L, null);

		// when
		List<Long> ids = suspensions.findSuspendedIdsAmong(List.of(1L, 2L, 3L, 5L));

		// then
		assertThat(ids).containsExactlyInAnyOrder(1L, 2L);
	}

	@Test
	@DisplayName("탈퇴하면 그 회원의 정지 표시만 지운다")
	void 탈퇴하면_정지_표시만_지운다() {
		// given
		rows.suspension(1L, null);
		rows.suspension(2L, null);

		// when & then
		assertThat(suspensions.deleteAllByUserId(1L)).isEqualTo(1);
		assertThat(suspensions.count()).isEqualTo(1);
	}

}
