package com.lolog.api.domain.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 올린 만화의 좋아요 한 줄(V8 room_post_likes). 한 사람이 올린 만화 하나에 한 번이다. 만화를 내리면 DB가 함께 지운다.
// 넣기는 이미 눌렀으면 그대로 두는 on conflict 문장이라 리포지토리의 native query로 만든다.
@Entity
@Table(name = "room_post_likes")
@IdClass(RoomPostLike.Key.class)
@Getter
@NoArgsConstructor
public class RoomPostLike {

	/**
	 * 기본 키(post_id, user_id)
	 *
	 * @param postId 올린 만화 ID
	 * @param userId 좋아요를 누른 회원 ID
	 */
	public record Key(Long postId, Long userId) implements Serializable {
	}

	@Id
	@Column(name = "post_id")
	private Long postId;

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

}
