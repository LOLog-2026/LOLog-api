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

// jobs 테이블의 작업. 지금은 DETECT만 만들고, COMIC은 #5에서 만든다.
// attempts는 큐에 넣은 횟수이고, 0이면 아직 넣지 못한 작업이다. 결과나 fail을 받으면 DONE·FAILED가 되고 그 뒤로는 바뀌지 않는다.
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

	@Column(name = "fail_reason", length = 500)
	private String failReason;

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

	public boolean isDetect() {
		return "DETECT".equals(jobType);
	}

	// 결과나 fail을 이미 받았는지. 받았으면 같은 jobId가 다시 와도 무시한다.
	public boolean isFinished() {
		return "DONE".equals(status) || "FAILED".equals(status);
	}

	public void complete() {
		status = "DONE";
	}

	public void fail(String reason) {
		status = "FAILED";
		failReason = reason;
	}

}
