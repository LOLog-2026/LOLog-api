package com.lolog.api.domain.admin.controller;

import com.lolog.api.domain.admin.dto.AdminDtos.AdminUser;
import com.lolog.api.domain.admin.dto.AdminDtos.FeedbackItem;
import com.lolog.api.domain.admin.dto.AdminDtos.FeedbackPage;
import com.lolog.api.domain.admin.dto.AdminDtos.FeedbackRequest;
import com.lolog.api.domain.admin.dto.AdminDtos.FeedbackStatusRequest;
import com.lolog.api.domain.admin.dto.AdminDtos.Notice;
import com.lolog.api.domain.admin.dto.AdminDtos.NoticeRequest;
import com.lolog.api.domain.admin.service.AdminService;
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

// 관리자 API(admin.md 7장, 초안). /api/admin/**는 토큰의 role이 ADMIN이 아니면 403 FORBIDDEN.
// 의견 보내기(/api/feedback)는 로그인한 누구나 쓴다.
@RestController
public class AdminController {

	private final AdminService admin;
	private final CurrentUserProvider currentUser;

	/** 쓰는 서비스와 현재 회원 도구를 받는다. */
	public AdminController(AdminService admin, CurrentUserProvider currentUser) {
		this.admin = admin;
		this.currentUser = currentUser;
	}

	/**
	 * 공지사항을 만든다.
	 *
	 * @param request 새 공지사항의 제목과 본문
	 * @return 만든 공지사항
	 */
	@PostMapping("/api/admin/notices")
	@ResponseStatus(HttpStatus.CREATED)
	public Notice createNotice(@RequestBody NoticeRequest request) {
		currentUser.requireAdmin();
		return admin.createNotice(request.title(), request.body());
	}

	/**
	 * 공지사항을 고친다.
	 *
	 * @param id 고칠 공지사항 ID
	 * @param request 바꿀 제목·본문. 바꾸지 않는 것은 null
	 * @return 고친 뒤의 공지사항
	 */
	@PatchMapping("/api/admin/notices/{id}")
	public Notice updateNotice(@PathVariable Long id, @RequestBody NoticeRequest request) {
		currentUser.requireAdmin();
		return admin.updateNotice(id, request.title(), request.body());
	}

	/**
	 * 공지사항을 지운다.
	 *
	 * @param id 지울 공지사항 ID
	 */
	@DeleteMapping("/api/admin/notices/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteNotice(@PathVariable Long id) {
		currentUser.requireAdmin();
		admin.deleteNotice(id);
	}

	/**
	 * 회원의 닉네임과 친구 코드를 돌려준다.
	 *
	 * @param id 조회할 회원 ID
	 * @return 닉네임과 친구 코드. 닉네임을 정하기 전이면 둘 다 null
	 */
	@GetMapping("/api/admin/users/{id}")
	public AdminUser user(@PathVariable Long id) {
		currentUser.requireAdmin();
		return admin.user(id);
	}

	/**
	 * 오류 신고·의견 목록을 페이지로 돌려준다.
	 *
	 * @param status 처리 상태 필터(OPEN·DONE). 없으면 전부
	 * @param cursor 앞 페이지에서 받은 nextCursor. 없으면 처음부터
	 * @return 최신순 목록 한 페이지와 다음 cursor
	 */
	@GetMapping("/api/admin/feedback")
	public FeedbackPage feedback(@RequestParam(required = false) String status,
			@RequestParam(required = false) String cursor) {
		currentUser.requireAdmin();
		return admin.feedbackList(status, cursor);
	}

	/**
	 * 오류 신고·의견의 처리 상태를 바꾼다.
	 *
	 * @param id 바꿀 오류 신고·의견 ID
	 * @param request 바꿀 처리 상태(OPEN·DONE)
	 * @return 상태를 바꾼 오류 신고·의견
	 */
	@PatchMapping("/api/admin/feedback/{id}")
	public FeedbackItem markFeedback(@PathVariable Long id, @RequestBody FeedbackStatusRequest request) {
		currentUser.requireAdmin();
		return admin.markFeedback(id, request.status());
	}

	/**
	 * 로그인한 회원이 오류 신고·의견을 보낸다.
	 *
	 * @param request 분류(BUG·OPINION), 내용, 앱 버전, 기기 모델
	 * @return 저장한 오류 신고·의견
	 */
	@PostMapping("/api/feedback")
	@ResponseStatus(HttpStatus.CREATED)
	public FeedbackItem sendFeedback(@RequestBody FeedbackRequest request) {
		return admin.send(currentUser.currentUserId(), request);
	}

}
