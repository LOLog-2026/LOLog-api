package com.lolog.api.comic;

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
import org.hibernate.annotations.UpdateTimestamp;

// 카드로 만든 4컷만화. 콘티·이미지 칸은 #6에서 붙인다.
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

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public Comic(Long cardId, Long userId, String cardSummary) {
		this.cardId = cardId;
		this.userId = userId;
		this.cardSummary = cardSummary;
	}

	public void fail(String reason) {
		status = ComicStatus.FAILED;
		failReason = reason;
	}

}
