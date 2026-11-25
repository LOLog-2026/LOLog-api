package com.lolog.api.internal.job;

import com.lolog.api.internal.job.JobWorkerDtos.JobFailRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Jetson 워커가 부르는 작업 실패 API. 감지·글·만화·쇼츠·캐릭터·목소리 작업이 모두 쓴다.
// X-Internal-Token 검사는 InternalSecurityConfig가 한다.
@RestController
public class JobWorkerController {

	private final JobFailService jobFails;

	/** 쓰는 서비스를 받는다. */
	public JobWorkerController(JobFailService jobFails) {
		this.jobFails = jobFails;
	}

	/**
	 * 작업이 실패했다고 받는다.
	 *
	 * @param id 실패한 작업 ID(경로)
	 * @param request 본문 { reason }
	 */
	@PostMapping("/internal/jobs/{id}/fail")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void fail(@PathVariable Long id, @Valid @RequestBody JobFailRequest request) {
		jobFails.fail(id, request.reason());
	}

}
