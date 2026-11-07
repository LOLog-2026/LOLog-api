package com.lolog.api.recording;

import com.lolog.api.recording.WorkerDtos.DetectResult;
import com.lolog.api.recording.WorkerDtos.JobFailRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Jetson 워커가 부르는 감지 결과·실패 API. X-Internal-Token 검사는 InternalSecurityConfig가 한다.
@RestController
public class WorkerController {

	private final DetectResultService detectResults;

	public WorkerController(DetectResultService detectResults) {
		this.detectResults = detectResults;
	}

	@PostMapping("/internal/recordings/{id}/cards")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void cards(@PathVariable Long id, @Valid @RequestBody DetectResult result) {
		detectResults.receive(id, result);
	}

	@PostMapping("/internal/jobs/{id}/fail")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void fail(@PathVariable Long id, @Valid @RequestBody JobFailRequest request) {
		detectResults.fail(id, request.reason());
	}

}
