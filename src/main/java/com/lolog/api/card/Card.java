package com.lolog.api.card;

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
import org.hibernate.annotations.CreationTimestamp;
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

	@Column(length = 200)
	private String place;

	@Column(name = "start_ms", nullable = false)
	private Long startMs;

	@Column(name = "end_ms", nullable = false)
	private Long endMs;

	// 이야기를 녹음한 시각 = 녹음 시작 시각 + startMs
	@Column(name = "recorded_at", nullable = false)
	private OffsetDateTime recordedAt;

	@OneToMany(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("speakerKey")
	private List<CardSpeaker> speakers = new ArrayList<>();

	@OneToMany(mappedBy = "card", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNo")
	private List<CardLine> lines = new ArrayList<>();

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

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

	// 화자 이름은 비어 있는 채로 시작한다. 앱은 이름 대신 key("화자1")를 보여 준다.
	public void addSpeaker(String key) {
		speakers.add(new CardSpeaker(this, key));
	}

	public void addLine(int lineNo, String speakerKey, String text, Long startMs, Long endMs) {
		lines.add(new CardLine(this, lineNo, speakerKey, text, startMs, endMs));
	}

}
