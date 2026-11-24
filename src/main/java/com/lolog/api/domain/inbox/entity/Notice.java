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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

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

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	// 새 공지를 만든다.
	public Notice(String title, String body) {
		this.title = title;
		this.body = body;
	}

	// 제목과 본문을 고친다.
	public void edit(String title, String body) {
		this.title = title;
		this.body = body;
	}

}
