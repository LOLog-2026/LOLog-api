package com.lolog.api.global.error;

import com.lolog.api.global.util.SafeLog;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

// 컨트롤러에서 난 예외를 명세의 오류 응답으로 바꾼다.
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/**
	 * 서비스가 던진 오류 코드를 그대로 답한다.
	 *
	 * @param e 서비스가 던진 예외
	 * @return 그 오류 코드의 상태와 본문
	 */
	@ExceptionHandler(ApiException.class)
	public ResponseEntity<ErrorResponse> handleApi(ApiException e) {
		return ResponseEntity.status(e.getStatus()).body(ErrorResponse.of(e));
	}

	/**
	 * 검증에 실패한 첫 칸을 field로 400을 답한다.
	 *
	 * @param e 본문 검증에 실패한 예외
	 * @return 400 INVALID_REQUEST
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleNotValid(MethodArgumentNotValidException e) {
		String field = e.getBindingResult().getFieldError() != null ? e.getBindingResult().getFieldError().getField()
				: null;
		return invalid(field);
	}

	/**
	 * 본문 JSON을 읽지 못하면 틀린 위치를 field로 400을 답한다.
	 *
	 * @param e 본문을 읽지 못한 예외
	 * @return 400 INVALID_REQUEST
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
		return invalid(e.getCause() instanceof JacksonException je ? path(je.getPath()) : null);
	}

	/**
	 * multipart의 파트가 빠짐(예: 이미지 file)
	 *
	 * @param e 파트가 빠진 예외
	 * @return 빠진 파트 이름을 field로 한 400 INVALID_REQUEST
	 */
	@ExceptionHandler(MissingServletRequestPartException.class)
	public ResponseEntity<ErrorResponse> handleMissingPart(MissingServletRequestPartException e) {
		return invalid(e.getRequestPartName());
	}

	/**
	 * 완성 이미지 20MB 초과(spring.servlet.multipart.max-file-size)
	 *
	 * @param e 크기 초과 예외(쓰지 않음)
	 * @return 413 PAYLOAD_TOO_LARGE
	 */
	@ExceptionHandler(MaxUploadSizeExceededException.class)
	public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException e) {
		return respond(ErrorCode.PAYLOAD_TOO_LARGE, null);
	}

	/**
	 * 파일 주소에 multipart가 아닌 본문(api-dto.md 오류 코드). consumes가 없는 주소는 Spring이 이 예외를 던진다.
	 * 크기 초과(MaxUploadSizeExceededException)는 위의 더 좁은 처리가 먼저다.
	 *
	 * @param e multipart 예외(쓰지 않음)
	 * @return 415 UNSUPPORTED_MEDIA_TYPE
	 */
	@ExceptionHandler(MultipartException.class)
	public ResponseEntity<ErrorResponse> handleNotMultipart(MultipartException e) {
		return respond(ErrorCode.UNSUPPORTED_MEDIA_TYPE, null);
	}

	/**
	 * 경로·쿼리 값의 형식이 틀리면 그 이름을 field로 400을 답한다.
	 *
	 * @param e 경로·쿼리 값의 형식이 틀린 예외
	 * @return 그 값 이름을 field로 한 400 INVALID_REQUEST
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return invalid(e.getName());
	}

	/**
	 * 400 INVALID_REQUEST 응답을 만든다.
	 *
	 * @param field 틀린 칸의 경로, 모르면 null
	 * @return 400 INVALID_REQUEST 응답
	 */
	private static ResponseEntity<ErrorResponse> invalid(String field) {
		return respond(ErrorCode.INVALID_REQUEST, field);
	}

	/**
	 * 오류 코드로 응답을 만든다.
	 *
	 * @param code 답할 오류 코드
	 * @param field 틀린 칸의 경로, 없으면 null
	 * @return 그 오류 코드의 상태와 본문
	 */
	private static ResponseEntity<ErrorResponse> respond(ErrorCode code, String field) {
		return ResponseEntity.status(code.status()).body(ErrorResponse.of(code, field));
	}

	/**
	 * JSON을 읽다 틀린 위치를 lines[2].text 모양으로 만든다. 위치를 모르면 null이다.
	 *
	 * @param references Jackson이 알려 준 틀린 위치 목록
	 * @return lines[2].text 모양의 경로, 모르면 null
	 */
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

	/**
	 * Spring이 상태를 정하는 예외(없는 주소, 틀린 메서드, 틀린 Content-Type 등). 상태와 헤더(405의 Allow 등)는 그대로 두고
	 * 본문만 ErrorResponse로 바꾼다(api-dto.md 오류 코드, LOLog-docs PR #60).
	 *
	 * @param e Spring이 던진 예외
	 * @param error 같은 예외를 Spring ErrorResponse로 본 것(상태와 헤더를 꺼낸다)
	 * @return 상태와 헤더는 그대로 두고 본문만 바꾼 응답
	 */
	private static ResponseEntity<ErrorResponse> framework(Exception e, org.springframework.web.ErrorResponse error) {
		HttpStatusCode status = error.getStatusCode();
		if (status.is5xxServerError()) {
			log.error("처리하지 못한 예외", SafeLog.withoutMessages(e));
			return ResponseEntity.status(status)
				.headers(error.getHeaders())
				.body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
		}
		ErrorResponse body = switch (status.value()) {
			case 404 -> ErrorResponse.of(ErrorCode.NOT_FOUND);
			case 405 -> ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED);
			case 406 -> ErrorResponse.of(ErrorCode.NOT_ACCEPTABLE);
			case 415 -> ErrorResponse.of(ErrorCode.UNSUPPORTED_MEDIA_TYPE);
			default -> ErrorResponse.of(ErrorCode.INVALID_REQUEST, field(e));
		};
		return ResponseEntity.status(status).headers(error.getHeaders()).body(body);
	}

	/**
	 * 빠진 쿼리·헤더면 그 이름을 field에 넣는다.
	 *
	 * @param e Spring이 던진 예외
	 * @return 빠진 쿼리·헤더 이름, 아니면 null
	 */
	private static String field(Exception e) {
		if (e instanceof MissingServletRequestParameterException missing) {
			return missing.getParameterName();
		}
		if (e instanceof MissingRequestHeaderException missing) {
			return missing.getHeaderName();
		}
		return null;
	}

	/**
	 * 위에서 잡지 못한 예외를 처리한다. 모르는 예외는 500이다.
	 *
	 * @param e 앞에서 잡지 못한 예외
	 * @return Spring이 상태를 정한 예외면 그 상태, 나머지는 500 INTERNAL_ERROR
	 */
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
		return respond(ErrorCode.INTERNAL_ERROR, null);
	}

}
