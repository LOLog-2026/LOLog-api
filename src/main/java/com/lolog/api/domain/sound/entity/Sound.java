package com.lolog.api.domain.sound.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 쇼츠 효과음·배경음 하나(sound_library). freesound_id 하나에 한 줄이다(uk_sound_library_freesound_id).
// 넣기·바꾸기는 upsert 한 문장이라 리포지토리의 native query로 만든다.
// 소리 벡터(embedding, embedding_model)는 pgvector 칸이라 엔티티에 두지 않고 리포지토리의 native query로만 다룬다.
@Entity
@Table(name = "sound_library")
@Getter
@NoArgsConstructor
public class Sound {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// SFX(효과음), BGM(배경음)
	@Column(nullable = false, length = 5)
	private String kind;

	// 모을 때 쓴 검색어(door_slam 등)
	@Column(nullable = false, length = 50)
	private String query;

	@Column(length = 200)
	private String title;

	// defaults/sounds/{id}.ogg. id가 정해진 뒤에 채운다.
	@Column(name = "object_key", nullable = false, length = 255)
	private String objectKey;

	@Column(name = "duration_ms", nullable = false)
	private Integer durationMs;

	@Column(name = "freesound_id", nullable = false)
	private Long freesoundId;

	@Column(name = "source_url", nullable = false, length = 500)
	private String sourceUrl;

	@Column(nullable = false, length = 10)
	private String license;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	// 저장소 키를 채운다.
	public void assignObjectKey(String objectKey) {
		this.objectKey = objectKey;
	}

}
