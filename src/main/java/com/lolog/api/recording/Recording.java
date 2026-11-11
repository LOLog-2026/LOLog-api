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

	// 녹음 끝 시각: 시작 시각 + 받은 묶음 길이의 합. 묶음을 받을 때마다 고쳐 적고, 음성 7일 보관을 이 시각부터 센다(audio.md 5장).
	@Setter
	@Column(name = "ended_at")
	private OffsetDateTime endedAt;

	// 이 녹음에서 음성 저장을 켰는지. 첫 글 묶음의 audioSaved로 정하고 바꾸지 않는다.
	@Column(name = "audio_saved", nullable = false)
	private boolean audioSaved;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public Recording(Long userId, String clientRecordingId, OffsetDateTime startedAt, boolean audioSaved) {
		this.userId = userId;
		this.clientRecordingId = clientRecordingId;
		this.startedAt = startedAt;
		this.endedAt = startedAt;
		this.audioSaved = audioSaved;
	}

}
