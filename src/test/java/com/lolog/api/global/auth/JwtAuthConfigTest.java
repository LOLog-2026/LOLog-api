package com.lolog.api.global.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("JwtAuthConfig")
class JwtAuthConfigTest {

	// ---- checkExpiry ----

	@Test
	@DisplayName("만료 뒤 29초는 여유 안이라 통과한다")
	void 만료_뒤_29초는_여유_안이라_통과한다() {
		// given
		Jwt jwt = token("1", "access", Instant.now().minusSeconds(29));

		// when & then
		assertThat(JwtAuthConfig.checkExpiry(jwt).hasErrors()).isFalse();
	}

	@Test
	@DisplayName("만료 뒤 31초는 token expired다")
	void 만료_뒤_31초는_token_expired다() {
		// given
		Jwt jwt = token("1", "access", Instant.now().minusSeconds(31));

		// when & then
		assertThat(codes(JwtAuthConfig.checkExpiry(jwt))).containsExactly(JwtAuthConfig.EXPIRED);
	}

	@Test
	@DisplayName("exp가 없으면 invalid token이다")
	void exp가_없으면_invalid_token이다() {
		// given
		Jwt jwt = token("1", "access", null);

		// when & then
		assertThat(codes(JwtAuthConfig.checkExpiry(jwt))).containsExactly("invalid_token");
	}

	// ---- checkClaims ----

	@Test
	@DisplayName("sub가 숫자 1자리부터 18자리까지면 통과한다")
	void sub가_숫자_1자리부터_18자리까지면_통과한다() {
		// when & then
		assertThat(JwtAuthConfig.checkClaims(token("1", "access", future())).hasErrors()).isFalse();
		assertThat(JwtAuthConfig.checkClaims(token("123456789012345678", "access", future())).hasErrors()).isFalse();
	}

	// 형식만 본다. 회원이 있는지는 여기서 보지 않는다.
	@Test
	@DisplayName("sub가 0이어도 형식은 통과한다")
	void sub가_0이어도_형식은_통과한다() {
		// when & then
		assertThat(JwtAuthConfig.checkClaims(token("0", "access", future())).hasErrors()).isFalse();
	}

	@Test
	@DisplayName("sub가 음수면 invalid token이다")
	void sub가_음수면_invalid_token이다() {
		// when & then
		assertThat(codes(JwtAuthConfig.checkClaims(token("-1", "access", future())))).containsExactly("invalid_token");
	}

	@Test
	@DisplayName("sub가 19자리면 invalid token이다")
	void sub가_19자리면_invalid_token이다() {
		// when & then
		assertThat(codes(JwtAuthConfig.checkClaims(token("1234567890123456789", "access", future()))))
			.containsExactly("invalid_token");
	}

	@Test
	@DisplayName("sub가 숫자가 아니면 invalid token이다")
	void sub가_숫자가_아니면_invalid_token이다() {
		// when & then
		assertThat(codes(JwtAuthConfig.checkClaims(token("abc", "access", future())))).containsExactly("invalid_token");
		assertThat(codes(JwtAuthConfig.checkClaims(token(" 1", "access", future())))).containsExactly("invalid_token");
		// 전각 숫자는 \d에 들지 않는다.
		assertThat(codes(JwtAuthConfig.checkClaims(token("１", "access", future())))).containsExactly("invalid_token");
	}

	@Test
	@DisplayName("sub가 없으면 invalid token이다")
	void sub가_없으면_invalid_token이다() {
		// when & then
		assertThat(codes(JwtAuthConfig.checkClaims(token(null, "access", future())))).containsExactly("invalid_token");
	}

	@Test
	@DisplayName("typ가 access가 아니면 invalid token이다")
	void typ가_access가_아니면_invalid_token이다() {
		// when & then
		assertThat(codes(JwtAuthConfig.checkClaims(token("1", "refresh", future())))).containsExactly("invalid_token");
		assertThat(codes(JwtAuthConfig.checkClaims(token("1", null, future())))).containsExactly("invalid_token");
	}

	@Test
	@DisplayName("iat가 없으면 invalid token이다")
	void iat가_없으면_invalid_token이다() {
		// given
		Jwt jwt = Jwt.withTokenValue("t")
			.header("alg", "HS256")
			.subject("1")
			.claim("typ", "access")
			.expiresAt(future())
			.build();

		// when & then
		assertThat(codes(JwtAuthConfig.checkClaims(jwt))).containsExactly("invalid_token");
	}

	// exp·sub·typ가 null이면 그 클레임을 넣지 않는다.
	private static Jwt token(String sub, String typ, Instant exp) {
		Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "HS256").issuedAt(Instant.now().minusSeconds(60));
		if (sub != null) {
			builder.subject(sub);
		}
		if (typ != null) {
			builder.claim("typ", typ);
		}
		if (exp != null) {
			builder.expiresAt(exp);
		}
		return builder.build();
	}

	private static Instant future() {
		return Instant.now().plusSeconds(600);
	}

	private static java.util.List<String> codes(OAuth2TokenValidatorResult result) {
		return result.getErrors().stream().map(OAuth2Error::getErrorCode).toList();
	}

}
