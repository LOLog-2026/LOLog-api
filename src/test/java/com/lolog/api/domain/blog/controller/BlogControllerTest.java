package com.lolog.api.domain.blog.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.blog.dto.BlogDtos.Blog;
import com.lolog.api.domain.blog.service.BlogService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.support.AppControllerTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("BlogController")
@WebMvcTest(BlogController.class)
class BlogControllerTest extends AppControllerTestSupport {

	@MockitoBean
	BlogService blogs;

	@Test
	@DisplayName("토큰 없이 블로그 목록을 부르면 401 UNAUTHORIZED다")
	void 토큰_없이_부르면_401이다() throws Exception {
		// when & then
		mvc.perform(get("/api/blogs")).andExpect(status().isUnauthorized());

		verifyNoInteractions(blogs);
	}

	@Test
	@DisplayName("블로그 확인은 본문의 url만 넘긴다")
	void 블로그_확인은_url을_넘긴다() throws Exception {
		// when & then
		mvc.perform(post("/api/blogs/check").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"https://a.tistory.com\"}"))
			.andExpect(status().isOk());

		verify(blogs).check("https://a.tistory.com");
	}

	@Test
	@DisplayName("블로그를 연결하면 201이고 내 회원 ID와 url, 카테고리를 넘긴다")
	void 연결하면_201이다() throws Exception {
		// when & then
		mvc.perform(post("/api/blogs").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"https://a.tistory.com\",\"categories\":[\"일상\"]}"))
			.andExpect(status().isCreated());

		verify(blogs).create(USER_ID, "https://a.tistory.com", List.of("일상"));
	}

	@Test
	@DisplayName("블로그 목록은 서비스의 목록을 blogs로 감싸 답한다")
	void 목록은_blogs로_감싼다() throws Exception {
		// given
		when(blogs.list(USER_ID)).thenReturn(List.of(new Blog(3L, "https://a.tistory.com", "내 블로그", List.of(), true)));

		// when & then
		mvc.perform(get("/api/blogs").header(AUTH, user()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.blogs[0].id").value(3))
			.andExpect(jsonPath("$.blogs[0].optimized").value(true));
	}

	@Test
	@DisplayName("카테고리를 고칠 때 categories를 빼면 null로 넘긴다")
	void 카테고리를_빼면_null로_넘긴다() throws Exception {
		// when & then
		mvc.perform(patch("/api/blogs/3").header(AUTH, user()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isOk());

		verify(blogs).update(USER_ID, 3L, null);
	}

	@Test
	@DisplayName("블로그 연결을 끊으면 204다")
	void 끊으면_204다() throws Exception {
		// when & then
		mvc.perform(delete("/api/blogs/3").header(AUTH, user())).andExpect(status().isNoContent());

		verify(blogs).delete(USER_ID, 3L);
	}

	@Test
	@DisplayName("글 목록은 cursor와 size를 그대로 넘긴다")
	void 글_목록은_cursor와_size를_넘긴다() throws Exception {
		// when & then
		mvc.perform(get("/api/blogs/3/posts").param("cursor", "10").param("size", "5").header(AUTH, user()))
			.andExpect(status().isOk());

		verify(blogs).posts(USER_ID, 3L, "10", 5);
	}

	@Test
	@DisplayName("글 목록의 size가 숫자가 아니면 400 INVALID_REQUEST이고 field는 size다")
	void size가_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(get("/api/blogs/3/posts").param("size", "abc").header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("size"));

		verifyNoInteractions(blogs);
	}

	@Test
	@DisplayName("서비스가 BLOG_NOT_FOUND를 던지면 404로 답한다")
	void 없는_블로그는_404다() throws Exception {
		// given
		when(blogs.posts(USER_ID, 9L, null, null)).thenThrow(new ApiException(ErrorCode.BLOG_NOT_FOUND));

		// when & then
		mvc.perform(get("/api/blogs/9/posts").header(AUTH, user()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("BLOG_NOT_FOUND"));
	}

}
