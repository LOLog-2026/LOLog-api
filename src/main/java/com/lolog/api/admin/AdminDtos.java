package com.lolog.api.admin;

import java.time.OffsetDateTime;
import java.util.List;

// 관리자·의견 API의 요청·응답 모양. LOLog-docs admin.md 7장(초안)과 같다.
public final class AdminDtos {

	private AdminDtos() {
	}

	// PATCH는 바꾼 것만 보낸다. 길이는 앞뒤 공백을 뺀 뒤에 서비스에서 확인한다.
	public record NoticeRequest(String title, String body) {
	}

	public record Notice(Long id, String title, String body, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	// 닉네임을 정하기 전이면 둘 다 null이다.
	public record AdminUser(String nickname, String friendCode) {
	}

	public record FeedbackRequest(String category, String text, String appVersion, String deviceModel) {
	}

	// userId는 보낸 사람이 탈퇴했으면 null이다.
	public record FeedbackItem(Long id, Long userId, String category, String text, String appVersion,
			String deviceModel, String status, OffsetDateTime createdAt) {
	}

	public record FeedbackPage(List<FeedbackItem> items, String nextCursor) {
	}

	public record FeedbackStatusRequest(String status) {
	}

	// /internal/users/{id}/suspension. 영구 정지면 null이다.
	public record SuspensionRequest(OffsetDateTime suspendedUntil) {
	}

}
