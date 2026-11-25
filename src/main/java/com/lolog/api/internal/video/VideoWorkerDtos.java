package com.lolog.api.internal.video;

import com.lolog.api.internal.comic.ComicWorkerDtos.Line;
import java.util.List;

// 쇼츠 워커가 /internal에서 받아 가는 본문.
public final class VideoWorkerDtos {

	private VideoWorkerDtos() {
	}

	/**
	 * GET /internal/videos/{id}/source. ComicSource와 같은 모양이고 comicId 자리에 videoId다.
	 *
	 * @param videoId 영상 ID
	 * @param cardId 영상을 만들 카드 ID
	 * @param kind 카드 종류
	 * @param summary 카드 요약
	 * @param place 카드의 장소(null 가능)
	 * @param speakers 카드의 화자
	 * @param lines 카드의 대사
	 */
	public record VideoSource(Long videoId, Long cardId, String kind, String summary, String place,
			List<Speaker> speakers, List<Line> lines) {
	}

	/**
	 * 카드의 화자. character는 붙인 그림과 목소리다.
	 *
	 * @param key 화자 키(대사의 speaker와 같은 값)
	 * @param name 화자 이름
	 * @param character 붙인 그림과 목소리, 없으면 null
	 */
	public record Speaker(String key, String name, SourceCharacter character) {
	}

	/**
	 * ComicSource의 character에 voice를 더한 것. 프로필 캐릭터면 사용자가 고른 목소리(없으면 null),
	 * 기본 화자면 그 화자 id(목소리 파일이 있을 때)다.
	 * voiceText는 voice가 "self"일 때 읽은 문장(워커의 ref_text), 아니면 null(정함 11-14)
	 *
	 * @param ref 그림을 가리키는 값(예: default:young-f-02, profile:42)
	 * @param appearance 외모 글
	 * @param imageUrl 그림의 10분 서명 주소, 없으면 null
	 * @param voice 읽을 목소리, 없으면 null
	 * @param voiceText voice가 "self"일 때 읽은 문장, 아니면 null
	 */
	public record SourceCharacter(String ref, String appearance, String imageUrl, String voice, String voiceText) {
	}

}
