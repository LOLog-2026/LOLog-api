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

// 답을 기다리는 방 초대 한 줄(V9 room_invites). 방 하나에 한 사람당 하나다(uk_room_invites_room_id_user_id).
// 수락·거절·취소하면 줄을 지운다. 초대한 시각을 문장 실행 시각(clock_timestamp)으로 정해야 해서 넣기를 리포지토리의 native query로 만든다.
@Entity
@Table(name = "room_invites")
@Getter
@NoArgsConstructor
public class RoomInvite {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "room_id", nullable = false)
	private Long roomId;

	// 초대받은 회원
	@Column(name = "user_id", nullable = false)
	private Long userId;

	// 보낸 방장
	@Column(name = "invited_by", nullable = false)
	private Long invitedBy;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

}
