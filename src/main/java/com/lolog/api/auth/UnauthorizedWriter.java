package com.lolog.api.auth;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;

// 보안 필터에서 컨트롤러를 거치지 않고 401 { code, message, field }를 쓴다.
final class UnauthorizedWriter {

	private UnauthorizedWriter() {
	}

	static void write(HttpServletResponse response) throws IOException {
		response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"인증이 필요합니다.\",\"field\":null}");
	}

}
