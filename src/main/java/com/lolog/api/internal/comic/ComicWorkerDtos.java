package com.lolog.api.internal.comic;

import com.lolog.api.domain.character.service.CharacterViews.SourceCharacter;
import java.util.List;

// 워커가 만화를 만들 때 받는 카드 내용. API DTO 명세 5장 ComicSource와 같다.
public final class ComicWorkerDtos {

	private ComicWorkerDtos() {
	}

	/**
	 * GET /internal/comics/{id}/source 응답이다.
	 *
	 * @param comicId 만화 ID
	 * @param cardId 만화를 만들 카드 ID
	 * @param kind 카드 종류
	 * @param summary 카드 요약
	 * @param place 카드의 장소(null 가능)
	 * @param speakers 카드의 화자
	 * @param lines 카드의 대사
	 */
	public record ComicSource(Long comicId, Long cardId, String kind, String summary, String place,
			List<Speaker> speakers, List<Line> lines) {
	}

	/**
	 * character는 붙인 그림 { ref, appearance, imageUrl } 또는 null(characters.md /internal)
	 *
	 * @param key 화자 키(대사의 speaker와 같은 값)
	 * @param name 화자 이름
	 * @param character 붙인 그림, 없으면 null
	 */
	public record Speaker(String key, String name, SourceCharacter character) {
	}

	/**
	 * id는 콘티의 sourceLineIds에 쓴다.
	 *
	 * @param id 대사 ID
	 * @param speaker 말한 화자 키
	 * @param text 대사 글
	 */
	public record Line(Long id, String speaker, String text) {
	}

}
