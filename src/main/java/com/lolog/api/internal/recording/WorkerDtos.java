package com.lolog.api.internal.recording;

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

	/**
	 * 묶음 하나의 감지 결과다.
	 *
	 * @param jobId 감지 작업 ID
	 * @param partNo 묶음 번호(1부터)
	 * @param cards 감지한 카드(없으면 빈 목록)
	 * @param carryFromMs 다음 묶음에 넘길 구간의 시작(ms, 녹음 시작 기준), 없으면 null
	 */
	public record DetectResult(
			@NotNull Long jobId,
			@NotNull @Min(1) Integer partNo,
			@NotNull List<@Valid @NotNull DetectedCard> cards,
			@PositiveOrZero Long carryFromMs) {
	}

	/**
	 * 감지한 카드 하나다.
	 *
	 * @param kind 종류(웃김, 황당, 추억)
	 * @param summary 요약(30자 이하)
	 * @param startMs 시작 시각(ms, 녹음 시작 기준)
	 * @param endMs 끝 시각(ms, startMs보다 커야 한다)
	 * @param speakers 화자 키(화자1부터 차례로, 1~4명)
	 * @param lines 대사(lineNo가 1부터 차례로)
	 */
	public record DetectedCard(
			@NotNull @Pattern(regexp = "^(웃김|황당|추억)$") String kind,
			@NotBlank @Size(max = 30) String summary,
			@NotNull @PositiveOrZero Long startMs,
			@NotNull Long endMs,
			@NotNull @Size(min = 1, max = 4) List<@NotNull String> speakers,
			@NotEmpty List<@Valid @NotNull DetectedLine> lines) {
	}

	/**
	 * 카드의 대사 한 줄이다.
	 *
	 * @param lineNo 대사 순번(1부터)
	 * @param speaker 말한 화자 키(카드의 speakers 중 하나)
	 * @param text 대사 글
	 * @param startMs 시작 시각(ms), null 가능
	 * @param endMs 끝 시각(ms), null 가능
	 */
	public record DetectedLine(
			@NotNull @Min(1) Integer lineNo,
			@NotNull String speaker,
			@NotBlank String text,
			@PositiveOrZero Long startMs,
			@PositiveOrZero Long endMs) {
	}

}
