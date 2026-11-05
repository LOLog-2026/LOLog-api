package com.lolog.api.auth;

import com.lolog.api.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

// JWT 검증(#9) 전까지 로컬에서만 쓰는 임시 인증. X-User-Id 헤더를 회원 ID로 믿는다.
// 이 속성은 application-local.yml에만 둔다. 배포 환경은 Spring Security 기본값으로 모두 막혀 있다.
@Configuration
@ConditionalOnProperty("lolog.auth.dev-header-enabled")
public class DevAuthConfig {

	@Bean
	SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.build();
	}

	@Bean
	CurrentUserProvider headerCurrentUserProvider(HttpServletRequest request) {
		return () -> {
			String value = request.getHeader("X-User-Id");
			if (value == null || !value.matches("\\d{1,18}")) {
				throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
			}
			return Long.parseLong(value);
		};
	}

}
