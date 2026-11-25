package com.lolog.api.domain.inbox.controller;

import com.lolog.api.domain.inbox.dto.InboxDtos.Badges;
import com.lolog.api.domain.inbox.dto.InboxDtos.InboxPage;
import com.lolog.api.domain.inbox.dto.InboxDtos.Unread;
import com.lolog.api.domain.inbox.service.InboxService;
import com.lolog.api.global.auth.CurrentUserProvider;
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

	/** 알림 서비스와 현재 사용자 정보를 받는다. */
	public InboxController(InboxService inbox, CurrentUserProvider currentUser) {
		this.inbox = inbox;
		this.currentUser = currentUser;
	}

	/**
	 * 탭에 맞는 알림 목록을 페이지로 돌려준다.
	 *
	 * @param tab 탭(ALL, NOTICE, NOTIFICATION, 없으면 ALL)
	 * @param cursor 앞 페이지 응답의 nextCursor(없으면 첫 페이지)
	 * @return 알림 목록 한 페이지
	 */
	@GetMapping("/api/inbox")
	public InboxPage list(@RequestParam(required = false) String tab, @RequestParam(required = false) String cursor) {
		return inbox.list(currentUser.currentUserId(), tab, cursor);
	}

	// 안 읽은 것이 있는지 돌려준다.
	@GetMapping("/api/inbox/unread")
	public Unread unread() {
		return inbox.unread(currentUser.currentUserId());
	}

	/**
	 * 알림이나 공지사항 하나를 읽음으로 한다.
	 *
	 * @param kind 경로의 종류(notice 또는 notification)
	 * @param id 경로의 공지 ID 또는 알림 ID
	 */
	@PostMapping("/api/inbox/{kind}/{id}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void read(@PathVariable String kind, @PathVariable Long id) {
		inbox.read(currentUser.currentUserId(), kind, id);
	}

	// 모두 읽음으로 한다.
	@PostMapping("/api/inbox/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void readAll() {
		inbox.readAll(currentUser.currentUserId());
	}

	// 새 소식 점 표시를 돌려준다.
	@GetMapping("/api/me/badges")
	public Badges badges() {
		return inbox.badges(currentUser.currentUserId());
	}

	/**
	 * 그 화면을 열었다고 남긴다.
	 *
	 * @param kind 경로의 새 소식 점 종류(cards, comics, profileCharacter, friends, rooms, inbox)
	 */
	@PostMapping("/api/me/badges/{kind}/read")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void seen(@PathVariable String kind) {
		inbox.seen(currentUser.currentUserId(), kind);
	}

}
