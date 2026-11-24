package com.lolog.api.domain.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 알림 목록의 알림 하나(inbox.md 1장). 30일이 지나면 NotificationCleanupJob이 지운다.
// 넣기는 created_at을 문장 실행 시각(clock_timestamp)으로 정해야 해서 리포지토리의 native query로 만든다.
@Entity
@Table(name = "notifications")
@Getter
@NoArgsConstructor
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	// COMIC_DONE 등 푸시의 type
	@Column(nullable = false, length = 30)
	private String type;

	// type을 뺀 푸시 값(JSON 객체)
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "jsonb")
	private String data;

	@Column(name = "read_at")
	private OffsetDateTime readAt;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	// 읽음으로 바꾼다. 이미 읽었으면 처음 읽은 시각을 그대로 둔다.
	public void markRead() {
		if (readAt == null) {
			readAt = OffsetDateTime.now();
		}
	}

}
