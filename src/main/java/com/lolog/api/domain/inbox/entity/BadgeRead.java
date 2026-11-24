package com.lolog.api.domain.inbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 새 소식 점을 마지막으로 본 시각(inbox.md 3장). (사용자, 종류)마다 한 줄이다.
// 넣기·바꾸기는 seen_at을 문장 실행 시각(clock_timestamp)으로 정하는 upsert라 리포지토리의 native query로 만든다.
@Entity
@Table(name = "badge_reads")
@IdClass(BadgeRead.Key.class)
@Getter
@NoArgsConstructor
public class BadgeRead {

	// 기본 키(user_id, kind)
	public record Key(Long userId, String kind) implements Serializable {
	}

	@Id
	@Column(name = "user_id")
	private Long userId;

	// cards, comics, profileCharacter, rooms
	@Id
	@Column(length = 20)
	private String kind;

	@Column(name = "seen_at", nullable = false)
	private OffsetDateTime seenAt;

}
