package com.lolog.api.domain.inbox.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

// 알림 목록·공지사항·새 소식 표시의 응답 모양. LOLog-docs inbox.md 5장(초안)과 같다.
public final class InboxDtos {

	private InboxDtos() {
	}

	// 공지사항 내용.
	public record NoticeContent(String title, String body) {
	}

	// data는 푸시에 넣은 값 그대로(type 뺌)
	public record NotificationContent(String type, Map<String, Object> data) {
	}

	// kind가 NOTICE면 notice, NOTIFICATION이면 notification을 채우고 다른 쪽은 null이다.
	public record InboxItem(String kind, Long id, OffsetDateTime createdAt, boolean read, NoticeContent notice,
			NotificationContent notification) {
	}

	// 알림 목록 한 페이지와 다음 커서.
	public record InboxPage(List<InboxItem> items, String nextCursor) {
	}

	// 안 읽은 알림·공지사항이 있는지.
	public record Unread(boolean hasUnread) {
	}

	// 기능 화면마다 새 소식 점을 켤지.
	public record Badges(boolean cards, boolean comics, boolean profileCharacter, boolean friends, boolean rooms,
			boolean inbox) {
	}

}
