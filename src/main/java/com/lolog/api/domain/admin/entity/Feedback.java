package com.lolog.api.domain.admin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

// 오류 신고·의견 하나(V12, admin.md). 사용자가 설정 화면에서 보내고 관리자가 처리 상태를 바꾼다.
@Entity
@Table(name = "feedback")
@Getter
@NoArgsConstructor
public class Feedback {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 탈퇴하면 탈퇴 처리가 null로 비운다. 엔티티 저장이 그 값을 되돌리지 않게 넣을 때만 쓴다.
	@Column(name = "user_id", updatable = false)
	private Long userId;

	// BUG, OPINION
	@Column(nullable = false, length = 10)
	private String category;

	@Column(nullable = false, length = 1000)
	private String text;

	@Column(name = "app_version", nullable = false, length = 30)
	private String appVersion;

	@Column(name = "device_model", nullable = false, length = 100)
	private String deviceModel;

	// OPEN, DONE
	@Column(nullable = false, length = 10)
	private String status = "OPEN";

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	/**
	 * 새 오류 신고·의견을 만든다.
	 *
	 * @param userId 보낸 회원 ID
	 * @param category 분류(BUG·OPINION)
	 * @param text 내용
	 * @param appVersion 보낸 앱 버전
	 * @param deviceModel 보낸 기기 모델
	 */
	public Feedback(Long userId, String category, String text, String appVersion, String deviceModel) {
		this.userId = userId;
		this.category = category;
		this.text = text;
		this.appVersion = appVersion;
		this.deviceModel = deviceModel;
	}

	/**
	 * 처리 상태를 바꾼다.
	 *
	 * @param status 새 처리 상태(OPEN·DONE)
	 */
	public void mark(String status) {
		this.status = status;
	}

}
