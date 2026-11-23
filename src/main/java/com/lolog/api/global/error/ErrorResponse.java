package com.lolog.api.global.error;

// 모든 오류 응답의 모양. field는 400 INVALID_REQUEST일 때 틀린 칸의 경로(예: lines[2].text)이고, 그 밖에는 null이다.
public record ErrorResponse(String code, String message, String field) {

	public static ErrorResponse of(ErrorCode code) {
		return of(code, null);
	}

	public static ErrorResponse of(ErrorCode code, String field) {
		return new ErrorResponse(code.name(), code.message(), field);
	}

	public static ErrorResponse of(ApiException e) {
		return new ErrorResponse(e.getCode(), e.getMessage(), e.getField());
	}

}
