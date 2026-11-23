package com.lolog.api.global.error;

import org.springframework.http.HttpStatus;

// 명세의 오류 코드를 담는 예외. 코드, HTTP 상태, 문구는 모두 ErrorCode가 정한다.
// field는 틀린 칸의 경로다(예: speakers[1].character). 없으면 null이다.
public class ApiException extends RuntimeException {

	private final ErrorCode errorCode;
	private final String field;

	public ApiException(ErrorCode errorCode) {
		this(errorCode, null);
	}

	public ApiException(ErrorCode errorCode, String field) {
		super(errorCode.message());
		this.errorCode = errorCode;
		this.field = field;
	}

	// 400 INVALID_REQUEST. field는 틀린 칸이다.
	public static ApiException invalid(String field) {
		return new ApiException(ErrorCode.INVALID_REQUEST, field);
	}

	public ErrorCode getErrorCode() {
		return errorCode;
	}

	public HttpStatus getStatus() {
		return errorCode.status();
	}

	public String getCode() {
		return errorCode.name();
	}

	public String getField() {
		return field;
	}

}
