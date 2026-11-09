package com.lolog.api.card;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "card_lines")
@Getter
@NoArgsConstructor
public class CardLine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "card_id", nullable = false)
	private Card card;

	@Column(name = "line_no", nullable = false)
	private Integer lineNo;

	@Setter
	@Column(name = "speaker_key", nullable = false, length = 20)
	private String speakerKey;

	@Setter
	@Column(nullable = false, columnDefinition = "text")
	private String text;

	// 모르면 null이다.
	@Column(name = "start_ms")
	private Long startMs;

	@Column(name = "end_ms")
	private Long endMs;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	CardLine(Card card, int lineNo, String speakerKey, String text, Long startMs, Long endMs) {
		this.card = card;
		this.lineNo = lineNo;
		this.speakerKey = speakerKey;
		this.text = text;
		this.startMs = startMs;
		this.endMs = endMs;
	}

}
