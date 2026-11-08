package com.lolog.api.user;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 인증 서비스(lolog-auth)가 탈퇴 때 부른다. X-Internal-Token 검사는 InternalSecurityConfig가 한다.
@RestController
public class InternalUserController {

	private final UserDataService userData;

	public InternalUserController(UserDataService userData) {
		this.userData = userData;
	}

	// 지울 게 없어도 204다.
	@DeleteMapping("/internal/users/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		userData.delete(id);
	}

}
