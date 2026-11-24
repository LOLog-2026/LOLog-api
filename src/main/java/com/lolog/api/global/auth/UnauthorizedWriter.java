package com.lolog.api.global.auth;

import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.error.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

// 보안 필터에서 컨트롤러를 거치지 않고 오류 응답 { code, message, field }를 쓴다.
final class UnauthorizedWriter {

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	private UnauthorizedWriter() {
	}

	// 토큰이 없거나 틀리면 UNAUTHORIZED, 만료된 액세스 토큰이면 TOKEN_EXPIRED(앱은 이때만 갱신),
	// 토큰을 확인하는 중에 서버 쪽 문제(Redis)가 생기면 INTERNAL_ERROR(앱은 로그인을 풀지 않고 다시 시도한다).
	static void write(HttpServletResponse response, ErrorCode code) throws IOException {
		response.setStatus(code.status().value());
		response.setContentType(MediaType.APPLICATION_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		response.getWriter().write(JSON.writeValueAsString(ErrorResponse.of(code)));
	}

}
