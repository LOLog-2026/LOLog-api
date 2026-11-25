package com.lolog.api.domain.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("UserProfileRepository")
class UserProfileRepositoryTest extends RepositoryTest {

	@Autowired
	private UserProfileRepository profiles;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
	}

	@Test
	@DisplayName("프로필이 없으면 넣고 1줄, 이미 있으면 그대로 두고 0줄이다")
	void 프로필이_없을_때만_넣는다() {
		// when
		int first = profiles.insertIfAbsent(1L, "AAAA1111");
		int again = profiles.insertIfAbsent(1L, "BBBB2222");

		// then
		assertThat(first).isEqualTo(1);
		assertThat(again).isZero();
		assertThat(profiles.findById(1L)).map(UserProfile::getFriendCode).contains("AAAA1111");
	}

	@Test
	@DisplayName("친구 코드가 다른 회원과 겹치면 넣지 않고 0줄이다")
	void 친구_코드가_겹치면_넣지_않는다() {
		// given
		rows.profile(1L, "AAAA1111", null);

		// when
		int inserted = profiles.insertIfAbsent(2L, "AAAA1111");

		// then
		assertThat(inserted).isZero();
		assertThat(profiles.existsById(2L)).isFalse();
	}

	@Test
	@DisplayName("친구 코드로 찾을 때 닉네임을 정하지 않은 회원은 찾지 않는다")
	void 닉네임이_없으면_친구_코드로_찾지_않는다() {
		// given
		rows.profile(1L, "AAAA1111", "닉네임");
		rows.profile(2L, "BBBB2222", null);

		// when & then
		assertThat(profiles.findByFriendCodeAndNicknameIsNotNull("AAAA1111")).map(UserProfile::getUserId).contains(1L);
		assertThat(profiles.findByFriendCodeAndNicknameIsNotNull("BBBB2222")).isEmpty();
	}

	@Test
	@DisplayName("탈퇴하면 그 회원의 프로필만 지운다")
	void 탈퇴하면_그_회원의_프로필만_지운다() {
		// given
		rows.profile(1L, "AAAA1111", null);
		rows.profile(2L, "BBBB2222", null);

		// when & then
		assertThat(profiles.deleteAllByUserId(1L)).isEqualTo(1);
		assertThat(profiles.findAll()).extracting(UserProfile::getUserId).containsExactly(2L);
	}

}
