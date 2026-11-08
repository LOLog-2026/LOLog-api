package com.lolog.api.card;

import com.fasterxml.jackson.annotation.JsonSetter;
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

	public record CardSummary(Long id, String kind, String summary, OffsetDateTime recordedAt, Long startMs, Long endMs,
			String place, int lineCount, int comicCount, OffsetDateTime createdAt) {
	}

	public record CardDetail(Long id, Long recordingId, String kind, String summary, String place,
			OffsetDateTime recordedAt, Long startMs, Long endMs, List<Speaker> speakers, List<Line> lines,
			List<ComicRef> comics, int comicRemaining, OffsetDateTime createdAt) {
	}

	public record Speaker(String key, String name) {
	}

	public record Line(Long id, String speaker, String text) {
	}

	public record ComicRef(Long id, ComicStatus status) {
	}

	// place는 "빠짐"(안 바꿈)과 "null"(지움)을 구분해야 해서 record 대신 setter로 받는다.
	public static class CardUpdateRequest {

		private List<@Valid @NotNull SpeakerUpdate> speakers;

		private List<@Valid @NotNull LineUpdate> lines;

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

	// name은 꼭 보내야 하고(null이면 이름을 지움), 빠지면 400이다.
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

	}

	public record LineUpdate(@NotNull Long id, String speaker, String text) {
	}

}
