package com.lolog.api.domain.room.repository;

import java.time.OffsetDateTime;

/**
 * 올린 만화 목록 한 줄(읽기 전용). 만화 제목·그림과 좋아요 수·내가 눌렀는지·댓글 수를 함께 읽는다.
 *
 * @param id 올린 만화 ID
 * @param comicId 만화 ID
 * @param userId 올린 회원 ID
 * @param createdAt 올린 시각
 * @param title 만화 제목(null 가능)
 * @param imageKey 만화 그림 저장소 key(null 가능)
 * @param likes 좋아요 수
 * @param liked 내가 좋아요를 눌렀는지
 * @param comments 댓글 수
 */
public record RoomPostView(long id, long comicId, long userId, OffsetDateTime createdAt, String title, String imageKey,
		long likes, boolean liked, long comments) {
}
