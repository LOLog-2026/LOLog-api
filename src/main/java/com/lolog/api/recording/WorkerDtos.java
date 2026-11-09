package com.lolog.api.recording;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;

// 워커가 /internal로 보내는 본문. 모양은 API DTO 명세 5장과 같다.
public final class WorkerDtos {

	private WorkerDtos() {
	}

	public record DetectResult(
			@NotNull Long jobId,
			@NotNull @Min(1) Integer partNo,
			@NotNull List<@Valid @NotNull DetectedCard> cards,
			@PositiveOrZero Long carryFromMs) {
	}

	public record DetectedCard(
			@NotNull @Pattern(regexp = "^(웃김|황당|추억)$") String kind,
			@NotBlank @Size(max = 30) String summary,
			@NotNull @PositiveOrZero Long startMs,
			@NotNull Long endMs,
			@NotNull @Size(min = 1, max = 4) List<@NotNull String> speakers,
			@NotEmpty List<@Valid @NotNull DetectedLine> lines) {
	}

	public record DetectedLine(
			@NotNull @Min(1) Integer lineNo,
			@NotNull String speaker,
			@NotBlank String text,
			@PositiveOrZero Long startMs,
			@PositiveOrZero Long endMs) {
	}

	public record JobFailRequest(@NotBlank @Size(max = 500) String reason) {
	}

}
