package com.lolog.api.auth;

// 요청한 사용자의 회원 ID를 돌려준다. JWT 검증(#9)이 붙으면 토큰에서 읽는 구현으로 바꾼다.
public interface CurrentUserProvider {

	long currentUserId();

}
