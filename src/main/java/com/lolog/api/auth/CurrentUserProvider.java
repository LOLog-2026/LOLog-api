package com.lolog.api.auth;

// 요청한 사용자의 회원 ID를 돌려준다. 구현은 JwtAuthConfig(액세스 토큰의 sub)다.
public interface CurrentUserProvider {

	long currentUserId();

}
