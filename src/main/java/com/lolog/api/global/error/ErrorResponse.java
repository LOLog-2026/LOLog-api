package com.lolog.api.global.error;

/**
 * 모든 오류 응답의 모양. field는 400 INVALID_REQUEST일 때 틀린 칸의 경로(예: lines[2].text)이고, 그 밖에는 null이다.
 *
 * @param code 오류 코드 이름(ErrorCode)
 * @param message 개발자용 설명
 * @param field 400 INVALID_REQUEST일 때 틀린 칸의 경로, 그 밖에는 null
 */
public record ErrorResponse(String code, String message, String field) {

	/**
	 * 오류 코드만으로 응답을 만든다.
	 *
	 * @param code 답할 오류 코드
	 * @return field가 null인 오류 응답
	 */
	public static ErrorResponse of(ErrorCode code) {
		return of(code, null);
	}

	/**
	 * 오류 코드와 틀린 칸으로 응답을 만든다.
	 *
	 * @param code 답할 오류 코드
	 * @param field 틀린 칸의 경로, 없으면 null
	 * @return 오류 응답
	 */
	public static ErrorResponse of(ErrorCode code, String field) {
		return new ErrorResponse(code.name(), code.message(), field);
	}

	/**
	 * 예외에 담긴 값으로 응답을 만든다.
	 *
	 * @param e 서비스가 던진 예외
	 * @return 예외의 코드·문구·틀린 칸을 담은 오류 응답
	 */
	public static ErrorResponse of(ApiException e) {
		return new ErrorResponse(e.getCode(), e.getMessage(), e.getField());
	}

}
