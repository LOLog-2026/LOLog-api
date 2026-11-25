package com.lolog.api.domain.video.dto;

import java.time.OffsetDateTime;
import java.util.List;

// 카드 영상 API의 요청·응답 모양. LOLog-docs video.md 2·3장과 같다.
public final class VideoDtos {

	private VideoDtos() {
	}

	/**
	 * POST /api/cards/{id}/videos → 202
	 *
	 * @param videoId 만든 영상 ID
	 * @param status 영상 상태 이름(요청 때는 QUEUED)
	 */
	public record VideoAccepted(Long videoId, String status) {
	}

	/**
	 * videoUrl·thumbnailUrl·durationMs는 DONE일 때만(10분 서명 주소). cardId는 카드를 지웠으면 null.
	 * cardSummary는 만화 목록(ComicResponse)과 같게 요청할 때의 카드 요약이다(앱 컨텐츠 미디어 탭 제목).
	 *
	 * @param id 영상 ID
	 * @param cardId 영상을 만든 카드 ID. 카드를 지웠으면 null
	 * @param cardSummary 요청할 때의 카드 요약. 없으면 빈 글
	 * @param status 영상 상태 이름(QUEUED, VOICE, DRAWING, DONE, FAILED)
	 * @param videoUrl 완성 영상(mp4)의 서명 주소. DONE이 아니면 null
	 * @param thumbnailUrl 목록용 그림(jpeg)의 서명 주소. DONE이 아니면 null
	 * @param durationMs 영상 길이(ms). DONE이 아니면 null
	 * @param createdAt 영상을 요청한 시각
	 */
	public record VideoResponse(Long id, Long cardId, String cardSummary, String status, String videoUrl,
			String thumbnailUrl, Long durationMs, OffsetDateTime createdAt) {
	}

	/**
	 * 영상 목록 한 페이지. 다음 페이지가 없으면 nextCursor는 null.
	 *
	 * @param videos 이 페이지의 영상 목록
	 * @param nextCursor 다음 페이지를 받을 cursor. 더 없으면 null
	 */
	public record VideoPage(List<VideoResponse> videos, String nextCursor) {
	}

	/**
	 * 카드 자세히의 videos[] 한 항목
	 *
	 * @param id 영상 ID
	 * @param status 영상 상태 이름
	 */
	public record VideoRef(Long id, String status) {
	}

}
