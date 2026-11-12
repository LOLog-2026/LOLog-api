package com.lolog.api.admin;

import com.lolog.api.admin.AdminDtos.AdminUser;
import com.lolog.api.admin.AdminDtos.FeedbackItem;
import com.lolog.api.admin.AdminDtos.FeedbackPage;
import com.lolog.api.admin.AdminDtos.FeedbackRequest;
import com.lolog.api.admin.AdminDtos.FeedbackStatusRequest;
import com.lolog.api.admin.AdminDtos.Notice;
import com.lolog.api.admin.AdminDtos.NoticeRequest;
import com.lolog.api.auth.CurrentUserProvider;
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

	public AdminController(AdminService admin, CurrentUserProvider currentUser) {
		this.admin = admin;
		this.currentUser = currentUser;
	}

	@PostMapping("/api/admin/notices")
	@ResponseStatus(HttpStatus.CREATED)
	public Notice createNotice(@RequestBody NoticeRequest request) {
		currentUser.requireAdmin();
		return admin.createNotice(request.title(), request.body());
	}

	@PatchMapping("/api/admin/notices/{id}")
	public Notice updateNotice(@PathVariable Long id, @RequestBody NoticeRequest request) {
		currentUser.requireAdmin();
		return admin.updateNotice(id, request.title(), request.body());
	}

	@DeleteMapping("/api/admin/notices/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteNotice(@PathVariable Long id) {
		currentUser.requireAdmin();
		admin.deleteNotice(id);
	}

	@GetMapping("/api/admin/users/{id}")
	public AdminUser user(@PathVariable Long id) {
		currentUser.requireAdmin();
		return admin.user(id);
	}

	@GetMapping("/api/admin/feedback")
	public FeedbackPage feedback(@RequestParam(required = false) String status,
			@RequestParam(required = false) String cursor) {
		currentUser.requireAdmin();
		return admin.feedbackList(status, cursor);
	}

	@PatchMapping("/api/admin/feedback/{id}")
	public FeedbackItem markFeedback(@PathVariable Long id, @RequestBody FeedbackStatusRequest request) {
		currentUser.requireAdmin();
		return admin.markFeedback(id, request.status());
	}

	@PostMapping("/api/feedback")
	@ResponseStatus(HttpStatus.CREATED)
	public FeedbackItem sendFeedback(@RequestBody FeedbackRequest request) {
		return admin.send(currentUser.currentUserId(), request);
	}

}
