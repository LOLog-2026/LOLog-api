package com.lolog.api.common;

import org.springframework.http.HttpStatus;

// 명세의 오류 코드와 HTTP 상태를 함께 담는 예외.
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public ApiException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

}
