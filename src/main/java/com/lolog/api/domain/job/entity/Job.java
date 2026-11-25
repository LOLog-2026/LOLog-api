package com.lolog.api.domain.job.entity;

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
// AVATAR는 회원(user_id)의 프로필 캐릭터, VOICE_CLONE은 회원의 내 목소리, TEXT는 글 하나(text_id)를 처리한다(characters.md, texts.md).
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

	/**
	 * 녹음 묶음 하나의 감지 작업을 만든다.
	 *
	 * @param recordingPartId 감지할 녹음 묶음 ID
	 * @param userId 녹음 주인 회원 ID
	 */
	public Job(Long recordingPartId, Long userId) {
		this.recordingPartId = recordingPartId;
		this.userId = userId;
	}

	/**
	 * 만화 작업을 만든다.
	 *
	 * @param comicId 그릴 만화 ID
	 * @param userId 만화를 요청한 회원 ID
	 * @return 저장 전 COMIC 작업
	 */
	public static Job comic(Long comicId, Long userId) {
		Job job = new Job();
		job.jobType = "COMIC";
		job.comicId = comicId;
		job.userId = userId;
		return job;
	}

	/**
	 * type은 VIDEO_VOICE 또는 VIDEO_DRAW
	 *
	 * @param type 작업 종류(VIDEO_VOICE 또는 VIDEO_DRAW)
	 * @param videoId 처리할 영상 ID
	 * @param userId 영상 주인 회원 ID
	 * @return 저장 전 영상 작업
	 */
	public static Job video(String type, Long videoId, Long userId) {
		Job job = new Job();
		job.jobType = type;
		job.videoId = videoId;
		job.userId = userId;
		return job;
	}

	/**
	 * 글을 카드로 만드는 작업을 만든다.
	 *
	 * @param textId 카드로 만들 글 ID
	 * @param userId 글 주인 회원 ID
	 * @return 저장 전 TEXT 작업
	 */
	public static Job text(Long textId, Long userId) {
		Job job = new Job();
		job.jobType = "TEXT";
		job.textId = textId;
		job.userId = userId;
		return job;
	}

	/**
	 * 내 목소리 만들기(characters.md "내 목소리"). 회원만 가리킨다.
	 *
	 * @param userId 목소리를 만들 회원 ID
	 * @return 저장 전 VOICE_CLONE 작업
	 */
	public static Job voiceClone(Long userId) {
		Job job = new Job();
		job.jobType = "VOICE_CLONE";
		job.userId = userId;
		return job;
	}

	/**
	 * 프로필 캐릭터 작업을 만든다.
	 *
	 * @param userId 캐릭터를 만들 회원 ID
	 * @return 저장 전 AVATAR 작업
	 */
	public static Job avatar(Long userId) {
		Job job = new Job();
		job.jobType = "AVATAR";
		job.userId = userId;
		return job;
	}

	// 큐에 넣었다고 센다.
	public void markPublished() {
		attempts++;
	}

	// 감지 작업인지 본다.
	public boolean isDetect() {
		return "DETECT".equals(jobType);
	}

	// 결과나 fail을 이미 받았는지. 받았으면 같은 jobId가 다시 와도 무시한다.
	public boolean isFinished() {
		return "DONE".equals(status) || "FAILED".equals(status);
	}

	// 성공으로 끝낸다.
	public void complete() {
		status = "DONE";
	}

	/**
	 * 실패로 끝내고 이유를 남긴다.
	 *
	 * @param reason 남길 실패 이유
	 */
	public void fail(String reason) {
		status = "FAILED";
		failReason = reason;
	}

}
