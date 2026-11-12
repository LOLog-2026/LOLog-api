package com.lolog.api.auth;

import com.lolog.api.common.ApiException;
import org.springframework.http.HttpStatus;

// 요청한 사용자의 회원 ID를 돌려준다. 구현은 JwtAuthConfig(액세스 토큰의 sub)다.
public interface CurrentUserProvider {

	long currentUserId();

	// 액세스 토큰의 role 클레임이 ADMIN인지(admin.md 1장, 초안). DB는 보지 않는다.
	default boolean isAdmin() {
		return false;
	}

	// 관리자가 아니면 403 FORBIDDEN. 관리자 주소(/api/admin/**)에서 부른다. 관리자의 회원 ID를 돌려준다.
	default long requireAdmin() {
		long userId = currentUserId();
		if (!isAdmin()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "관리자만 쓸 수 있어요.");
		}
		return userId;
	}

}
