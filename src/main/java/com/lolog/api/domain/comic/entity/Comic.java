package com.lolog.api.domain.comic.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

// 카드로 만든 4컷만화. 워커가 카드 내용을 가져가면 STORYBOARD, 콘티를 보내면 DRAWING, 이미지를 보내면 DONE이다.
@Entity
@Table(name = "comics")
@Getter
@NoArgsConstructor
public class Comic {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 카드를 지우면 DB가 null로 바꾼다(ON DELETE SET NULL).
	@Column(name = "card_id")
	private Long cardId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private ComicStatus status = ComicStatus.QUEUED;

	// 만화를 요청할 때 카드 요약을 복사해 둔다. 카드를 지워도 만화 응답에 쓴다.
	@Column(name = "card_summary", columnDefinition = "text")
	private String cardSummary;

	// 서버 기록용. 앱에는 보내지 않는다.
	@Column(name = "fail_reason", length = 500)
	private String failReason;

	// 워커가 보낸 콘티 JSON(LOLog-docs spec/storyboard.md v1). 서버는 기본만 검사하고 그대로 둔다.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "json")
	private String storyboard;

	// 저장소(S3 호환) 객체 키. DONE이 되면 생긴다.
	@Column(name = "image_key", length = 255)
	private String imageKey;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	/**
	 * 카드로 새 만화를 만든다. 처음 상태는 QUEUED다.
	 *
	 * @param cardId 만화로 만들 카드 ID
	 * @param userId 만화를 요청한 회원 ID
	 * @param cardSummary 요청할 때의 카드 요약
	 */
	public Comic(Long cardId, Long userId, String cardSummary) {
		this.cardId = cardId;
		this.userId = userId;
		this.cardSummary = cardSummary;
	}

	// 끝난 만화(DONE·FAILED)인지 본다.
	public boolean isFinished() {
		return status == ComicStatus.DONE || status == ComicStatus.FAILED;
	}

	// 워커가 콘티를 만들기 시작했다고 표시한다.
	public void startStoryboard() {
		status = ComicStatus.STORYBOARD;
	}

	/**
	 * 콘티를 저장하고 그리기 단계로 넘긴다.
	 *
	 * @param json 워커가 보낸 콘티 JSON
	 */
	public void saveStoryboard(String json) {
		storyboard = json;
		status = ComicStatus.DRAWING;
	}

	/**
	 * 이미지 키를 저장하고 완료로 바꾼다.
	 *
	 * @param key 저장소에 올린 만화 이미지 객체 키
	 */
	public void finish(String key) {
		imageKey = key;
		status = ComicStatus.DONE;
	}

	/**
	 * 실패로 바꾸고 이유를 남긴다.
	 *
	 * @param reason 남길 실패 이유(서버 기록용)
	 */
	public void fail(String reason) {
		status = ComicStatus.FAILED;
		failReason = reason;
	}

}
