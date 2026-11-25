package com.lolog.api.global.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("UploadSizeFilter")
class UploadSizeFilterTest {

	private static final int ONE_MB = 1024 * 1024;

	private final UploadSizeFilter filter = new UploadSizeFilter();
	private final MockFilterChain chain = new MockFilterChain();
	private final MockHttpServletResponse response = new MockHttpServletResponse();

	private static MockHttpServletRequest request(String method, String uri, int bytes) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
		request.setContent(new byte[bytes]);
		return request;
	}

	@Test
	@DisplayName("POST /api/recordings 본문이 1MB+1바이트면 413 PAYLOAD_TOO_LARGE이고 넘기지 않는다")
	void 녹음_업로드가_1MB를_넘으면_413이다() throws Exception {
		// when
		filter.doFilter(request("POST", "/api/recordings", ONE_MB + 1), response, chain);

		// then
		assertThat(response.getStatus()).isEqualTo(413);
		assertThat(response.getContentAsString()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
		assertThat(chain.getRequest()).isNull();
	}

	@Test
	@DisplayName("POST /api/recordings 본문이 딱 1MB면 넘긴다")
	void 녹음_업로드가_1MB면_넘긴다() throws Exception {
		// given
		MockHttpServletRequest request = request("POST", "/api/recordings", ONE_MB);

		// when
		filter.doFilter(request, response, chain);

		// then
		assertThat(chain.getRequest()).isSameAs(request);
	}

	@Test
	@DisplayName("다른 경로는 1MB를 넘어도 넘긴다")
	void 다른_경로는_넘긴다() throws Exception {
		// given
		MockHttpServletRequest request = request("POST", "/api/recordings/1/parts", ONE_MB + 1);

		// when
		filter.doFilter(request, response, chain);

		// then
		assertThat(chain.getRequest()).isSameAs(request);
		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	@DisplayName("같은 경로라도 POST가 아니면 넘긴다")
	void POST가_아니면_넘긴다() throws Exception {
		// given
		MockHttpServletRequest request = request("PUT", "/api/recordings", ONE_MB + 1);

		// when
		filter.doFilter(request, response, chain);

		// then
		assertThat(chain.getRequest()).isSameAs(request);
	}

	@Test
	@DisplayName("Content-Length가 없으면 넘긴다(Nginx가 막는다)")
	void Content_Length가_없으면_넘긴다() throws Exception {
		// given
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/recordings");

		// when
		filter.doFilter(request, response, chain);

		// then
		assertThat(request.getContentLengthLong()).isEqualTo(-1);
		assertThat(chain.getRequest()).isSameAs(request);
	}

}
