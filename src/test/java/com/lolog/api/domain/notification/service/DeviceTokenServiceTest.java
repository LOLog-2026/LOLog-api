package com.lolog.api.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.lolog.api.domain.notification.repository.DeviceTokenRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("DeviceTokenService")
class DeviceTokenServiceTest {

	private static final long USER_ID = 7L;
	private static final String TOKEN = "fcm-token-1";

	@Mock
	private DeviceTokenRepository repository;

	@InjectMocks
	private DeviceTokenService service;

	@Test
	@DisplayName("등록하면 회원 ID와 토큰으로 upsert한다")
	void 등록하면_upsert한다() {
		// when
		service.register(USER_ID, TOKEN);

		// then
		verify(repository).upsert(USER_ID, TOKEN);
	}

	@Test
	@DisplayName("지울 때는 내 토큰일 때만 지우도록 토큰과 회원 ID를 함께 넘긴다")
	void 지우면_내_토큰만_지운다() {
		// when
		service.remove(USER_ID, TOKEN);

		// then
		verify(repository).deleteMine(TOKEN, USER_ID);
	}

	@Test
	@DisplayName("회원의 토큰 목록을 리포지토리에서 그대로 돌려준다")
	void 토큰_목록을_돌려준다() {
		// given
		given(repository.findTokensByUserId(USER_ID)).willReturn(List.of("a", "b"));

		// when
		List<String> tokens = service.tokensOf(USER_ID);

		// then
		assertThat(tokens).containsExactly("a", "b");
	}

	@Test
	@DisplayName("무효 토큰을 받은 그대로 지운다")
	void 무효_토큰을_지운다() {
		// when
		service.removeInvalid(List.of("a", "b"));

		// then
		verify(repository).deleteByTokens(List.of("a", "b"));
	}

	@Test
	@DisplayName("탈퇴하면 그 회원의 토큰을 모두 지운다")
	void 탈퇴하면_모두_지운다() {
		// when
		service.removeAll(USER_ID);

		// then
		verify(repository).deleteAllByUserId(USER_ID);
	}

}
