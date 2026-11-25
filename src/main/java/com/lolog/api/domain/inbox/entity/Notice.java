package com.lolog.api.domain.inbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;

// 모든 사용자에게 보이는 공지 하나(inbox.md 2장). 운영자가 쓰고 고친다(admin.md).
@Entity
@Table(name = "notices")
@Getter
@NoArgsConstructor
public class Notice {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String title;

	@Column(nullable = false, columnDefinition = "text")
	private String body;

	// DB 시계(clock_timestamp, V22)로 넣고, 넣은 뒤 다시 읽는다. 알림함에서 알림(DB 시계)과 시각 순으로 섞어 보여 주기 때문이다.
	@Generated
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	// 처음에는 DB 기본값이고, 고치면 edit이 바꾼다. 응답에만 쓰고 다른 시각과 비교하지 않는다.
	@Generated(event = EventType.INSERT)
	@Column(name = "updated_at", nullable = false, insertable = false)
	private OffsetDateTime updatedAt;

	/**
	 * 새 공지를 만든다.
	 *
	 * @param title 공지 제목(100자 이하)
	 * @param body 공지 본문
	 */
	public Notice(String title, String body) {
		this.title = title;
		this.body = body;
	}

	/**
	 * 제목과 본문을 고친다. 같은 값이어도 수정 일시는 지금으로 바뀐다.
	 *
	 * @param title 새 제목
	 * @param body 새 본문
	 */
	public void edit(String title, String body) {
		this.title = title;
		this.body = body;
		this.updatedAt = OffsetDateTime.now();
	}

}
