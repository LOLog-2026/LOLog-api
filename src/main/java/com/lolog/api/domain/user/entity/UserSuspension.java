package com.lolog.api.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 정지된 회원 한 명(V12). 인증 서비스가 알려 준다. 기한(suspended_until)이 지나면 정지 아님으로 본다.
// 다시 알려 주면 기한만 바꾸는 upsert 한 문장이라 리포지토리의 native query로 만든다.
@Entity
@Table(name = "user_suspensions")
@Getter
@NoArgsConstructor
public class UserSuspension {

	@Id
	@Column(name = "user_id")
	private Long userId;

	// null이면 영구 정지
	@Column(name = "suspended_until")
	private OffsetDateTime suspendedUntil;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

}
