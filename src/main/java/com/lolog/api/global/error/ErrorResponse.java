package com.lolog.api.global.error;

// 모든 오류 응답의 모양. field는 400 INVALID_REQUEST일 때 틀린 칸의 경로(예: lines[2].text)이고, 그 밖에는 null이다.
public record ErrorResponse(String code, String message, String field) {

	// 오류 코드만으로 응답을 만든다.
	public static ErrorResponse of(ErrorCode code) {
		return of(code, null);
	}

	// 오류 코드와 틀린 칸으로 응답을 만든다.
	public static ErrorResponse of(ErrorCode code, String field) {
		return new ErrorResponse(code.name(), code.message(), field);
	}

	// 예외에 담긴 값으로 응답을 만든다.
	public static ErrorResponse of(ApiException e) {
		return new ErrorResponse(e.getCode(), e.getMessage(), e.getField());
	}

}
