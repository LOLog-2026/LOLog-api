package com.lolog.api.domain.admin.dto;

import java.time.OffsetDateTime;
import java.util.List;

// 관리자·의견 API의 요청·응답 모양. LOLog-docs admin.md 7장(초안)과 같다.
public final class AdminDtos {

	private AdminDtos() {
	}

	/**
	 * PATCH는 바꾼 것만 보낸다. 길이는 앞뒤 공백을 뺀 뒤에 서비스에서 확인한다.
	 *
	 * @param title 공지사항 제목(1~100자). PATCH에서 바꾸지 않으면 null
	 * @param body 공지사항 본문. PATCH에서 바꾸지 않으면 null
	 */
	public record NoticeRequest(String title, String body) {
	}

	/**
	 * 공지사항 응답.
	 *
	 * @param id 공지사항 ID
	 * @param title 제목
	 * @param body 본문
	 * @param createdAt 만든 일시
	 * @param updatedAt 마지막으로 고친 일시
	 */
	public record Notice(Long id, String title, String body, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
	}

	/**
	 * 닉네임을 정하기 전이면 둘 다 null이다.
	 *
	 * @param nickname 회원 닉네임
	 * @param friendCode 회원 친구 코드
	 */
	public record AdminUser(String nickname, String friendCode) {
	}

	/**
	 * 오류 신고·의견 보내기 요청.
	 *
	 * @param category 분류(BUG·OPINION)
	 * @param text 내용(1~1000자)
	 * @param appVersion 보낸 앱 버전(30자까지)
	 * @param deviceModel 보낸 기기 모델(100자까지)
	 */
	public record FeedbackRequest(String category, String text, String appVersion, String deviceModel) {
	}

	/**
	 * userId는 보낸 사람이 탈퇴했으면 null이다.
	 *
	 * @param id 오류 신고·의견 ID
	 * @param userId 보낸 회원 ID. 탈퇴했으면 null
	 * @param category 분류(BUG·OPINION)
	 * @param text 내용
	 * @param appVersion 보낸 앱 버전
	 * @param deviceModel 보낸 기기 모델
	 * @param status 처리 상태(OPEN·DONE)
	 * @param createdAt 보낸 일시
	 */
	public record FeedbackItem(Long id, Long userId, String category, String text, String appVersion,
			String deviceModel, String status, OffsetDateTime createdAt) {
	}

	/**
	 * 오류 신고·의견 목록 한 페이지.
	 *
	 * @param items 최신순 오류 신고·의견
	 * @param nextCursor 다음 페이지를 받을 cursor. 마지막 페이지면 null
	 */
	public record FeedbackPage(List<FeedbackItem> items, String nextCursor) {
	}

	/**
	 * 처리 상태(OPEN·DONE) 바꾸기 요청.
	 *
	 * @param status 바꿀 처리 상태(OPEN·DONE)
	 */
	public record FeedbackStatusRequest(String status) {
	}

	/**
	 * /internal/users/{id}/suspension. 영구 정지면 null이다.
	 *
	 * @param suspendedUntil 정지가 풀리는 일시. 영구 정지면 null
	 */
	public record SuspensionRequest(OffsetDateTime suspendedUntil) {
	}

}
