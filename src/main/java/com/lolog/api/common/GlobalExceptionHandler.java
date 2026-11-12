package com.lolog.api.common;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import tools.jackson.core.JacksonException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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

	// multipart의 파트가 빠짐(예: 이미지 file)
	@ExceptionHandler(MissingServletRequestPartException.class)
	public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
		return invalid(e.getRequestPartName());
	}

	// 완성 이미지 20MB 초과(spring.servlet.multipart.max-file-size)
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
		return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
			.body(new ErrorResponse("PAYLOAD_TOO_LARGE", "파일이 너무 큽니다."));
	}

	// 파일 주소에 multipart가 아닌 본문(api-dto.md 오류 코드). consumes가 없는 주소는 Spring이 이 예외를 던진다.
	// 크기 초과(MaxUploadSizeExceededException)는 위의 더 좁은 처리가 먼저다.
	@ExceptionHandler(MultipartException.class)
	public ResponseEntity<ErrorResponse> handleNotMultipart(MultipartException e) {
		return ResponseEntity.status(HttpStatus.UNSUPPORTED_MEDIA_TYPE)
			.body(new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "이 주소가 받지 않는 Content-Type입니다."));
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

	// Spring이 상태를 정하는 예외(없는 주소, 틀린 메서드, 틀린 Content-Type 등). 상태와 헤더(405의 Allow 등)는 그대로 두고
	// 본문만 ErrorResponse로 바꾼다(api-dto.md 오류 코드, LOLog-docs PR #60).
	private static ResponseEntity<ErrorResponse> framework(Exception e, org.springframework.web.ErrorResponse error) {
		HttpStatusCode status = error.getStatusCode();
		if (status.is5xxServerError()) {
			log.error("처리하지 못한 예외", SafeLog.withoutMessages(e));
			return ResponseEntity.status(status)
				.headers(error.getHeaders())
				.body(new ErrorResponse("INTERNAL_ERROR", "서버 오류가 발생했습니다."));
		}
		ErrorResponse body = switch (status.value()) {
			case 404 -> new ErrorResponse("NOT_FOUND", "없는 주소입니다.");
			case 405 -> new ErrorResponse("METHOD_NOT_ALLOWED", "이 주소에서 쓸 수 없는 메서드입니다.");
			case 406 -> new ErrorResponse("NOT_ACCEPTABLE", "JSON으로만 답할 수 있습니다.");
			case 415 -> new ErrorResponse("UNSUPPORTED_MEDIA_TYPE", "이 주소가 받지 않는 Content-Type입니다.");
			default -> new ErrorResponse("INVALID_REQUEST", "요청 형식이 올바르지 않습니다.", field(e));
		};
		return ResponseEntity.status(status).headers(error.getHeaders()).body(body);
	}

	// 빠진 쿼리·헤더면 그 이름을 field에 넣는다.
	private static String field(Exception e) {
		if (e instanceof MissingServletRequestParameterException missing) {
			return missing.getParameterName();
		}
		if (e instanceof MissingRequestHeaderException missing) {
			return missing.getHeaderName();
		}
		return null;
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleUnexpected(Exception e) throws Exception {
		// 보안 예외는 500으로 바꾸지 않고 Spring Security 처리(401 UNAUTHORIZED 등)로 넘긴다.
		if (e instanceof AuthenticationException || e instanceof AccessDeniedException) {
			throw e;
		}
		if (e instanceof org.springframework.web.ErrorResponse error) {
			return framework(e, error);
		}
		log.error("처리하지 못한 예외", SafeLog.withoutMessages(e));
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
			.body(new ErrorResponse("INTERNAL_ERROR", "서버 오류가 발생했습니다."));
	}

}
