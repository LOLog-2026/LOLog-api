package com.lolog.api.blog;

import com.lolog.api.auth.CurrentUserProvider;
import com.lolog.api.blog.BlogDtos.Blog;
import com.lolog.api.blog.BlogDtos.BlogCheck;
import com.lolog.api.blog.BlogDtos.BlogCheckRequest;
import com.lolog.api.blog.BlogDtos.BlogCreateRequest;
import com.lolog.api.blog.BlogDtos.BlogList;
import com.lolog.api.blog.BlogDtos.BlogPostPage;
import com.lolog.api.blog.BlogDtos.BlogUpdateRequest;
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

	public BlogController(BlogService blogs, CurrentUserProvider currentUser) {
		this.blogs = blogs;
		this.currentUser = currentUser;
	}

	@PostMapping("/api/blogs/check")
	public BlogCheck check(@RequestBody BlogCheckRequest request) {
		currentUser.currentUserId();
		return blogs.check(request.url());
	}

	@PostMapping("/api/blogs")
	@ResponseStatus(HttpStatus.CREATED)
	public Blog create(@RequestBody BlogCreateRequest request) {
		return blogs.create(currentUser.currentUserId(), request.url(), request.categories());
	}

	@GetMapping("/api/blogs")
	public BlogList list() {
		return new BlogList(blogs.list(currentUser.currentUserId()));
	}

	@PatchMapping("/api/blogs/{id}")
	public Blog update(@PathVariable Long id, @RequestBody BlogUpdateRequest request) {
		return blogs.update(currentUser.currentUserId(), id, request.categories());
	}

	@DeleteMapping("/api/blogs/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		blogs.delete(currentUser.currentUserId(), id);
	}

	@GetMapping("/api/blogs/{id}/posts")
	public BlogPostPage posts(@PathVariable Long id, @RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size) {
		return blogs.posts(currentUser.currentUserId(), id, cursor, size);
	}

}
