package com.lolog.api.domain.blog.controller;

import com.lolog.api.domain.blog.dto.BlogDtos.Blog;
import com.lolog.api.domain.blog.dto.BlogDtos.BlogCheck;
import com.lolog.api.domain.blog.dto.BlogDtos.BlogCheckRequest;
import com.lolog.api.domain.blog.dto.BlogDtos.BlogCreateRequest;
import com.lolog.api.domain.blog.dto.BlogDtos.BlogList;
import com.lolog.api.domain.blog.dto.BlogDtos.BlogPostPage;
import com.lolog.api.domain.blog.dto.BlogDtos.BlogUpdateRequest;
import com.lolog.api.domain.blog.service.BlogService;
import com.lolog.api.global.auth.CurrentUserProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 블로그 연결 API(texts.md 6장). 로그인이 필요하고 내 블로그만 보인다.
@RestController
public class BlogController {

	private final BlogService blogs;
	private final CurrentUserProvider currentUser;

	/** 쓰는 서비스와 현재 회원 도구를 받는다. */
	public BlogController(BlogService blogs, CurrentUserProvider currentUser) {
		this.blogs = blogs;
		this.currentUser = currentUser;
	}

	/**
	 * 블로그 주소를 확인하고 고를 수 있는 카테고리를 돌려준다.
	 *
	 * @param request 확인할 블로그 주소
	 * @return 찾은 RSS 주소, 고를 수 있는 카테고리, 따로 맞춘 플랫폼인지
	 */
	@PostMapping("/api/blogs/check")
	public BlogCheck check(@RequestBody BlogCheckRequest request) {
		currentUser.currentUserId();
		return blogs.check(request.url());
	}

	/**
	 * 블로그를 연결한다.
	 *
	 * @param request 연결할 블로그 주소와 고른 카테고리(3개까지, 비면 전부 받기)
	 * @return 연결한 블로그
	 */
	@PostMapping("/api/blogs")
	@ResponseStatus(HttpStatus.CREATED)
	public Blog create(@RequestBody BlogCreateRequest request) {
		return blogs.create(currentUser.currentUserId(), request.url(), request.categories());
	}

	/**
	 * 내 블로그 목록을 돌려준다.
	 *
	 * @return 연결한 순서대로 내 블로그 목록
	 */
	@GetMapping("/api/blogs")
	public BlogList list() {
		return new BlogList(blogs.list(currentUser.currentUserId()));
	}

	/**
	 * 가져올 카테고리를 바꾼다.
	 *
	 * @param id 바꿀 내 블로그 ID
	 * @param request 새로 고른 카테고리 전체(3개까지, 비면 전부 받기)
	 * @return 바꾼 뒤의 블로그
	 */
	@PatchMapping("/api/blogs/{id}")
	public Blog update(@PathVariable Long id, @RequestBody BlogUpdateRequest request) {
		return blogs.update(currentUser.currentUserId(), id, request.categories());
	}

	/**
	 * 블로그 연결을 끊는다.
	 *
	 * @param id 끊을 내 블로그 ID
	 */
	@DeleteMapping("/api/blogs/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		blogs.delete(currentUser.currentUserId(), id);
	}

	/**
	 * 블로그에서 가져온 글 목록을 페이지로 돌려준다.
	 *
	 * @param id 글을 볼 내 블로그 ID
	 * @param cursor 앞 페이지에서 받은 nextCursor. 없으면 처음부터
	 * @param size 한 페이지 글 수(1~50). 없으면 20
	 * @return 최신순 글 목록 한 페이지와 다음 cursor
	 */
	@GetMapping("/api/blogs/{id}/posts")
	public BlogPostPage posts(@PathVariable Long id, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return blogs.posts(currentUser.currentUserId(), id, cursor, size);
	}

}
