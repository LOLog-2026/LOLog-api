package com.lolog.api.domain.inbox.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("BadgeReadRepository")
class BadgeReadRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private BadgeReadRepository badges;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("다시 보면 줄을 늘리지 않고 그 종류의 확인 시각만 뒤로 바꾼다")
	void 다시_보면_확인_시각만_바꾼다() {
		// given
		badges.markSeen(ME, "cards");
		badges.markSeen(ME, "rooms");
		OffsetDateTime first = seenAt(ME, "cards");
		OffsetDateTime rooms = seenAt(ME, "rooms");

		// when
		badges.markSeen(ME, "cards");

		// then
		assertThat(badges.count()).isEqualTo(2);
		assertThat(seenAt(ME, "cards")).isAfter(first);
		assertThat(seenAt(ME, "rooms")).isAtSameInstantAs(rooms);
	}

	@Test
	@DisplayName("탈퇴하면 그 회원의 확인 기록만 지운다")
	void 탈퇴하면_그_회원의_확인_기록만_지운다() {
		// given
		badges.markSeen(ME, "cards");
		badges.markSeen(ME, "rooms");
		badges.markSeen(OTHER, "cards");

		// when & then
		assertThat(badges.deleteAllByUserId(ME)).isEqualTo(2);
		assertThat(badges.count()).isEqualTo(1);
	}

	private OffsetDateTime seenAt(long userId, String kind) {
		return jdbc.queryForObject("select seen_at from badge_reads where user_id = ? and kind = ?",
				OffsetDateTime.class, userId, kind);
	}

}
