package com.lolog.api.auth;

import com.lolog.api.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
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
@Configuration
public class JwtAuthConfig {

	static final String EXPIRED = "token_expired";
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
				.authenticationEntryPoint((request, response, e) -> UnauthorizedWriter.write(response, isExpired(e))))
			.exceptionHandling(e -> e.authenticationEntryPoint((request, response, ex) -> UnauthorizedWriter.write(response)))
			.build();
	}

	@Bean
	JwtDecoder jwtDecoder(@Value("${lolog.auth.jwt-secret}") String secret) {
		byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
		if (bytes.length < 32) {
			throw new IllegalStateException("JWT_SECRET은 32바이트 이상이어야 합니다.");
		}
		SecretKey key = new SecretKeySpec(bytes, "HmacSHA256");
		NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
		decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtAuthConfig::checkExpiry,
				JwtAuthConfig::checkClaims));
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

	private static OAuth2TokenValidatorResult invalid(String code, String description) {
		return OAuth2TokenValidatorResult.failure(new OAuth2Error(code, description, null));
	}

	private static boolean isExpired(Throwable e) {
		for (Throwable t = e; t != null; t = t.getCause()) {
			if (t instanceof JwtValidationException v && v.getErrors().stream().anyMatch(err -> EXPIRED.equals(err.getErrorCode()))) {
				return true;
			}
		}
		return false;
	}

}
