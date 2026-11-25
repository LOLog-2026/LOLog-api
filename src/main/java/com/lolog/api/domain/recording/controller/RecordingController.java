package com.lolog.api.domain.recording.controller;

import com.lolog.api.domain.recording.dto.RecordingDtos.RecordingPage;
import com.lolog.api.domain.recording.dto.RecordingDtos.StatusResponse;
import com.lolog.api.domain.recording.dto.RecordingDtos.UploadRequest;
import com.lolog.api.domain.recording.dto.RecordingDtos.UploadResponse;
import com.lolog.api.domain.recording.service.RecordingService;
import com.lolog.api.global.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 녹음 업로드와 목록·상태 조회 API.
@RestController
@RequestMapping("/api/recordings")
public class RecordingController {

	private final RecordingService recordingService;
	private final CurrentUserProvider currentUser;

	/** 녹음 서비스와 현재 사용자 정보를 받는다. */
	public RecordingController(RecordingService recordingService, CurrentUserProvider currentUser) {
		this.recordingService = recordingService;
		this.currentUser = currentUser;
	}

	/**
	 * 녹음 묶음 하나를 받는다.
	 *
	 * @param request 업로드할 녹음 묶음(본문)
	 * @return 녹음 ID와 지금 상태(202)
	 */
	@PostMapping
	@ResponseStatus(HttpStatus.ACCEPTED)
	public UploadResponse upload(@Valid @RequestBody UploadRequest request) {
		return recordingService.upload(currentUser.currentUserId(), request);
	}

	/**
	 * 내 녹음 목록을 페이지로 돌려준다.
	 *
	 * @param cursor 앞 페이지의 nextCursor(쿼리, 없으면 처음부터)
	 * @param size 페이지 크기(쿼리, 기본 20, 1~50)
	 * @param status 걸러 볼 상태들(쿼리, 쉼표로 잇기, 없으면 전부)
	 * @return 녹음 목록과 다음 cursor
	 */
	@GetMapping
	public RecordingPage list(@RequestParam(required = false) String cursor,
			@RequestParam(required = false) Integer size, @RequestParam(required = false) String status) {
		return recordingService.list(currentUser.currentUserId(), cursor, size, status);
	}

	/**
	 * 녹음 하나의 상태를 돌려준다.
	 *
	 * @param id 녹음 ID(경로)
	 * @return 녹음 상태와 카드 수
	 */
	@GetMapping("/{id}")
	public StatusResponse status(@PathVariable Long id) {
		return recordingService.status(currentUser.currentUserId(), id);
	}

}
