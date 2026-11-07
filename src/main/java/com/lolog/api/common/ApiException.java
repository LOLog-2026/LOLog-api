package com.lolog.api.common;

import org.springframework.http.HttpStatus;

// 명세의 오류 코드와 HTTP 상태를 함께 담는 예외. field는 400 INVALID_REQUEST일 때 틀린 칸의 경로다.
public class ApiException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final String field;

	public ApiException(HttpStatus status, String code, String message) {
		this(status, code, message, null);
	}

	public ApiException(HttpStatus status, String code, String message, String field) {
		super(message);
		this.status = status;
		this.code = code;
		this.field = field;
	}

	public static ApiException invalid(String field, String message) {
		return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message, field);
	}

	public HttpStatus getStatus() {
		return status;
	}

	public String getCode() {
		return code;
	}

	public String getField() {
		return field;
	}

}
