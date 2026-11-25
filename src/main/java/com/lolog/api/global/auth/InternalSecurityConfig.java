package com.lolog.api.global.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

// 워커·인증 서비스가 부르는 /internal 주소. 모든 프로필에서 X-Internal-Token으로 막는다.
// 다른 필터 체인보다 먼저 본다(@Order(1)). 나머지(/api 등)는 JwtAuthConfig(@Order(2))가 맡는다.
@Configuration
public class InternalSecurityConfig {

	/**
	 * /internal 주소를 내부 토큰으로 막는 필터 체인을 만든다.
	 *
	 * @param http 필터 체인을 만들 보안 설정
	 * @param token 비교할 내부 토큰(lolog.internal.token, 환경 변수 INTERNAL_TOKEN)
	 * @return /internal 주소용 필터 체인
	 */
	@Bean
	@Order(1)
	SecurityFilterChain internalSecurityFilterChain(HttpSecurity http,
			@Value("${lolog.internal.token}") String token) throws Exception {
		return http
			.securityMatcher("/internal/**")
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.addFilterBefore(new InternalTokenFilter(token), AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
			.build();
	}

}
