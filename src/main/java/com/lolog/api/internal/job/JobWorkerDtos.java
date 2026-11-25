package com.lolog.api.internal.job;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// 워커가 작업 실패를 알릴 때 보내는 본문. 모양은 API DTO 명세 5장과 같다.
public final class JobWorkerDtos {

	private JobWorkerDtos() {
	}

	/**
	 * 작업 실패 본문. reason은 실패 이유다.
	 *
	 * @param reason 실패 이유(빈 글은 안 됨, 500자 이하)
	 */
	public record JobFailRequest(@NotBlank @Size(max = 500) String reason) {
	}

}
