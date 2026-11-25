package com.lolog.api.domain.admin.repository;

import static com.lolog.api.support.RepositoryRows.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.admin.entity.Feedback;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("FeedbackRepository")
class FeedbackRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private FeedbackRepository feedback;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	@DisplayName("since 뒤에 보낸 것만 세고 since와 같은 시각은 뺀다")
	void since와_같은_시각은_세지_않는다() {
		// given
		insert(ME, "OPEN", at(4));
		insert(ME, "OPEN", at(5));
		insert(ME, "DONE", at(6));
		insert(OTHER, "OPEN", at(7));

		// when & then
		assertThat(feedback.countSentSince(ME, at(5))).isEqualTo(1);
		assertThat(feedback.countSentSince(ME, at(3))).isEqualTo(3);
	}

	@Test
	@DisplayName("목록은 ID 내림차순이고 before를 주면 그보다 작은 ID만 읽는다")
	void 목록은_ID_내림차순이다() {
		// given
		long f1 = insert(ME, "OPEN", at(0));
		long f2 = insert(ME, "DONE", at(0));
		long f3 = insert(OTHER, "OPEN", at(0));

		// when & then
		assertThat(feedback.findPage(null, Limit.of(2))).extracting(Feedback::getId).containsExactly(f3, f2);
		assertThat(feedback.findPage(f2, Limit.of(2))).extracting(Feedback::getId).containsExactly(f1);
	}

	@Test
	@DisplayName("처리 상태로 거른 목록도 ID 내림차순이고 before를 지킨다")
	void 처리_상태로_거른_목록() {
		// given
		long f1 = insert(ME, "OPEN", at(0));
		insert(ME, "DONE", at(0));
		long f3 = insert(OTHER, "OPEN", at(0));

		// when & then
		assertThat(feedback.findPageByStatus("OPEN", null, Limit.of(10))).extracting(Feedback::getId)
			.containsExactly(f3, f1);
		assertThat(feedback.findPageByStatus("OPEN", f3, Limit.of(10))).extracting(Feedback::getId)
			.containsExactly(f1);
	}

	@Test
	@DisplayName("탈퇴하면 그 회원이 보낸 것의 보낸 사람만 비우고 줄은 남긴다")
	void 탈퇴하면_보낸_사람만_비운다() {
		// given
		long mine = insert(ME, "OPEN", at(0));
		long others = insert(OTHER, "OPEN", at(0));

		// when
		int changed = feedback.clearUser(ME);

		// then
		assertThat(changed).isEqualTo(1);
		assertThat(feedback.findById(mine)).hasValueSatisfying(f -> assertThat(f.getUserId()).isNull());
		assertThat(feedback.findById(others)).hasValueSatisfying(f -> assertThat(f.getUserId()).isEqualTo(OTHER));
	}

	private long insert(long userId, String status, OffsetDateTime createdAt) {
		return jdbc.queryForObject("""
				insert into feedback (user_id, category, text, app_version, device_model, status, created_at)
				values (?, 'BUG', '내용', '1.0.0', 'Pixel', ?, ?) returning id
				""", Long.class, userId, status, createdAt);
	}

}
