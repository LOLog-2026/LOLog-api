package com.lolog.api.domain.inbox.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

// 알림 목록·공지사항·새 소식 표시의 응답 모양. LOLog-docs inbox.md 5장(초안)과 같다.
public final class InboxDtos {

	private InboxDtos() {
	}

	/**
	 * 공지사항 내용.
	 *
	 * @param title 공지 제목
	 * @param body 공지 본문
	 */
	public record NoticeContent(String title, String body) {
	}

	/**
	 * data는 푸시에 넣은 값 그대로(type 뺌)
	 *
	 * @param type 알림 종류(COMIC_DONE 등 푸시의 type)
	 * @param data 푸시에 넣은 값 그대로(type 뺌)
	 */
	public record NotificationContent(String type, Map<String, Object> data) {
	}

	/**
	 * kind가 NOTICE면 notice, NOTIFICATION이면 notification을 채우고 다른 쪽은 null이다.
	 *
	 * @param kind 항목 종류(NOTICE 또는 NOTIFICATION)
	 * @param id 공지 ID 또는 알림 ID
	 * @param createdAt 공지·알림이 생긴 시각
	 * @param read 이 회원이 읽었으면 true
	 * @param notice 공지 내용(kind가 NOTICE가 아니면 null)
	 * @param notification 알림 내용(kind가 NOTIFICATION이 아니면 null)
	 */
	public record InboxItem(String kind, Long id, OffsetDateTime createdAt, boolean read, NoticeContent notice,
			NotificationContent notification) {
	}

	/**
	 * 알림 목록 한 페이지와 다음 커서.
	 *
	 * @param items 이 페이지의 항목(최근 것이 위)
	 * @param nextCursor 다음 페이지를 받을 커서(마지막 페이지면 null)
	 */
	public record InboxPage(List<InboxItem> items, String nextCursor) {
	}

	/**
	 * 안 읽은 알림·공지사항이 있는지.
	 *
	 * @param hasUnread 안 읽은 알림이나 공지사항이 있으면 true
	 */
	public record Unread(boolean hasUnread) {
	}

	/**
	 * 기능 화면마다 새 소식 점을 켤지.
	 *
	 * @param cards 보관함 점
	 * @param comics 만화(컨텐츠) 화면 점
	 * @param profileCharacter 프로필 캐릭터 화면 점
	 * @param friends 친구 화면 점
	 * @param rooms 방 화면 점
	 * @param inbox 종 버튼 점
	 */
	public record Badges(boolean cards, boolean comics, boolean profileCharacter, boolean friends, boolean rooms,
			boolean inbox) {
	}

}
