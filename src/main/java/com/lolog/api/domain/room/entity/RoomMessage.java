package com.lolog.api.domain.room.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 방 대화 한 줄(V8 room_messages): 방을 만듦(CREATED), 만화를 올림(POSTED), 좋아요(LIKED), 댓글(COMMENT).
// 방이나 올린 만화를 지우면 DB가 함께 지운다. 시각을 문장 실행 시각(clock_timestamp)으로 정해야 해서 넣기를 리포지토리의 native query로 만든다.
@Entity
@Table(name = "room_messages")
@Getter
@NoArgsConstructor
public class RoomMessage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "room_id", nullable = false)
	private Long roomId;

	// 남긴 회원
	@Column(name = "user_id", nullable = false)
	private Long userId;

	// CREATED, POSTED, LIKED, COMMENT
	@Column(name = "message_type", nullable = false, length = 10)
	private String messageType;

	// 올린 만화. 방 전체 댓글이면 null
	@Column(name = "post_id")
	private Long postId;

	// COMMENT일 때만
	@Column(length = 200)
	private String body;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

}
