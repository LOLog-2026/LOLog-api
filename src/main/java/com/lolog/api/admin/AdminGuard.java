package com.lolog.api.admin;

import com.lolog.api.auth.CurrentUserProvider;
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

	public AdminGuard(CurrentUserProvider currentUser) {
		this.currentUser = currentUser;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(this).addPathPatterns("/api/admin/**");
	}

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
		currentUser.requireAdmin();
		return true;
	}

}
