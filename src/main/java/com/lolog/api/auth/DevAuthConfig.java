package com.lolog.api.auth;

import com.lolog.api.common.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

// JWT 검증(#9) 전까지 로컬에서만 쓰는 임시 인증.
// 앱의 테스트 토큰(Bearer)은 회원 1번으로 보고, 시험용 X-User-Id 헤더는 그 값을 회원 ID로 믿는다.
// local 프로필에서만 켜진다. 그 밖의 환경은 Spring Security 기본값으로 모두 막혀 있다.
@Configuration
@Profile("local")
public class DevAuthConfig {

	@Bean
	SecurityFilterChain devSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.build();
	}

	@Bean
	CurrentUserProvider headerCurrentUserProvider(HttpServletRequest request,
			@Value("${lolog.auth.dev-test-token:}") String testToken) {
		return () -> {
			if (!testToken.isEmpty() && ("Bearer " + testToken).equals(request.getHeader("Authorization"))) {
				return 1L;
			}
			String value = request.getHeader("X-User-Id");
			if (value == null || !value.matches("\\d{1,18}")) {
				throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
			}
			return Long.parseLong(value);
		};
	}

}
