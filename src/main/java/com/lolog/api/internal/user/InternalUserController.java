package com.lolog.api.internal.user;

import com.lolog.api.domain.admin.dto.AdminDtos.SuspensionRequest;
import com.lolog.api.domain.admin.service.AdminService;
import com.lolog.api.domain.user.service.UserDataService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 인증 서비스(lolog-auth)가 탈퇴·정지·정지 풀기 때 부른다. X-Internal-Token 검사는 InternalSecurityConfig가 한다.
@RestController
public class InternalUserController {

	private final UserDataService userData;
	private final AdminService admin;

	/** 쓰는 서비스를 받는다. */
	public InternalUserController(UserDataService userData, AdminService admin) {
		this.userData = userData;
		this.admin = admin;
	}

	/**
	 * 정지됐다고 알림(admin.md /internal, 초안). 본문 { suspendedUntil }, 영구면 null. 방 멤버의 "정지된 계정" 표시에 쓴다.
	 *
	 * @param id 정지된 회원 ID(경로)
	 * @param request 본문 { suspendedUntil }, 영구 정지면 suspendedUntil이 null
	 */
	@PutMapping("/internal/users/{id}/suspension")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void suspend(@PathVariable Long id, @RequestBody SuspensionRequest request) {
		admin.suspend(id, request.suspendedUntil());
	}

	/**
	 * 관리자가 먼저 풀었다고 알림. 정지된 적 없어도 204다.
	 *
	 * @param id 정지가 풀린 회원 ID(경로)
	 */
	@DeleteMapping("/internal/users/{id}/suspension")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void unsuspend(@PathVariable Long id) {
		admin.unsuspend(id);
	}

	/**
	 * 지울 게 없어도 204다.
	 *
	 * @param id 탈퇴한 회원 ID(경로)
	 */
	@DeleteMapping("/internal/users/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		userData.delete(id);
	}

}
