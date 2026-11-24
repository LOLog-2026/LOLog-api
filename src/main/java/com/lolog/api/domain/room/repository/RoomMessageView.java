package com.lolog.api.domain.room.repository;

import java.time.OffsetDateTime;

// 방 대화 한 줄(읽기 전용). 만화에 단 줄이면 그 만화의 제목·그림을 함께 읽는다.
// 만화를 내렸거나 만화가 없으면 comicId가 null이다.
public record RoomMessageView(long id, long userId, String type, Long postId, String body, OffsetDateTime createdAt,
		Long comicId, String postTitle, String postImageKey) {
}
