package com.lolog.api.auth;

import com.lolog.api.common.ApiException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;

// JWT 검증(#9)이 붙기 전 배포 환경용. 사용자를 알 수 없으므로 항상 401이다.
@Configuration
@ConditionalOnProperty(name = "lolog.auth.dev-header-enabled", havingValue = "false", matchIfMissing = true)
public class NoCurrentUserConfig {

	@Bean
	CurrentUserProvider noCurrentUserProvider() {
		return () -> {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
		};
	}

}
