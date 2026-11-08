package com.lolog.api.auth;

import com.lolog.api.common.ApiException;
import com.lolog.api.common.SafeLog;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidationException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

// 인증 서비스(lolog-auth)가 발급한 액세스 토큰(JWT)으로 /api 사용자를 확인한다(TR-04, API 명세 "토큰").
// 서명 HS256, 비밀값 JWT_SECRET(32바이트 이상)은 lolog-auth와 같다. sub = 사용자 ID 문자열, typ = "access".
// 만료면 401 TOKEN_EXPIRED, 없거나 틀리면 401 UNAUTHORIZED. /internal은 InternalSecurityConfig(@Order(1))가 먼저 본다.
// 탈퇴한 회원의 토큰은 만료 전이어도 401 UNAUTHORIZED다(API 명세 "탈퇴한 회원의 토큰").
@Configuration
public class JwtAuthConfig {

	static final String EXPIRED = "token_expired";

	// 탈퇴 여부를 Redis에서 확인하지 못함. 401이면 앱이 로그인을 풀어 버리므로 500으로 답한다.
	static final String UNAVAILABLE = "withdrawn_check_unavailable";

	private static final Logger log = LoggerFactory.getLogger(JwtAuthConfig.class);

	// lolog-auth가 탈퇴 때 31분 동안 남기는 키. 값은 탈퇴 시각이고, 여기서는 키가 있는지만 본다.
	static final String WITHDRAWN_KEY = "lolog:auth:withdrawn:";
	private static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

	@Bean
	@Order(2)
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, JwtDecoder decoder) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			// 서버 상태 확인은 토큰 없이 부른다(API DTO 명세 0장).
			.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/api/health").permitAll()
				.anyRequest().authenticated())
			.oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder))
				// 검사기는 모두 돌고 오류를 모은다. 만료된 토큰은 Redis와 관계없이 TOKEN_EXPIRED로 답해 앱이 갱신하게 한다.
				.authenticationEntryPoint((request, response, e) -> {
					if (hasError(e, EXPIRED)) {
						UnauthorizedWriter.write(response, true);
					}
					else if (hasError(e, UNAVAILABLE)) {
						UnauthorizedWriter.writeInternalError(response);
					}
					else {
						UnauthorizedWriter.write(response, false);
					}
				}))
			.exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> UnauthorizedWriter.write(response)))
			.build();
	}

	@Bean
	JwtDecoder jwtDecoder(@Value("${lolog.auth.jwt-secret}") String secret, StringRedisTemplate redis) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalStateException("JWT_SECRET은 32바이트 이상이어야 합니다.");
		}
		SecretKey key = new SecretKeySpec(bytes, "HmacSHA256");
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtAuthConfig::checkExpiry,
				JwtAuthConfig::checkClaims, jwt -> checkWithdrawn(jwt, redis)));
		return decoder;
	}

	// 토큰의 sub를 회원 ID로 쓴다. 필터 체인이 인증을 요구하므로 여기까지 오면 Jwt가 있다.
	@Bean
	CurrentUserProvider jwtCurrentUserProvider() {
		return () -> {
			Authentication auth = SecurityContextHolder.getContext().getAuthentication();
			if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
				throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다.");
			}
			return Long.parseLong(jwt.getSubject());
		};
	}

	// 만료는 다른 오류와 코드를 나눈다. 앱은 TOKEN_EXPIRED일 때만 토큰을 갱신한다.
	private static OAuth2TokenValidatorResult checkExpiry(Jwt jwt) {
		Instant exp = jwt.getExpiresAt();
		if (exp == null) {
			return invalid("invalid_token", "exp가 없습니다.");
		}
		if (Instant.now(Clock.systemUTC()).minus(CLOCK_SKEW).isAfter(exp)) {
			return invalid(EXPIRED, "토큰이 만료됐습니다.");
		}
		return OAuth2TokenValidatorResult.success();
	}

	private static OAuth2TokenValidatorResult checkClaims(Jwt jwt) {
		if (!"access".equals(jwt.getClaimAsString("typ"))) {
			return invalid("invalid_token", "액세스 토큰이 아닙니다.");
		}
		String sub = jwt.getSubject();
		if (sub == null || !sub.matches("\\d{1,18}")) {
			return invalid("invalid_token", "sub가 사용자 ID가 아닙니다.");
		}
		if (jwt.getIssuedAt() == null) {
			return invalid("invalid_token", "iat가 없습니다.");
		}
		return OAuth2TokenValidatorResult.success();
	}

	// 요청마다 Redis를 한 번 본다. Redis에 못 붙으면 통과시키지 않고 500 INTERNAL_ERROR로 답한다(앱은 5xx를 다시 시도한다).
	private static OAuth2TokenValidatorResult checkWithdrawn(Jwt jwt, StringRedisTemplate redis) {
		String sub = jwt.getSubject();
		if (sub == null || !sub.matches("\\d{1,18}")) {
			// checkClaims가 거절한다.
			return OAuth2TokenValidatorResult.success();
		}
		try {
			if (Boolean.TRUE.equals(redis.hasKey(WITHDRAWN_KEY + sub))) {
				return invalid("invalid_token", "탈퇴한 회원입니다.");
			}
			return OAuth2TokenValidatorResult.success();
		}
		catch (DataAccessException e) {
			log.error("탈퇴한 회원인지 Redis에서 확인하지 못했습니다.", SafeLog.withoutMessages(e));
			return invalid(UNAVAILABLE, "탈퇴 여부를 확인하지 못했습니다.");
		}
	}

	private static OAuth2TokenValidatorResult invalid(String code, String description) {
		return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
	}

	private static boolean hasError(Throwable e, String code) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof JwtValidationException v && v.getErrors().stream().anyMatch(err -> code.equals(err.getErrorCode()))) {
				return true;
			}
		}
		return false;
	}

}
