package com.lolog.api.global.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("InternalTokenFilter")
class InternalTokenFilterTest {

	private static final String TOKEN = "internal-test-token";

	private final MockFilterChain chain = new MockFilterChain();
	private final MockHttpServletResponse response = new MockHttpServletResponse();

	private static MockHttpServletRequest request(String header) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/jobs/1/fail");
		if (header != null) {
			request.addHeader(InternalTokenFilter.HEADER, header);
		}
		return request;
	}

	private void assertUnauthorized() throws Exception {
		assertThat(response.getStatus()).isEqualTo(401);
		assertThat(response.getContentAsString()).contains("\"code\":\"UNAUTHORIZED\"");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("토큰이 맞으면 다음 필터로 넘긴다")
	void 토큰이_맞으면_넘긴다() throws Exception {
		// given
		MockHttpServletRequest request = request(TOKEN);

		// when
		new InternalTokenFilter(TOKEN).doFilter(request, response, chain);

		// then
		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("토큰 헤더가 없으면 401 UNAUTHORIZED이고 넘기지 않는다")
	void 토큰이_없으면_401이다() throws Exception {
		// when
		new InternalTokenFilter(TOKEN).doFilter(request(null), response, chain);

		// then
		assertUnauthorized();
	}

	@Test
	@DisplayName("토큰이 틀리면 401이다")
	void 토큰이_틀리면_401이다() throws Exception {
		// when
		new InternalTokenFilter(TOKEN).doFilter(request("internal-test-tokeX"), response, chain);

		// then
		assertUnauthorized();
	}

	@Test
	@DisplayName("맞는 토큰의 앞부분만 보내면 401이다")
	void 토큰_앞부분만_보내면_401이다() throws Exception {
		// when
		new InternalTokenFilter(TOKEN).doFilter(request("internal"), response, chain);

		// then
		assertUnauthorized();
	}

	@Test
	@DisplayName("토큰 앞에 Bearer를 붙이면 401이다")
	void Bearer를_붙이면_401이다() throws Exception {
		// when
		new InternalTokenFilter(TOKEN).doFilter(request("Bearer " + TOKEN), response, chain);

		// then
		assertUnauthorized();
	}

	@Test
	@DisplayName("Authorization에 액세스 토큰만 있고 내부 토큰이 없으면 401이다")
	void 액세스_토큰만_있으면_401이다() throws Exception {
		// given
		MockHttpServletRequest request = request(null);
		request.addHeader("Authorization", TestTokens.bearer(1));

		// when
		new InternalTokenFilter(TOKEN).doFilter(request, response, chain);

		// then
		assertUnauthorized();
	}

	@Test
	@DisplayName("설정 토큰이 빈 문자열이면 빈 헤더를 보내도 401이다")
	void 설정_토큰이_비면_빈_헤더도_401이다() throws Exception {
		// when
		new InternalTokenFilter("").doFilter(request(""), response, chain);

		// then
		assertUnauthorized();
	}

	@Test
	@DisplayName("설정 토큰이 빈 문자열이면 헤더가 없어도 401이다")
	void 설정_토큰이_비면_헤더_없이도_401이다() throws Exception {
		// when
		new InternalTokenFilter("").doFilter(request(null), response, chain);

		// then
		assertUnauthorized();
	}

}
