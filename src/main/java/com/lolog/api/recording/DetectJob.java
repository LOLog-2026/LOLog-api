package com.lolog.api.recording;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

// jobs 테이블의 DETECT 작업. attempts는 큐에 넣은 횟수이고, 0이면 아직 넣지 못한 작업이다.
@Entity
@Table(name = "jobs")
@Getter
@NoArgsConstructor
public class DetectJob {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "recording_part_id")
	private Long recordingPartId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "job_type", nullable = false, length = 20)
	private String jobType = "DETECT";

	@Column(nullable = false, length = 20)
	private String status = "QUEUED";

	@Column(nullable = false)
	private int attempts;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	public DetectJob(Long recordingPartId, Long userId) {
		this.recordingPartId = recordingPartId;
		this.userId = userId;
	}

	public void markPublished() {
		attempts++;
	}

}
