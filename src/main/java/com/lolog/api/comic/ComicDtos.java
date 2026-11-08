package com.lolog.api.comic;

import java.time.OffsetDateTime;
import java.util.List;

// 앱에 주는 만화 모양. API DTO 명세 2.3 ComicResponse와 같다.
public final class ComicDtos {

	private ComicDtos() {
	}

	public record ComicPage(List<ComicResponse> comics, String nextCursor) {
	}

	// cardId는 카드를 지웠으면 null. imageUrl은 DONE일 때만 있고 10분 동안 열리는 서명 주소다.
	public record ComicResponse(Long id, Long cardId, String cardSummary, ComicStatus status, String imageUrl,
			OffsetDateTime createdAt) {
	}

}
