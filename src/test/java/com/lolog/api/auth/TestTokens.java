package com.lolog.api.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

// 테스트용 액세스 토큰. 서명 비밀값은 src/test/resources/application.properties의 JWT_SECRET과 같다.
public final class TestTokens {

	public static final String SECRET = "test-jwt-secret-at-least-32-bytes-long!!";

	private TestTokens() {
	}

	// "Bearer <토큰>". 30분짜리 정상 액세스 토큰
	public static String bearer(Object userId) {
		return "Bearer " + token(String.valueOf(userId), "access", Duration.ofMinutes(30), SECRET);
	}

	public static String token(String sub, String typ, Duration validFor, String secret) {
		Instant now = Instant.now();
		JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().subject(sub)
			.issueTime(Date.from(now.minus(Duration.ofHours(1))))
			.expirationTime(Date.from(now.plus(validFor)));
		if (typ != null) {
			claims.claim("typ", typ);
		}
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims.build());
			jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
			return jwt.serialize();
		}
		catch (JOSEException e) {
			throw new IllegalStateException(e);
		}
	}

}
