package com.lolog.api.domain.room.repository;

import java.time.OffsetDateTime;

// 내가 받은 초대 한 줄(읽기 전용). 방 이름과 그 방에 내가 차단한 멤버가 있는지를 함께 읽는다.
public record ReceivedInviteView(long id, long roomId, String roomName, long invitedBy, OffsetDateTime createdAt,
		boolean hasBlocked) {
}
