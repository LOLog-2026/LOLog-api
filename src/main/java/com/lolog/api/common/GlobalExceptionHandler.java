package com.lolog.api.common;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.core.JacksonException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final int MAX_CAUSE_DEPTH = 10;

	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
		return ResponseEntity.status(e.getStatus())
			.body(new ErrorResponse(e.getCode(), e.getMessage(), e.getField()));
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleNotValid(MethodArgumentNotValidException e) {
		String field = e.getBindingResult().getFieldError() != null ? e.getBindingResult().getFieldError().getField()
				: null;
		return invalid(field);
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
		return invalid(e.getCause() instanceof JacksonException je ? path(je.getPath()) : null);
	}

	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return invalid(e.getName());
	}

	private static ResponseEntity<ErrorResponse> invalid(String field) {
		return ResponseEntity.badRequest()
			.body(new ErrorResponse("INVALID_REQUEST", "요청 형식이 올바르지 않습니다.", field));
	}

	// JSON을 읽다 틀린 위치를 lines[2].text 모양으로 만든다. 위치를 모르면 null이다.
	private static String path(List<JacksonException.Reference> references) {
		StringBuilder result = new StringBuilder();
		for (JacksonException.Reference reference : references) {
			if (reference.getPropertyName() != null) {
				result.append(result.isEmpty() ? "" : ".").append(reference.getPropertyName());
			}
			else if (reference.getIndex() >= 0) {
				result.append('[').append(reference.getIndex()).append(']');
			}
		}
		return result.isEmpty() ? null : result.toString();
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) throws Exception {
		// 프레임워크가 상태를 정하는 예외(없는 주소의 404, 405 등)와 보안 예외는 500으로 바꾸지 않고 원래 처리로 넘긴다.
		if (e instanceof org.springframework.web.ErrorResponse || e instanceof AuthenticationException
				|| e instanceof AccessDeniedException) {
			throw e;
		}
		log.error("처리하지 못한 예외", withoutMessages(e, 0));
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(new ErrorResponse("INTERNAL_ERROR", "서버 오류가 발생했습니다."));
	}

	// DB 오류 등의 메시지에는 저장하려던 행, 즉 대화 글이 들어갈 수 있다. 로그에는 예외 종류와 스택만 남긴다.
	private static RuntimeException withoutMessages(Throwable e, int depth) {
		Throwable cause = e.getCause();
		RuntimeException copy = new RuntimeException(e.getClass().getName(),
				cause != null && depth < MAX_CAUSE_DEPTH ? withoutMessages(cause, depth + 1) : null);
		copy.setStackTrace(e.getStackTrace());
		return copy;
	}

}
