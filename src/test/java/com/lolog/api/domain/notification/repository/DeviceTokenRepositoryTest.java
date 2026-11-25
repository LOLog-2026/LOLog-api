package com.lolog.api.domain.notification.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("DeviceTokenRepository")
class DeviceTokenRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private DeviceTokenRepository tokens;

	@Test
	@DisplayName("같은 토큰을 다른 사용자가 등록하면 새 줄 없이 그 사용자로 옮긴다")
	void 같은_토큰은_새_사용자로_옮긴다() {
		// given
		tokens.upsert(ME, "token-a");

		// when
		tokens.upsert(OTHER, "token-a");

		// then
		assertThat(tokens.count()).isEqualTo(1);
		assertThat(tokens.findTokensByUserId(ME)).isEmpty();
		assertThat(tokens.findTokensByUserId(OTHER)).containsExactly("token-a");
	}

	@Test
	@DisplayName("사용자의 토큰은 등록한 순서로 읽는다")
	void 토큰은_등록한_순서다() {
		// given
		tokens.upsert(ME, "token-b");
		tokens.upsert(ME, "token-a");
		tokens.upsert(OTHER, "token-c");

		// when & then
		assertThat(tokens.findTokensByUserId(ME)).containsExactly("token-b", "token-a");
	}

	@Test
	@DisplayName("내 토큰일 때만 지우고, 남의 토큰이면 0줄이다")
	void 내_토큰일_때만_지운다() {
		// given
		tokens.upsert(ME, "token-a");

		// when & then
		assertThat(tokens.deleteMine("token-a", OTHER)).isZero();
		assertThat(tokens.deleteMine("token-a", ME)).isEqualTo(1);
		assertThat(tokens.deleteMine("token-a", ME)).isZero();
	}

	@Test
	@DisplayName("주어진 토큰들만 한 번에 지운다")
	void 주어진_토큰들만_지운다() {
		// given
		tokens.upsert(ME, "token-a");
		tokens.upsert(OTHER, "token-b");
		tokens.upsert(ME, "token-c");

		// when
		int deleted = tokens.deleteByTokens(List.of("token-a", "token-b", "없는 토큰"));

		// then
		assertThat(deleted).isEqualTo(2);
		assertThat(tokens.findTokensByUserId(ME)).containsExactly("token-c");
	}

	@Test
	@DisplayName("탈퇴하면 그 사용자의 토큰만 지운다")
	void 탈퇴하면_그_사용자의_토큰만_지운다() {
		// given
		tokens.upsert(ME, "token-a");
		tokens.upsert(ME, "token-b");
		tokens.upsert(OTHER, "token-c");

		// when & then
		assertThat(tokens.deleteAllByUserId(ME)).isEqualTo(2);
		assertThat(tokens.findTokensByUserId(OTHER)).containsExactly("token-c");
	}

}
