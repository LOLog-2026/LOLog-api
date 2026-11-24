package com.lolog.api.domain.friend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 차단 한 줄(V5·V10 user_blocks). (차단한 사람, 차단된 사람)마다 한 줄이다. 차단을 풀면 줄을 지운다.
@Entity
@Table(name = "user_blocks")
@IdClass(UserBlock.Key.class)
@Getter
@NoArgsConstructor
public class UserBlock {

	// 기본 키(blocker_id, blocked_id)
	public record Key(Long blockerId, Long blockedId) implements Serializable {
	}

	@Id
	@Column(name = "blocker_id")
	private Long blockerId;

	@Id
	@Column(name = "blocked_id")
	private Long blockedId;

	// 방에서 그 사람 글을 "숨긴 글"로 보여 줄지
	@Column(name = "hide_in_rooms", nullable = false)
	private boolean hideInRooms;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	// blocker가 blocked를 차단한다.
	public UserBlock(Long blockerId, Long blockedId, boolean hideInRooms) {
		this.blockerId = blockerId;
		this.blockedId = blockedId;
		this.hideInRooms = hideInRooms;
	}

	// 방에서 그 사람 글 숨기기 설정을 바꾼다.
	public void changeHideInRooms(boolean hideInRooms) {
		this.hideInRooms = hideInRooms;
	}

}
