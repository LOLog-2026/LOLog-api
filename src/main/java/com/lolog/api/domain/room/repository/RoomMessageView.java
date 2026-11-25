package com.lolog.api.domain.room.repository;

import java.time.OffsetDateTime;

/**
 * 방 대화 한 줄(읽기 전용). 만화에 단 줄이면 그 만화의 제목·그림을 함께 읽는다.
 * 만화를 내렸거나 만화가 없으면 comicId가 null이다.
 *
 * @param id 대화 줄 ID
 * @param userId 남긴 회원 ID
 * @param type CREATED·POSTED·LIKED·COMMENT
 * @param postId 관련 올린 만화 ID(없으면 null)
 * @param body 댓글 내용(COMMENT일 때만)
 * @param createdAt 남긴 시각
 * @param comicId 만화 ID(없거나 내렸으면 null)
 * @param postTitle 만화 제목(null 가능)
 * @param postImageKey 만화 그림 저장소 key(null 가능)
 */
public record RoomMessageView(long id, long userId, String type, Long postId, String body, OffsetDateTime createdAt,
		Long comicId, String postTitle, String postImageKey) {
}
