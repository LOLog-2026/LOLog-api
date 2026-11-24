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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Generated;

// 친구와 같이 쓰는 방 하나(V8·V9 rooms). 방을 지우면 멤버·초대·올린 만화·좋아요·대화를 DB가 함께 지운다(ON DELETE CASCADE).
// 이름 바꾸기와 방장 넘기기를 서로 다른 요청이 따로 하므로, 바뀐 열만 UPDATE한다(@DynamicUpdate).
@Entity
@Table(name = "rooms")
@Getter
@NoArgsConstructor
@DynamicUpdate
public class Room {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 20)
	private String name;

	// 인증 DB 회원 ID(외래 키 아님)
	@Column(name = "created_by", nullable = false, updatable = false)
	private Long createdBy;

	// 방장. 나가면 새 방장으로 바뀐다.
	@Column(name = "owner_id", nullable = false)
	private Long ownerId;

	// 마지막 소식 시각. 대화 줄을 넣을 때 문장 실행 시각(clock_timestamp)으로 바꿔서 리포지토리의 native query로만 바꾼다.
	@Column(name = "last_activity_at", insertable = false, updatable = false)
	private OffsetDateTime lastActivityAt;

	// 만든 시각. DB가 넣고, 넣은 뒤 다시 읽는다(방 응답의 createdAt).
	@Generated
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false, insertable = false)
	private OffsetDateTime updatedAt;

	// me가 방장인 새 방을 만든다.
	public Room(String name, Long me) {
		this.name = name;
		this.createdBy = me;
		this.ownerId = me;
	}

	// 방 이름을 바꾼다.
	public void rename(String name) {
		this.name = name;
		updatedAt = OffsetDateTime.now();
	}

	// 방장을 넘긴다.
	public void changeOwner(Long ownerId) {
		this.ownerId = ownerId;
		updatedAt = OffsetDateTime.now();
	}

	// me가 방장인지 본다.
	public boolean isOwnedBy(long me) {
		return ownerId == me;
	}

}
