package com.lolog.api.job;

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

// jobs 테이블의 작업. DETECT는 묶음 하나(recording_part_id), COMIC은 만화 하나(comic_id),
// AVATAR는 회원(user_id)의 프로필 캐릭터, TEXT는 글 하나(text_id)를 처리한다(characters.md, texts.md).
// attempts는 큐에 넣은 횟수이고, 0이면 아직 넣지 못한 작업이다. 결과나 fail을 받으면 DONE·FAILED가 되고 그 뒤로는 바뀌지 않는다.
@Entity
@Table(name = "jobs")
@Getter
@NoArgsConstructor
public class Job {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "recording_part_id")
	private Long recordingPartId;

	@Column(name = "comic_id")
	private Long comicId;

	// VIDEO_VOICE(3호 목소리)·VIDEO_DRAW(2호 그림·합치기) 작업의 영상(video.md)
	@Column(name = "video_id")
	private Long videoId;

	// TEXT 작업(글 하나를 카드로, texts.md)의 글
	@Column(name = "text_id")
	private Long textId;

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

	public Job(Long recordingPartId, Long userId) {
		this.recordingPartId = recordingPartId;
		this.userId = userId;
	}

	public static Job comic(Long comicId, Long userId) {
		Job job = new Job();
		job.jobType = "COMIC";
		job.comicId = comicId;
		job.userId = userId;
		return job;
	}

	// type은 VIDEO_VOICE 또는 VIDEO_DRAW
	public static Job video(String type, Long videoId, Long userId) {
		Job job = new Job();
		job.jobType = type;
		job.videoId = videoId;
		job.userId = userId;
		return job;
	}

	public static Job text(Long textId, Long userId) {
		Job job = new Job();
		job.jobType = "TEXT";
		job.textId = textId;
		job.userId = userId;
		return job;
	}

	public static Job avatar(Long userId) {
		Job job = new Job();
		job.jobType = "AVATAR";
		job.userId = userId;
		return job;
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
