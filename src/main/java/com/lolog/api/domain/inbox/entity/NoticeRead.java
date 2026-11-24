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

// 사용자가 공지를 읽은 기록 하나(inbox.md 2장). (공지, 사용자)마다 한 줄이다. 공지를 지우면 DB가 함께 지운다.
// 넣기는 이미 있으면 그대로 두는 on conflict 문장이라 리포지토리의 native query로 만든다.
@Entity
@Table(name = "notice_reads")
@IdClass(NoticeRead.Key.class)
@Getter
@NoArgsConstructor
public class NoticeRead {

	// 기본 키(notice_id, user_id)
	public record Key(Long noticeId, Long userId) implements Serializable {
	}

	@Id
	@Column(name = "notice_id")
	private Long noticeId;

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Column(name = "read_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime readAt;

}
