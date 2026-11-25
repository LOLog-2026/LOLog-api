package com.lolog.api.internal.recording;

import com.lolog.api.internal.recording.WorkerDtos.DetectResult;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Jetson 워커가 부르는 감지 결과 API. X-Internal-Token 검사는 InternalSecurityConfig가 한다.
@RestController
public class WorkerController {

	private final DetectResultService detectResults;

	/** 쓰는 서비스를 받는다. */
	public WorkerController(DetectResultService detectResults) {
		this.detectResults = detectResults;
	}

	/**
	 * 묶음 하나의 감지 결과를 받는다.
	 *
	 * @param id 녹음 ID(경로)
	 * @param result 묶음 하나의 감지 결과
	 */
	@PostMapping("/internal/recordings/{id}/cards")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cards(@PathVariable Long id, @Valid @RequestBody DetectResult result) {
		detectResults.receive(id, result);
	}

}
