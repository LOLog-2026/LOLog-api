package com.lolog.api.domain.video.entity;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

// 카드로 만든 쇼츠(video.md). 작업은 두 단계다: 3호가 목소리(VIDEO_VOICE), 2호가 그림·합치기(VIDEO_DRAW).
@Entity
@Table(name = "videos")
@Getter
@NoArgsConstructor
public class Video {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 카드를 지우면 DB가 null로 바꾼다(ON DELETE SET NULL).
	@Column(name = "card_id")
	private Long cardId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private VideoStatus status = VideoStatus.QUEUED;

	// 영상을 요청할 때 카드 요약을 복사해 둔다. 카드를 지워도 영상 응답에 쓴다.
	@Column(name = "card_summary", length = 30)
	private String cardSummary;

	// 3호가 보낸 콘티 JSON(video.md 5장). 서버는 기본만 검사하고 그대로 둔다.
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(columnDefinition = "jsonb")
	private String storyboard;

	// 대사 음성(wav). 2호가 받아 간 뒤 영상이 끝나거나 실패하면 지운다.
	@Column(name = "audio_key", length = 255)
	private String audioKey;

	// 완성 영상(mp4)과 목록용 그림(jpeg). DONE이 되면 생긴다.
	@Column(name = "video_key", length = 255)
	private String videoKey;

	@Column(name = "thumb_key", length = 255)
	private String thumbKey;

	@Column(name = "duration_ms")
	private Long durationMs;

	// 서버 기록용. 앱에는 보내지 않는다.
	@Column(name = "fail_reason", length = 500)
	private String failReason;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	/**
	 * 카드로 새 영상 요청을 만든다.
	 *
	 * @param cardId 영상을 만들 카드 ID
	 * @param userId 요청한 회원 ID
	 * @param cardSummary 요청할 때의 카드 요약(복사해 둔다)
	 */
	public Video(Long cardId, Long userId, String cardSummary) {
		this.cardId = cardId;
		this.userId = userId;
		this.cardSummary = cardSummary;
	}

	// 아직 만드는 중인지 본다.
	public boolean isInProgress() {
		return VideoStatus.IN_PROGRESS.contains(status);
	}

	// 3호가 카드 내용을 받아 갔을 때. 다시 받아 가도 상태는 한 번만 바뀐다.
	public void startVoice() {
		if (status == VideoStatus.QUEUED) {
			status = VideoStatus.VOICE;
		}
	}

	/**
	 * 3호가 보낸 콘티를 저장한다.
	 *
	 * @param json 콘티 JSON
	 */
	public void saveStoryboard(String json) {
		storyboard = json;
	}

	// 콘티를 받았는지 본다.
	public boolean hasStoryboard() {
		return storyboard != null;
	}

	/**
	 * 대사 음성을 받았을 때. 처음 받은 것이면 DRAWING이 되고 true를 돌려준다(2호 작업을 넣어야 함). 다시 받으면 파일만 바뀐다.
	 *
	 * @param key 대사 음성(wav)의 저장소 키
	 * @return 처음 받아 DRAWING이 됐으면 true
	 */
	public boolean attachAudio(String key) {
		audioKey = key;
		if (status == VideoStatus.DRAWING) {
			return false;
		}
		status = VideoStatus.DRAWING;
		return true;
	}

	/**
	 * 완성. 중간 음성 파일 키는 비운다(파일은 부른 쪽이 커밋 뒤에 지운다).
	 *
	 * @param videoKey 완성 영상(mp4)의 저장소 키
	 * @param thumbKey 목록용 그림(jpeg)의 저장소 키
	 * @param durationMs 영상 길이(ms)
	 */
	public void finish(String videoKey, String thumbKey, long durationMs) {
		this.videoKey = videoKey;
		this.thumbKey = thumbKey;
		this.durationMs = durationMs;
		this.audioKey = null;
		this.status = VideoStatus.DONE;
	}

	/**
	 * 실패. 중간 음성 파일 키는 비운다(파일은 부른 쪽이 커밋 뒤에 지운다).
	 *
	 * @param reason 워커가 보낸 실패 이유(서버 기록용)
	 */
	public void fail(String reason) {
		this.failReason = reason;
		this.audioKey = null;
		this.status = VideoStatus.FAILED;
	}

}
