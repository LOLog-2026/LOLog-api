package com.lolog.api.domain.room.repository;

import java.time.OffsetDateTime;

// 올린 만화 목록 한 줄(읽기 전용). 만화 제목·그림과 좋아요 수·내가 눌렀는지·댓글 수를 함께 읽는다.
public record RoomPostView(long id, long comicId, long userId, OffsetDateTime createdAt, String title, String imageKey,
		long likes, boolean liked, long comments) {
}
