package com.lolog.api.support;

import com.lolog.api.global.auth.InternalSecurityConfig;
import com.lolog.api.global.auth.JwtAuthConfig;
import com.lolog.api.global.auth.TestTokens;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

// 앱용 컨트롤러 테스트(@WebMvcTest)의 공통 설정. 하위 클래스에 @WebMvcTest(XxxController.class)를 단다.
// 보안은 실제 필터 체인(JwtAuthConfig, InternalSecurityConfig)을 올리고, 탈퇴·정지 확인용 Redis만 목으로 둔다.
// Redis 목은 기본으로 0을 돌려주므로 토큰은 탈퇴·정지되지 않은 회원의 것으로 본다.
@Import({ JwtAuthConfig.class, InternalSecurityConfig.class })
public abstract class AppControllerTestSupport {

	protected static final long USER_ID = 7L;

	protected static final String AUTH = "Authorization";

	@Autowired
	protected MockMvc mvc;

	@MockitoBean
	protected StringRedisTemplate redis;

	// 일반 회원의 Authorization 헤더 값
	protected static String user() {
		return TestTokens.bearer(USER_ID);
	}

	// 관리자의 Authorization 헤더 값
	protected static String admin() {
		return TestTokens.adminBearer(USER_ID);
	}

}
