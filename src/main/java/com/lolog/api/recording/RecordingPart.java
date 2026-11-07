package com.lolog.api.recording;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "recording_parts")
@Getter
@NoArgsConstructor
public class RecordingPart {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "recording_id", nullable = false)
	private Recording recording;

	@Column(name = "part_no", nullable = false)
	private Integer partNo;

	@Column(name = "is_last", nullable = false)
	private boolean last;

	@Column(name = "duration_sec", nullable = false)
	private Integer durationSec;

	// 문장 목록 JSON. 감지가 끝나면 지워질 수 있어 null일 수 있다.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "json")
	private String sentences;

	@Setter
	@Column(name = "carry_from_ms")
	private Long carryFromMs;

	@Setter
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private RecordingStatus status = RecordingStatus.RECEIVED;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public RecordingPart(Recording recording, int partNo, boolean last, int durationSec, String sentences) {
		this.recording = recording;
		this.partNo = partNo;
		this.last = last;
		this.durationSec = durationSec;
		this.sentences = sentences;
	}

}
