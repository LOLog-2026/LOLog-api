package com.lolog.api.global.storage.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 지울 저장소 파일 하나(storage_deletions). 지우면 줄을 빼고, 실패하면 횟수와 이유를 남긴다.
@Entity
@Table(name = "storage_deletions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class StorageDeletion {

	static final int MAX_ERROR = 200;

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 키 하나, 또는 prefix가 true면 그 아래 전체
	@Column(name = "object_key", nullable = false, length = 255)
	private String objectKey;

	@Column(nullable = false)
	private boolean prefix;

	@Column(nullable = false)
	private int attempts;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "last_error", length = MAX_ERROR)
	private String lastError;

	// 지울 키(또는 접두어)를 만든다.
	public StorageDeletion(String objectKey, boolean prefix) {
		this.objectKey = objectKey;
		this.prefix = prefix;
	}

	// 지우기에 실패했다. 이유는 200자로 자른다.
	public void failed(String error) {
		this.attempts++;
		this.lastError = error == null || error.length() <= MAX_ERROR ? error : error.substring(0, MAX_ERROR);
	}

}
