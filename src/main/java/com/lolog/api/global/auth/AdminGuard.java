package com.lolog.api.global.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

// /api/admin/**는 요청 값(경로·본문)을 읽기 전에 관리자인지 먼저 본다(admin.md 1장: 관리자 확인이 400보다 먼저).
// 토큰이 없거나 틀리면 그 앞의 보안 필터가 401로 답한다. 관리자가 아니면 403 FORBIDDEN.
@Configuration(proxyBeanMethods = false)
public class AdminGuard implements HandlerInterceptor, WebMvcConfigurer {

	private final CurrentUserProvider currentUser;

	/** 요청한 회원을 확인하는 도구를 받는다. */
	public AdminGuard(CurrentUserProvider currentUser) {
		this.currentUser = currentUser;
	}

	/**
	 * 관리자 주소에만 이 검사를 건다.
	 *
	 * @param registry 검사를 걸 인터셉터 목록
	 */
	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this).addPathPatterns("/api/admin/**");
	}

	/**
	 * 관리자가 아니면 컨트롤러로 가기 전에 막는다.
	 *
	 * @param request 들어온 요청
	 * @param response 응답(쓰지 않음)
	 * @param handler 요청을 받을 컨트롤러(쓰지 않음)
	 * @return 관리자면 true, 아니면 403 예외를 던진다
	 */
	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		currentUser.requireAdmin();
		return true;
	}

}
