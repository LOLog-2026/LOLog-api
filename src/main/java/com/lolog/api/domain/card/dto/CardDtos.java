package com.lolog.api.domain.card.dto;

import com.lolog.api.domain.audio.service.CardAudioViews.CardAudio;
import com.lolog.api.domain.character.service.CharacterViews.SpeakerCharacter;
import com.lolog.api.domain.comic.entity.ComicStatus;
import com.lolog.api.domain.video.dto.VideoDtos.VideoRef;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.List;

// 카드 API의 요청·응답 모양. API DTO 명세 2.2와 같다.
public final class CardDtos {

	private CardDtos() {
	}

	/**
	 * 카드 목록 한 페이지.
	 *
	 * @param cards 이 페이지의 카드
	 * @param nextCursor 다음 페이지 커서(마지막 카드 ID, 더 없으면 null)
	 */
	public record CardPage(List<CardSummary> cards, String nextCursor) {
	}

	/**
	 * source는 RECORDING·WRITTEN·BLOG, textId는 글 카드만 있다. 글 카드는 startMs·endMs가 null이다(texts.md 6장).
	 *
	 * @param id 카드 ID
	 * @param kind 이야기 종류
	 * @param summary 요약
	 * @param recordedAt 이야기가 있었던 시각
	 * @param startMs 녹음 안 시작 위치(ms, 글 카드는 null)
	 * @param endMs 녹음 안 끝 위치(ms, 글 카드는 null)
	 * @param place 장소(null 가능)
	 * @param lineCount 대사 수
	 * @param comicCount 실패하지 않은 만화 수
	 * @param hasAudio 들을 수 있는 구간 음성이 있는지
	 * @param source RECORDING·WRITTEN·BLOG
	 * @param textId 글 카드의 글 ID(녹음 카드는 null)
	 * @param createdAt 카드를 만든 시각
	 */
	public record CardSummary(Long id, String kind, String summary, OffsetDateTime recordedAt, Long startMs, Long endMs,
			String place, int lineCount, int comicCount, boolean hasAudio, String source, Long textId,
			OffsetDateTime createdAt) {
	}

	/**
	 * 카드 상세 응답.
	 *
	 * @param id 카드 ID
	 * @param recordingId 녹음 ID(글 카드이거나 녹음을 지웠으면 null)
	 * @param kind 이야기 종류
	 * @param summary 요약
	 * @param place 장소(null 가능)
	 * @param recordedAt 이야기가 있었던 시각
	 * @param startMs 녹음 안 시작 위치(ms, 글 카드는 null)
	 * @param endMs 녹음 안 끝 위치(ms, 글 카드는 null)
	 * @param speakers 화자(key순)
	 * @param lines 대사(줄 번호순)
	 * @param comics 이 카드로 만든 만화(최신순)
	 * @param comicRemaining 더 만들 수 있는 만화 수
	 * @param videos 이 카드로 만든 영상
	 * @param videoRemaining 더 만들 수 있는 영상 수
	 * @param audio 카드 구간 음성
	 * @param source RECORDING·WRITTEN·BLOG
	 * @param textId 글 카드의 글 ID(녹음 카드는 null)
	 * @param truncated 글 카드의 글이 잘렸는지
	 * @param createdAt 카드를 만든 시각
	 */
	public record CardDetail(Long id, Long recordingId, String kind, String summary, String place,
			OffsetDateTime recordedAt, Long startMs, Long endMs, List<Speaker> speakers, List<Line> lines,
			List<ComicRef> comics, int comicRemaining, List<VideoRef> videos, int videoRemaining, CardAudio audio,
			String source, Long textId,
			boolean truncated, OffsetDateTime createdAt) {
	}

	/**
	 * character는 붙인 그림 { type, id, label, thumbnailUrl } 또는 null(characters.md 4장)
	 *
	 * @param key 화자 key(예: 화자1)
	 * @param name 사용자가 붙인 이름(null 가능)
	 * @param character 붙인 그림(없으면 null)
	 */
	public record Speaker(String key, String name, SpeakerCharacter character) {
	}

	/**
	 * 대사 한 줄.
	 *
	 * @param id 대사 ID
	 * @param speaker 말한 화자 key
	 * @param text 대사 내용
	 */
	public record Line(Long id, String speaker, String text) {
	}

	/**
	 * 카드에 딸린 만화 하나의 ID와 상태.
	 *
	 * @param id 만화 ID
	 * @param status 만화 상태
	 */
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

		/**
		 * 뺄 화자 key를 받는다.
		 *
		 * @param removeSpeakers 뺄 화자 key(null이면 빈 목록으로 본다)
		 */
		public void setRemoveSpeakers(List<String> removeSpeakers) {
			this.removeSpeakers = removeSpeakers;
		}

		private String place;

		private boolean placeSent;

		public List<SpeakerUpdate> getSpeakers() {
			return speakers == null ? List.of() : speakers;
		}

		/**
		 * 고치거나 더할 화자를 받는다.
		 *
		 * @param speakers 고치거나 더할 화자(null이면 빈 목록으로 본다)
		 */
		public void setSpeakers(List<SpeakerUpdate> speakers) {
			this.speakers = speakers;
		}

		public List<LineUpdate> getLines() {
			return lines == null ? List.of() : lines;
		}

		/**
		 * 고칠 대사를 받는다.
		 *
		 * @param lines 고칠 대사(null이면 빈 목록으로 본다)
		 */
		public void setLines(List<LineUpdate> lines) {
			this.lines = lines;
		}

		public String getPlace() {
			return place;
		}

		/**
		 * 장소를 받고, 보냈다고 표시한다.
		 *
		 * @param place 바꿀 장소(null이면 지움)
		 */
		@JsonSetter("place")
		public void setPlace(String place) {
			this.place = place;
			this.placeSent = true;
		}

		public boolean isPlaceSent() {
			return placeSent;
		}

	}

	/**
	 * 붙일 그림. type은 DEFAULT·PROFILE, id는 기본 화자 id 또는 회원 ID(글자)
	 *
	 * @param type DEFAULT 또는 PROFILE
	 * @param id 기본 화자 id 또는 회원 ID(글자)
	 */
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

		/**
		 * 화자 key를 받는다.
		 *
		 * @param key 고치거나 더할 화자 key(빼기 전 기준)
		 */
		public void setKey(String key) {
			this.key = key;
		}

		public String getName() {
			return name;
		}

		/**
		 * 이름을 받고, 보냈다고 표시한다.
		 *
		 * @param name 바꿀 이름(null이면 지움)
		 */
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

		/**
		 * 그림을 받고, 보냈다고 표시한다.
		 *
		 * @param character 붙일 그림(null이면 뗌)
		 */
		@JsonSetter("character")
		public void setCharacter(CharacterRef character) {
			this.character = character;
			this.characterSent = true;
		}

		public boolean isCharacterSent() {
			return characterSent;
		}

	}

	/**
	 * 고칠 대사. speaker·text 중 바꿀 것만 보낸다.
	 *
	 * @param id 고칠 대사 ID
	 * @param speaker 바꿀 화자 key(안 바꾸면 null)
	 * @param text 바꿀 대사 내용(안 바꾸면 null)
	 */
	public record LineUpdate(@NotNull Long id, String speaker, String text) {
	}

}
