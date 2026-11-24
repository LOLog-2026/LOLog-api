package com.lolog.api.domain.character.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 서비스가 넣어 둔 기본 화자 하나(default_characters, characters.md 2장). id는 "young-f-02" 같은 정해 둔 값이다.
// 넣기·바꾸기는 upsert 한 문장이라 리포지토리의 native query로 만든다.
@Entity
@Table(name = "default_characters")
@Getter
@NoArgsConstructor
public class DefaultCharacter {

	@Id
	@Column(length = 20)
	private String id;

	// CHILD, YOUNG, MIDDLE
	@Column(name = "age_group", nullable = false, length = 10)
	private String ageGroup;

	// F, M
	@Column(nullable = false, length = 1)
	private String gender;

	@Column(nullable = false)
	private boolean glasses;

	@Column(nullable = false, length = 50)
	private String label;

	// 그림 모델용 생김새 설명. 앱에는 보내지 않는다.
	@Column(nullable = false, length = 200)
	private String appearance;

	@Column(name = "image_key", nullable = false, length = 255)
	private String imageKey;

	@Column(name = "thumb_key", nullable = false, length = 255)
	private String thumbKey;

	// 목소리 파일 키. 없으면 null.
	@Column(name = "voice_key", length = 255)
	private String voiceKey;

	@Column(name = "sort_order", nullable = false)
	private Integer sortOrder;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime updatedAt;

}
