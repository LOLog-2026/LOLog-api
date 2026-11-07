package com.lolog.api.recording;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import java.time.OffsetDateTime;
import java.util.List;

public final class RecordingDtos {

	private static final String UUID_PATTERN = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

	private RecordingDtos() {
	}

	public record UploadRequest(
			@NotNull @Pattern(regexp = UUID_PATTERN) String clientRecordingId,
			@NotNull @Min(1) Integer partNo,
			@NotNull Boolean isLast,
			@NotNull OffsetDateTime startedAt,
			@NotNull @PositiveOrZero @Max(86400) Integer durationSec,
			@NotNull List<@Valid @NotNull Sentence> sentences) {
	}

	public record Sentence(@NotNull @PositiveOrZero Long startMs, @NotNull Long endMs, @NotBlank String text) {
	}

	public record UploadResponse(Long recordingId, RecordingStatus status) {
	}

	public record StatusResponse(Long recordingId, RecordingStatus status, int cardCount) {
	}

	// lolog:detect 스트림의 payload 모양. API 명세의 큐 메시지와 같다.
	public record DetectMessage(Long jobId, Long recordingId, Integer partNo, boolean isLast, List<Sentence> sentences) {
	}

}
