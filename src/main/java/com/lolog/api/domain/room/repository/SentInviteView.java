package com.lolog.api.domain.room.repository;

import java.time.OffsetDateTime;

// 방장이 보는 답을 기다리는 초대 한 줄(읽기 전용). 방 이름을 함께 읽는다.
public record SentInviteView(long id, long roomId, String roomName, long userId, OffsetDateTime createdAt) {
}
