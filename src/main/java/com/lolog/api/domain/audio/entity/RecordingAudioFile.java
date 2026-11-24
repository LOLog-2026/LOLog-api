package com.lolog.api.domain.audio.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 녹음의 음성 파일 하나(10분 단위, audio.md 6장). 녹음 하나에 번호(seq)마다 한 줄이다(uk_recording_audio_files_recording_id_seq).
// 같은 번호를 다시 올리면 덮어쓰는 upsert 한 문장이라 리포지토리의 native query로 만든다.
@Entity
@Table(name = "recording_audio_files")
@Getter
@NoArgsConstructor
public class RecordingAudioFile {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	// 녹음을 지우면 DB가 함께 지운다(ON DELETE CASCADE).
	@Column(name = "recording_id", nullable = false)
	private Long recordingId;

	@Column(nullable = false)
	private Integer seq;

	@Column(name = "audio_key", nullable = false, length = 255)
	private String audioKey;

	// 녹음 시작부터의 구간(ms)
	@Column(name = "start_ms", nullable = false)
	private Long startMs;

	@Column(name = "end_ms", nullable = false)
	private Long endMs;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime updatedAt;

}
