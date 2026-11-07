package com.lolog.api.recording;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

@Entity
@Table(name = "recordings")
@Getter
@NoArgsConstructor
public class Recording {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "client_recording_id", nullable = false, length = 64)
	private String clientRecordingId;

	@Column(name = "started_at", nullable = false)
	private OffsetDateTime startedAt;

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

	public Recording(Long userId, String clientRecordingId, OffsetDateTime startedAt) {
		this.userId = userId;
		this.clientRecordingId = clientRecordingId;
		this.startedAt = startedAt;
	}

}
