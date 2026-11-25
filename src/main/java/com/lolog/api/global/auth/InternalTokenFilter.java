package com.lolog.api.global.auth;

import com.lolog.api.global.error.ErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.web.filter.OncePerRequestFilter;

// /internal 요청의 X-Internal-Token을 INTERNAL_TOKEN 값과 비교한다. 없거나 틀리면 401이다.
// 모든 주소에 걸리지 않도록 빈으로 등록하지 않고 InternalSecurityConfig의 필터 체인에만 넣는다.
class InternalTokenFilter extends OncePerRequestFilter {

	static final String HEADER = "X-Internal-Token";

	private final byte[] expected;

	/**
	 * 비교할 내부 토큰 값을 받는다.
	 *
	 * @param token 비교할 내부 토큰 값(INTERNAL_TOKEN). 비어 있으면 모든 요청이 401
	 */
	InternalTokenFilter(String token) {
		this.expected = token.getBytes(StandardCharsets.UTF_8);
	}

	/**
	 * 헤더의 토큰이 맞을 때만 다음 필터로 넘긴다.
	 *
	 * @param request 들어온 /internal 요청
	 * @param response 401을 쓸 응답
	 * @param chain 토큰이 맞을 때 넘길 다음 필터
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String actual = request.getHeader(HEADER);
		// 값이 비어 있으면 아무 요청도 받지 않는다. 비교 시간으로 값을 짐작하지 못하게 MessageDigest.isEqual로 비교한다.
		boolean ok = expected.length > 0 && actual != null
				&& MessageDigest.isEqual(expected, actual.getBytes(StandardCharsets.UTF_8));
		if (!ok) {
			UnauthorizedWriter.write(response, ErrorCode.UNAUTHORIZED);
			return;
		}
		chain.doFilter(request, response);
	}

}
