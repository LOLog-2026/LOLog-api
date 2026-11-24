package com.lolog.api.domain.friend.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Generated;

// 두 사람 사이의 친구 요청이나 친구 한 줄(V5 friendships). 두 사람 사이에 줄은 하나다(uk_friendships_pair).
// 거절·취소·끊기·차단은 줄을 지운다.
@Entity
@Table(name = "friendships")
@Getter
@NoArgsConstructor
public class Friendship {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "requester_id", nullable = false, updatable = false)
	private Long requesterId;

	@Column(name = "addressee_id", nullable = false, updatable = false)
	private Long addresseeId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private FriendshipStatus status;

	// 요청한 시각. DB가 넣고, 넣은 뒤 다시 읽는다(요청 응답의 createdAt).
	@Generated
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	// ACCEPTED가 된 뒤에는 친구가 된 시각이다. 넣을 때는 DB 기본값을 쓴다.
	@Column(name = "updated_at", nullable = false, insertable = false)
	private OffsetDateTime updatedAt;

	// requester가 addressee에게 보낸 새 요청을 만든다.
	public Friendship(Long requesterId, Long addresseeId) {
		this.requesterId = requesterId;
		this.addresseeId = addresseeId;
		this.status = FriendshipStatus.PENDING;
	}

	// 요청을 수락해 친구가 된다.
	public void accept() {
		status = FriendshipStatus.ACCEPTED;
		updatedAt = OffsetDateTime.now();
	}

	// 친구(ACCEPTED)인지 본다.
	public boolean isAccepted() {
		return status == FriendshipStatus.ACCEPTED;
	}

	// me가 보낸 요청인지 본다.
	public boolean isRequestedBy(long me) {
		return requesterId == me;
	}

	// me가 아닌 쪽의 회원 ID.
	public long otherThan(long me) {
		return requesterId == me ? addresseeId : requesterId;
	}

}
