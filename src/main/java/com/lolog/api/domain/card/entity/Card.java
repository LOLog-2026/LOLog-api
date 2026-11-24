package com.lolog.api.domain.card.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.UpdateTimestamp;

// 감지한 이야기 하나. 화자와 대사는 함께 저장하고 함께 지운다.
@Entity
@Table(name = "cards")
@Getter
@NoArgsConstructor
public class Card {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 녹음 기록을 지우면 null이 된다.
	@Column(name = "recording_id")
	private Long recordingId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(nullable = false, length = 20)
	private String kind;

	@Column(nullable = false, columnDefinition = "text")
	private String summary;

	@Setter
	@Column(length = 200)
	private String place;

	// 글 카드(text_id)는 시간 값이 없어 null이다(texts.md 7장).
	@Column(name = "start_ms")
	private Long startMs;

	@Column(name = "end_ms")
	private Long endMs;

	// 글 카드의 글. 녹음 카드는 null이고, 글이 지워지면 null이 된다(texts.md 7장).
	@Column(name = "text_id")
	private Long textId;

	// 녹음 카드는 녹음 시작 시각 + startMs, 글 카드는 직접 쓴 글이면 올린 시각, 블로그 글이면 쓴 시각(texts.md 2장)
	@Column(name = "recorded_at", nullable = false)
	private OffsetDateTime recordedAt;

	// 만든 만화 횟수(V2). 만화 요청 때 +1, 실패하면 −1, 만화를 지워도 줄지 않는다. comicRemaining = 3 − comicCount
	@Column(name = "comic_count", nullable = false)
	private int comicCount;

	// 만든 영상 횟수(V14). 영상 요청 때 +1, 실패하면 −1, 영상을 지워도 줄지 않는다. videoRemaining = 2 − videoCount
	// CardRepository의 조건 UPDATE로만 바꾼다. 카드를 고칠 때 엔티티가 옛 값으로 덮어쓰지 않게 읽기만 한다.
	@Column(name = "video_count", nullable = false, insertable = false, updatable = false)
	private int videoCount;

	// 카드 구간 음성(V6). 음성이 없으면 둘 다 null. CardRepository의 조건 UPDATE로만 바꾼다(videoCount와 같은 까닭).
	@Column(name = "audio_key", insertable = false, updatable = false)
	private String audioKey;

	@Column(name = "audio_duration_ms", insertable = false, updatable = false)
	private Long audioDurationMs;

	@OneToMany(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("speakerKey")
	private List<CardSpeaker> speakers = new ArrayList<>();

	@OneToMany(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNo")
	private List<CardLine> lines = new ArrayList<>();

	// DB 시계(clock_timestamp, V20)로 넣고, 넣은 뒤 다시 읽는다. 보관함 새 소식 본 시각(badge_reads.seen_at)과 같은 시계로 비교하려는 것이다.
	@Generated
	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	// 녹음으로 만든 카드.
	public Card(Long recordingId, Long userId, String kind, String summary, long startMs, long endMs,
			OffsetDateTime recordedAt) {
		this.recordingId = recordingId;
		this.userId = userId;
		this.kind = kind;
		this.summary = summary;
		this.startMs = startMs;
		this.endMs = endMs;
		this.recordedAt = recordedAt;
	}

	// 글 하나로 만든 카드(texts.md). 시간 값은 없고, 장소는 글에 적은 장소다.
	public static Card fromText(Long textId, Long userId, String kind, String summary, String place,
			OffsetDateTime recordedAt) {
		Card card = new Card();
		card.textId = textId;
		card.userId = userId;
		card.kind = kind;
		card.summary = summary;
		card.place = place;
		card.recordedAt = recordedAt;
		return card;
	}

	// 화자 이름은 비어 있는 채로 시작한다. 앱은 이름 대신 key("화자1")를 보여 준다.
	public void addSpeaker(String key) {
		speakers.add(new CardSpeaker(this, key));
	}

	// 대사를 한 줄 더한다.
	public void addLine(int lineNo, String speakerKey, String text, Long startMs, Long endMs) {
		lines.add(new CardLine(this, lineNo, speakerKey, text, startMs, endMs));
	}

}
