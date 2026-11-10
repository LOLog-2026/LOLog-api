package com.lolog.api.comic;

import com.lolog.api.character.CharacterViews.SourceCharacter;
import java.util.List;

// 워커가 만화를 만들 때 받는 카드 내용. API DTO 명세 5장 ComicSource와 같다.
public final class ComicWorkerDtos {

	private ComicWorkerDtos() {
	}

	public record ComicSource(Long comicId, Long cardId, String kind, String summary, String place,
			List<Speaker> speakers, List<Line> lines) {
	}

	// character는 붙인 그림 { ref, appearance, imageUrl } 또는 null(characters.md /internal)
	public record Speaker(String key, String name, SourceCharacter character) {
	}

	// id는 콘티의 sourceLineIds에 쓴다.
	public record Line(Long id, String speaker, String text) {
	}

}
