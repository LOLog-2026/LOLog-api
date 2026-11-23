package com.lolog.api.domain.video.dto;

import java.time.OffsetDateTime;
import java.util.List;

// 카드 영상 API의 요청·응답 모양. LOLog-docs video.md 2·3장과 같다.
public final class VideoDtos {

	private VideoDtos() {
	}

	// POST /api/cards/{id}/videos → 202
	public record VideoAccepted(Long videoId, String status) {
	}

	// videoUrl·thumbnailUrl·durationMs는 DONE일 때만(10분 서명 주소). cardId는 카드를 지웠으면 null.
	// cardSummary는 만화 목록(ComicResponse)과 같게 요청할 때의 카드 요약이다(앱 컨텐츠 미디어 탭 제목).
	public record VideoResponse(Long id, Long cardId, String cardSummary, String status, String videoUrl,
			String thumbnailUrl, Long durationMs, OffsetDateTime createdAt) {
	}

	// 영상 목록 한 페이지. 다음 페이지가 없으면 nextCursor는 null.
	public record VideoPage(List<VideoResponse> videos, String nextCursor) {
	}

	// 카드 자세히의 videos[] 한 항목
	public record VideoRef(Long id, String status) {
	}

}
