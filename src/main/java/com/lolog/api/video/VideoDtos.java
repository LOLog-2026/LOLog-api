package com.lolog.api.video;

import com.lolog.api.comic.ComicWorkerDtos.Line;
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

	public record VideoPage(List<VideoResponse> videos, String nextCursor) {
	}

	// 카드 자세히의 videos[] 한 항목
	public record VideoRef(Long id, String status) {
	}

	// GET /internal/videos/{id}/source. ComicSource와 같은 모양이고 comicId 자리에 videoId다.
	public record VideoSource(Long videoId, Long cardId, String kind, String summary, String place,
			List<Speaker> speakers, List<Line> lines) {
	}

	public record Speaker(String key, String name, SourceCharacter character) {
	}

	// ComicSource의 character에 voice를 더한 것. 프로필 캐릭터면 사용자가 고른 목소리(없으면 null),
	// 기본 화자면 그 화자 id(목소리 파일이 있을 때)다.
	// voiceText는 voice가 "self"일 때 읽은 문장(워커의 ref_text), 아니면 null(정함 11-14)
	public record SourceCharacter(String ref, String appearance, String imageUrl, String voice, String voiceText) {
	}

}
