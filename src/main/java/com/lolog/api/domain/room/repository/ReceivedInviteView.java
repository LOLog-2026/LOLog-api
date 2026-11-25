package com.lolog.api.domain.room.repository;

import java.time.OffsetDateTime;

/**
 * 내가 받은 초대 한 줄(읽기 전용). 방 이름과 그 방에 내가 차단한 멤버가 있는지를 함께 읽는다.
 *
 * @param id 초대 ID
 * @param roomId 방 ID
 * @param roomName 방 이름
 * @param invitedBy 초대한 방장 회원 ID
 * @param createdAt 초대한 시각
 * @param hasBlocked 내가 차단한 멤버가 그 방에 있는지
 */
public record ReceivedInviteView(long id, long roomId, String roomName, long invitedBy, OffsetDateTime createdAt,
		boolean hasBlocked) {
}
