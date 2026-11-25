package com.lolog.api.domain.comic.dto;

import com.lolog.api.domain.comic.entity.ComicStatus;
import java.time.OffsetDateTime;
import java.util.List;

// 앱에 주는 만화 모양. API DTO 명세 2.3 ComicResponse와 같다.
public final class ComicDtos {

	private ComicDtos() {
	}

	/**
	 * 만화 목록 한 페이지와 다음 커서.
	 *
	 * @param comics 이 페이지의 만화
	 * @param nextCursor 다음 페이지를 받을 커서(마지막으로 받은 만화 ID, 마지막 페이지면 null)
	 */
	public record ComicPage(List<ComicResponse> comics, String nextCursor) {
	}

	/**
	 * cardId는 카드를 지웠으면 null. imageUrl은 DONE일 때만 있고 10분 동안 열리는 서명 주소다.
	 *
	 * @param id 만화 ID
	 * @param cardId 만화를 만든 카드 ID(카드를 지웠으면 null)
	 * @param cardSummary 요청할 때 복사해 둔 카드 요약(없으면 빈 글자)
	 * @param status 만화 만들기 진행 상태
	 * @param imageUrl 만화 이미지 서명 주소(DONE일 때만, 10분 동안 열림)
	 * @param createdAt 만화를 요청한 시각
	 */
	public record ComicResponse(Long id, Long cardId, String cardSummary, ComicStatus status, String imageUrl,
			OffsetDateTime createdAt) {
	}

}
