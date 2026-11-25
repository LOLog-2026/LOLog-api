package com.lolog.api.domain.room.repository;

import java.time.OffsetDateTime;

/**
 * 방 목록 한 줄(읽기 전용). 방과 마지막 대화 줄, 내 받지 않은 글 수를 함께 읽는다. 대화 줄이 없으면 last* 칸이 null이다.
 *
 * @param id 방 ID
 * @param name 방 이름
 * @param ownerId 방장 회원 ID
 * @param createdAt 방을 만든 시각
 * @param lastType 마지막 대화 줄 종류(없으면 null)
 * @param lastUserId 마지막 대화 줄을 남긴 회원 ID(없으면 null)
 * @param lastAt 마지막 대화 줄 시각(없으면 null)
 * @param lastBody 마지막 대화 줄 내용(없으면 null)
 * @param unread 내가 받지 않은 글 수
 */
public record RoomItemView(long id, String name, long ownerId, OffsetDateTime createdAt, String lastType,
		Long lastUserId, OffsetDateTime lastAt, String lastBody, long unread) {
}
