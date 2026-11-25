package com.lolog.api.global.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

// 녹음 글 본문은 1MB까지다. 길이를 밝히지 않은 요청은 Nginx의 client_max_body_size로 막는다.
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class UploadSizeFilter extends OncePerRequestFilter {

	private static final long MAX_BYTES = 1024 * 1024;

	/**
	 * 녹음 글 업로드 본문이 1MB를 넘으면 413으로 막는다.
	 *
	 * @param request 들어온 요청
	 * @param response 413을 쓸 응답
	 * @param chain 크기가 괜찮을 때 넘길 다음 필터
	 */
	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		boolean upload = "POST".equals(request.getMethod()) && "/api/recordings".equals(request.getRequestURI());
		if (upload && request.getContentLengthLong() > MAX_BYTES) {
			response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			response.getWriter().write("{\"code\":\"PAYLOAD_TOO_LARGE\",\"message\":\"본문이 너무 큽니다.\",\"field\":null}");
			return;
		}
		chain.doFilter(request, response);
	}

}
