package com.lolog.api.card;

import com.lolog.api.video.VideoDtos.VideoRef;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.lolog.api.audio.CardAudioViews.CardAudio;
import com.lolog.api.character.CharacterViews.SpeakerCharacter;
import com.lolog.api.comic.ComicStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

// 카드 API의 요청·응답 모양. API DTO 명세 2.2와 같다.
public final class CardDtos {

	private CardDtos() {
	}

	public record CardPage(List<CardSummary> cards, String nextCursor) {
	}

	// source는 RECORDING·WRITTEN·BLOG, textId는 글 카드만 있다. 글 카드는 startMs·endMs가 null이다(texts.md 6장).
	public record CardSummary(Long id, String kind, String summary, OffsetDateTime recordedAt, Long startMs, Long endMs,
			String place, int lineCount, int comicCount, boolean hasAudio, String source, Long textId,
			OffsetDateTime createdAt) {
	}

	public record CardDetail(Long id, Long recordingId, String kind, String summary, String place,
			OffsetDateTime recordedAt, Long startMs, Long endMs, List<Speaker> speakers, List<Line> lines,
			List<ComicRef> comics, int comicRemaining, List<VideoRef> videos, int videoRemaining, CardAudio audio,
			String source, Long textId,
			boolean truncated, OffsetDateTime createdAt) {
	}

	// character는 붙인 그림 { type, id, label, thumbnailUrl } 또는 null(characters.md 4장)
	public record Speaker(String key, String name, SpeakerCharacter character) {
	}

	public record Line(Long id, String speaker, String text) {
	}

	public record ComicRef(Long id, ComicStatus status) {
	}

	// place는 "빠짐"(안 바꿈)과 "null"(지움)을 구분해야 해서 record 대신 setter로 받는다.
	public static class CardUpdateRequest {

		private List<@Valid @NotNull SpeakerUpdate> speakers;

		private List<@Valid @NotNull LineUpdate> lines;

		// 뺄 화자의 key(빼기 전 기준). 대사가 없는 화자만 뺄 수 있다.
		private List<@NotNull String> removeSpeakers;

		public List<String> getRemoveSpeakers() {
			return removeSpeakers == null ? List.of() : removeSpeakers;
		}

		public void setRemoveSpeakers(List<String> removeSpeakers) {
			this.removeSpeakers = removeSpeakers;
		}

		private String place;

		private boolean placeSent;

		public List<SpeakerUpdate> getSpeakers() {
			return speakers == null ? List.of() : speakers;
		}

		public void setSpeakers(List<SpeakerUpdate> speakers) {
			this.speakers = speakers;
		}

		public List<LineUpdate> getLines() {
			return lines == null ? List.of() : lines;
		}

		public void setLines(List<LineUpdate> lines) {
			this.lines = lines;
		}

		public String getPlace() {
			return place;
		}

		@JsonSetter("place")
		public void setPlace(String place) {
			this.place = place;
			this.placeSent = true;
		}

		public boolean isPlaceSent() {
			return placeSent;
		}

	}

	// 붙일 그림. type은 DEFAULT·PROFILE, id는 기본 화자 id 또는 회원 ID(글자)
	public record CharacterRef(String type, String id) {
	}

	// name과 character는 둘 다 선택이지만 둘 중 하나는 있어야 한다(api-dto SpeakerUpdate).
	// 빠짐(안 바꿈)과 null(지움·뗌)을 구분해야 해서 record 대신 setter로 받는다.
	public static class SpeakerUpdate {

		@NotNull
		private String key;

		private String name;

		private boolean nameSent;

		public String getKey() {
			return key;
		}

		public void setKey(String key) {
			this.key = key;
		}

		public String getName() {
			return name;
		}

		@JsonSetter("name")
		public void setName(String name) {
			this.name = name;
			this.nameSent = true;
		}

		public boolean isNameSent() {
			return nameSent;
		}

		private CharacterRef character;

		private boolean characterSent;

		public CharacterRef getCharacter() {
			return character;
		}

		@JsonSetter("character")
		public void setCharacter(CharacterRef character) {
			this.character = character;
			this.characterSent = true;
		}

		public boolean isCharacterSent() {
			return characterSent;
		}

	}

	public record LineUpdate(@NotNull Long id, String speaker, String text) {
	}

}
