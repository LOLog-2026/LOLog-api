package com.lolog.api.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

// 보안 필터에서 컨트롤러를 거치지 않고 401(또는 500) { code, message, field }를 쓴다.
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

	// 토큰을 확인하는 중에 서버 쪽 문제(Redis)가 생기면 500 INTERNAL_ERROR. 앱은 로그인을 풀지 않고 다시 시도한다.
	static void writeInternalError(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"code\":\"INTERNAL_ERROR\",\"message\":\"서버 오류가 발생했습니다.\",\"field\":null}");
	}

}
