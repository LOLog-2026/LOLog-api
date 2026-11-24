package com.lolog.api.domain.recording.dto;

import com.lolog.api.domain.recording.entity.RecordingStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;
import java.util.List;

// 녹음 업로드·조회 API의 요청·응답 모양.
public final class RecordingDtos {

	private static final String UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

	private RecordingDtos() {
	}

	// 녹음 묶음 하나의 업로드 요청.
	public record UploadRequest(
			@NotNull @Pattern(regexp = UUID_PATTERN) String clientRecordingId,
			@NotNull @Min(1) Integer partNo,
			@NotNull Boolean isLast,
			@NotNull OffsetDateTime startedAt,
			@NotNull @PositiveOrZero @Max(86400) Integer durationSec,
			@NotNull List<@Valid @NotNull Sentence> sentences,
			// 음성 저장을 켰는지(audio.md 6장). 없으면 false(예전 앱).
			Boolean audioSaved) {
	}

	// 문장 하나와 녹음 안의 시작·끝 시각(ms).
	public record Sentence(@NotNull @PositiveOrZero Long startMs, @NotNull Long endMs, @NotBlank String text) {
	}

	// 업로드 응답. 녹음 ID와 지금 상태.
	public record UploadResponse(Long recordingId, RecordingStatus status) {
	}

	// 녹음 하나의 상태와 만든 카드 수.
	public record StatusResponse(Long recordingId, RecordingStatus status, int cardCount) {
	}

	// GET /api/recordings. 앱을 다시 켰을 때 "분석 중"인 녹음을 찾는 데 쓴다.
	public record RecordingPage(List<RecordingListItem> recordings, String nextCursor) {
	}

	// 녹음 목록의 녹음 하나.
	public record RecordingListItem(Long recordingId, String clientRecordingId, RecordingStatus status,
			OffsetDateTime startedAt, int cardCount) {
	}

	// lolog:detect 스트림의 payload 모양. API 명세의 큐 메시지와 같다.
	public record DetectMessage(Long jobId, Long recordingId, Integer partNo, boolean isLast, List<Sentence> sentences) {
	}

}
