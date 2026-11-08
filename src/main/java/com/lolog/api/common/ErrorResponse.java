package com.lolog.api.common;

// 모든 오류 응답의 모양. field는 400 INVALID_REQUEST일 때 틀린 칸의 경로(예: lines[2].text)이고, 그 밖에는 null이다.
public record ErrorResponse(String code, String message, String field) {

	public ErrorResponse(String code, String message) {
		this(code, message, null);
	}

}
