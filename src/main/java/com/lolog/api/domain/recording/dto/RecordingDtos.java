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

	/**
	 * 녹음 묶음 하나의 업로드 요청.
	 *
	 * @param clientRecordingId 앱이 녹음마다 만든 UUID. 같은 녹음의 묶음을 묶는 데 쓴다
	 * @param partNo 묶음 번호(1부터 차례로)
	 * @param isLast 이 녹음의 마지막 묶음인지
	 * @param startedAt 녹음 시작 시각
	 * @param durationSec 이 묶음의 길이(초, 0~86400)
	 * @param sentences 이 묶음의 문장 목록
	 * @param audioSaved 음성 저장을 켰는지. null이면 false로 본다
	 */
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

	/**
	 * 문장 하나와 녹음 안의 시작·끝 시각(ms).
	 *
	 * @param startMs 녹음 시작부터 문장 시작까지(ms)
	 * @param endMs 녹음 시작부터 문장 끝까지(ms). startMs보다 커야 한다
	 * @param text 문장 글
	 */
	public record Sentence(@NotNull @PositiveOrZero Long startMs, @NotNull Long endMs, @NotBlank String text) {
	}

	/**
	 * 업로드 응답. 녹음 ID와 지금 상태.
	 *
	 * @param recordingId 묶음이 속한 녹음 ID
	 * @param status 녹음의 지금 상태
	 */
	public record UploadResponse(Long recordingId, RecordingStatus status) {
	}

	/**
	 * 녹음 하나의 상태와 만든 카드 수.
	 *
	 * @param recordingId 녹음 ID
	 * @param status 녹음의 지금 상태
	 * @param cardCount 이 녹음으로 만든 카드 수
	 */
	public record StatusResponse(Long recordingId, RecordingStatus status, int cardCount) {
	}

	/**
	 * GET /api/recordings. 앱을 다시 켰을 때 "분석 중"인 녹음을 찾는 데 쓴다.
	 *
	 * @param recordings 이 페이지의 녹음 목록(최신순)
	 * @param nextCursor 다음 페이지를 받을 cursor. 더 없으면 null
	 */
	public record RecordingPage(List<RecordingListItem> recordings, String nextCursor) {
	}

	/**
	 * 녹음 목록의 녹음 하나.
	 *
	 * @param recordingId 녹음 ID
	 * @param clientRecordingId 앱이 만든 녹음 UUID
	 * @param status 녹음의 지금 상태
	 * @param startedAt 녹음 시작 시각
	 * @param cardCount 이 녹음으로 만든 카드 수
	 */
	public record RecordingListItem(Long recordingId, String clientRecordingId, RecordingStatus status,
			OffsetDateTime startedAt, int cardCount) {
	}

	/**
	 * lolog:detect 스트림의 payload 모양. API 명세의 큐 메시지와 같다.
	 *
	 * @param jobId 감지 작업 ID
	 * @param recordingId 녹음 ID
	 * @param partNo 감지할 묶음 번호
	 * @param isLast 마지막 묶음처럼 처리할지(넘겨 둔 글만 보낼 때도 true)
	 * @param sentences 감지할 문장 목록(앞 묶음에서 넘겨받은 글 포함)
	 */
	public record DetectMessage(Long jobId, Long recordingId, Integer partNo, boolean isLast, List<Sentence> sentences) {
	}

}
