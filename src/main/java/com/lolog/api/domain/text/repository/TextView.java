package com.lolog.api.domain.text.repository;

import com.lolog.api.domain.text.entity.TextSource;
import java.time.OffsetDateTime;

/**
 * 글 하나(읽기 전용). 첫 카드와 상태, 블로그 글이면 원문 링크와 블로그 이름을 함께 읽는다.
 *
 * @param id 글 ID
 * @param source 글 출처
 * @param title 글 제목
 * @param body 글 본문(null 가능)
 * @param place 장소(null 가능)
 * @param situation 상황(null 가능)
 * @param truncated 블로그 본문을 앞부분만 넣었는지
 * @param createdAt 글을 저장한 시각
 * @param cardId 이 글로 만든 첫 카드 ID. 없으면 null
 * @param status 글 상태 이름(DONE, FAILED, QUEUED)
 * @param link 블로그 원문 링크. 블로그 글이 아니면 null
 * @param blogTitle 블로그 이름. 블로그 글이 아니면 null
 */
public record TextView(long id, TextSource source, String title, String body, String place, String situation,
		boolean truncated, OffsetDateTime createdAt, Long cardId, String status, String link, String blogTitle) {
}
