package com.lolog.api.domain.room.repository;

import java.time.OffsetDateTime;

// 방 목록 한 줄(읽기 전용). 방과 마지막 대화 줄, 내 받지 않은 글 수를 함께 읽는다. 대화 줄이 없으면 last* 칸이 null이다.
public record RoomItemView(long id, String name, long ownerId, OffsetDateTime createdAt, String lastType,
		Long lastUserId, OffsetDateTime lastAt, String lastBody, long unread) {
}
