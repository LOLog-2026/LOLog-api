package com.lolog.api.auth;

import com.lolog.api.common.ApiException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

// JWT 검증(#9)이 붙기 전 배포 환경용. 사용자를 알 수 없으므로 항상 401이다.
@Configuration
@Profile("!local")
public class NoCurrentUserConfig {

	// /internal 체인을 만들면 Spring Boot의 기본 보안 설정이 꺼지므로, /api 등 나머지 주소를 여기서 직접 막는다.
	@Bean
	@Order(2)
	SecurityFilterChain denyByDefaultSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
			.exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> UnauthorizedWriter.write(response)))
			.build();
	}

	@Bean
	CurrentUserProvider noCurrentUserProvider() {
		return () -> {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
		};
	}

}
