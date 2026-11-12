package com.lolog.api.inbox;

import com.lolog.api.auth.CurrentUserProvider;
import com.lolog.api.inbox.InboxDtos.Badges;
import com.lolog.api.inbox.InboxDtos.InboxPage;
import com.lolog.api.inbox.InboxDtos.Unread;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 알림 목록과 공지사항, 앱 안 새 소식 표시 API(inbox.md 5장, 초안). 모두 로그인이 필요하다.
@RestController
public class InboxController {

	private final InboxService inbox;
	private final CurrentUserProvider currentUser;

	public InboxController(InboxService inbox, CurrentUserProvider currentUser) {
		this.inbox = inbox;
		this.currentUser = currentUser;
	}

	@GetMapping("/api/inbox")
	public InboxPage list(@RequestParam(required = false) String tab, @RequestParam(required = false) String cursor) {
		return inbox.list(currentUser.currentUserId(), tab, cursor);
	}

	@GetMapping("/api/inbox/unread")
	public Unread unread() {
		return inbox.unread(currentUser.currentUserId());
	}

	@PostMapping("/api/inbox/{kind}/{id}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void read(@PathVariable String kind, @PathVariable Long id) {
		inbox.read(currentUser.currentUserId(), kind, id);
	}

	@PostMapping("/api/inbox/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void readAll() {
		inbox.readAll(currentUser.currentUserId());
	}

	@GetMapping("/api/me/badges")
	public Badges badges() {
		return inbox.badges(currentUser.currentUserId());
	}

	@PostMapping("/api/me/badges/{kind}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void seen(@PathVariable String kind) {
		inbox.seen(currentUser.currentUserId(), kind);
	}

}
