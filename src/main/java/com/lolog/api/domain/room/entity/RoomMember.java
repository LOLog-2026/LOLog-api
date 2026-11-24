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

// 방 멤버 한 줄(V8 room_members). (방, 회원)마다 한 줄이다. 방을 지우면 DB가 함께 지운다.
// 들어온 시각과 대화를 본 시각은 문장 실행 시각(clock_timestamp)으로 정해야 해서 넣기·바꾸기를 리포지토리의 native query로 만든다.
@Entity
@Table(name = "room_members")
@IdClass(RoomMember.Key.class)
@Getter
@NoArgsConstructor
public class RoomMember {

	// 기본 키(room_id, user_id)
	public record Key(Long roomId, Long userId) implements Serializable {
	}

	@Id
	@Column(name = "room_id")
	private Long roomId;

	@Id
	@Column(name = "user_id")
	private Long userId;

	// 대화를 마지막으로 본 시각. 그 뒤 다른 사람의 대화 줄 수가 받지 않은 글이다.
	@Column(name = "last_read_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime lastReadAt;

	// 방에 들어온 시각
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

}
