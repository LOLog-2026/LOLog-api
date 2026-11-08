package com.lolog.api.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

// 보안 필터에서 컨트롤러를 거치지 않고 401 { code, message, field }를 쓴다.
final class UnauthorizedWriter {

	private UnauthorizedWriter() {
	}

	static void write(HttpServletResponse response) throws IOException {
		write(response, false);
	}

	// 만료된 액세스 토큰이면 TOKEN_EXPIRED(앱은 이때만 갱신), 그 밖에는 UNAUTHORIZED.
	static void write(HttpServletResponse response, boolean expired) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		String body = expired ? "{\"code\":\"TOKEN_EXPIRED\",\"message\":\"토큰이 만료됐습니다.\",\"field\":null}"
				: "{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\",\"field\":null}";
		response.getWriter().write(body);
	}

}
