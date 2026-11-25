package com.lolog.api.global.error;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerTest {

	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new ThrowingController())
			.setControllerAdvice(new GlobalExceptionHandler())
			.build();
	}

	// ---- ApiException ----

	@Test
	@DisplayName("ApiException은 그 코드의 상태와 code·message·field로 답한다")
	void ApiException은_그_코드로_답한다() throws Exception {
		// when & then
		mvc.perform(get("/test/api"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CHARACTER_NOT_READY"))
			.andExpect(jsonPath("$.message").value(ErrorCode.CHARACTER_NOT_READY.message()))
			.andExpect(jsonPath("$.field").isEmpty());
	}

	@Test
	@DisplayName("field가 있는 ApiException은 field를 담는다")
	void ApiException의_field를_담는다() throws Exception {
		// when & then
		mvc.perform(get("/test/api-field"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("UNKNOWN_CHARACTER"))
			.andExpect(jsonPath("$.field").value("speakers[1].character"));
	}

	// ---- 요청 검증·읽기 ----

	@Test
	@DisplayName("@Valid에 걸리면 처음 틀린 칸을 field로 400 INVALID_REQUEST다")
	void 검증에_걸리면_INVALID_REQUEST() throws Exception {
		// when & then
		mvc.perform(post("/test/valid").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\" \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.message").value(ErrorCode.INVALID_REQUEST.message()))
			.andExpect(jsonPath("$.field").value("name"));
	}

	@Test
	@DisplayName("JSON 값의 형식이 틀리면 lines[1].number 모양 경로를 field로 400이다")
	void JSON_형식이_틀리면_경로를_field로() throws Exception {
		// given
		String body = "{\"lines\":[{\"number\":1},{\"number\":\"x\"}]}";

		// when & then
		mvc.perform(post("/test/lines").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("lines[1].number"));
	}

	@Test
	@DisplayName("JSON 문법이 깨져 위치를 모르면 field 없이 400이다")
	void JSON이_깨지면_field_없이_400() throws Exception {
		// when & then
		mvc.perform(post("/test/lines").contentType(MediaType.APPLICATION_JSON).content("{"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").isEmpty());
	}

	@Test
	@DisplayName("빠진 multipart 파트는 그 이름을 field로 400이다")
	void 빠진_파트는_그_이름으로_400() throws Exception {
		// when & then
		mvc.perform(get("/test/missing-part"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("file"));
	}

	@Test
	@DisplayName("경로 값의 형식이 틀리면 그 이름을 field로 400이다")
	void 경로_값이_틀리면_그_이름으로_400() throws Exception {
		// when & then
		mvc.perform(get("/test/items/abc"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("id"));
	}

	@Test
	@DisplayName("빠진 쿼리 값은 그 이름을 field로 400이다")
	void 빠진_쿼리는_그_이름으로_400() throws Exception {
		// when & then
		mvc.perform(get("/test/query"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("cursor"));
	}

	@Test
	@DisplayName("빠진 헤더는 그 이름을 field로 400이다")
	void 빠진_헤더는_그_이름으로_400() throws Exception {
		// when & then
		mvc.perform(get("/test/header"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("X-Request-Id"));
	}

	// ---- 파일 ----

	@Test
	@DisplayName("업로드 크기 초과는 413 PAYLOAD_TOO_LARGE다")
	void 크기_초과는_413() throws Exception {
		// when & then
		mvc.perform(get("/test/too-large"))
			.andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"))
			.andExpect(jsonPath("$.field").isEmpty());
	}

	@Test
	@DisplayName("multipart가 아닌 본문(MultipartException)은 415 UNSUPPORTED_MEDIA_TYPE이다")
	void multipart가_아니면_415() throws Exception {
		// when & then
		mvc.perform(get("/test/not-multipart"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	// ---- Spring이 상태를 정하는 예외 ----

	@Test
	@DisplayName("틀린 메서드는 405 METHOD_NOT_ALLOWED이고 Allow 헤더를 그대로 둔다")
	void 틀린_메서드는_405() throws Exception {
		// when & then
		mvc.perform(post("/test/api"))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(header().string("Allow", "GET"))
			.andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
	}

	@Test
	@DisplayName("JSON 주소에 다른 Content-Type이면 415 UNSUPPORTED_MEDIA_TYPE이다")
	void 틀린_Content_Type은_415() throws Exception {
		// when & then
		mvc.perform(post("/test/lines").contentType(MediaType.TEXT_PLAIN).content("hello"))
			.andExpect(status().isUnsupportedMediaType())
			.andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
	}

	@Test
	@DisplayName("404로 정한 ResponseStatusException은 NOT_FOUND다")
	void 상태_404는_NOT_FOUND() throws Exception {
		// when & then
		mvc.perform(get("/test/status/404"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("5xx로 정한 Spring 예외는 그 상태에 INTERNAL_ERROR 본문이다")
	void 상태_5xx는_INTERNAL_ERROR() throws Exception {
		// when & then
		mvc.perform(get("/test/status/503"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.message()));
	}

	// ---- 그 밖 ----

	@Test
	@DisplayName("모르는 예외는 500 INTERNAL_ERROR이고 예외 문구를 내보내지 않는다")
	void 모르는_예외는_500() throws Exception {
		// when & then
		mvc.perform(get("/test/unexpected"))
			.andExpect(status().isInternalServerError())
			.andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
			.andExpect(jsonPath("$.message").value(ErrorCode.INTERNAL_ERROR.message()))
			.andExpect(jsonPath("$.field").isEmpty());
	}

	@Test
	@DisplayName("권한 예외는 500으로 바꾸지 않고 Spring Security로 넘긴다")
	void 권한_예외는_넘긴다() {
		// when
		Throwable thrown = catchThrowable(() -> mvc.perform(get("/test/denied")));

		// then
		assertThat(thrown).hasRootCauseInstanceOf(AccessDeniedException.class);
	}

	@Test
	@DisplayName("인증 예외는 500으로 바꾸지 않고 Spring Security로 넘긴다")
	void 인증_예외는_넘긴다() {
		// when
		Throwable thrown = catchThrowable(() -> mvc.perform(get("/test/unauthenticated")));

		// then
		assertThat(thrown).hasRootCauseInstanceOf(BadCredentialsException.class);
	}

	// ---- 테스트용 컨트롤러 ----

	/**
	 * 검증용 본문
	 *
	 * @param name 비어 있으면 안 되는 값
	 */
	record NameBody(@NotBlank String name) {
	}

	/**
	 * 경로 확인용 본문
	 *
	 * @param lines 줄 목록
	 */
	record LinesBody(List<Line> lines) {
	}

	/**
	 * 줄 하나
	 *
	 * @param number 숫자여야 하는 값
	 */
	record Line(int number) {
	}

	// 주소마다 정한 예외를 던진다.
	@RestController
	static class ThrowingController {

		@GetMapping("/test/api")
		String api() {
			throw new ApiException(ErrorCode.CHARACTER_NOT_READY);
		}

		@GetMapping("/test/api-field")
		String apiField() {
			throw new ApiException(ErrorCode.UNKNOWN_CHARACTER, "speakers[1].character");
		}

		@PostMapping("/test/valid")
		String valid(@Valid @RequestBody NameBody body) {
			return body.name();
		}

		@PostMapping("/test/lines")
		String lines(@RequestBody LinesBody body) {
			return "ok";
		}

		@GetMapping("/test/missing-part")
		String missingPart() throws MissingServletRequestPartException {
			throw new MissingServletRequestPartException("file");
		}

		@GetMapping("/test/items/{id}")
		String item(@PathVariable Long id) {
			return "ok";
		}

		@GetMapping("/test/query")
		String query(@RequestParam String cursor) {
			return cursor;
		}

		@GetMapping("/test/header")
		String header(@RequestHeader("X-Request-Id") String id) {
			return id;
		}

		@GetMapping("/test/too-large")
		String tooLarge() {
			throw new MaxUploadSizeExceededException(20L * 1024 * 1024);
		}

		@GetMapping("/test/not-multipart")
		String notMultipart() {
			throw new MultipartException("multipart가 아님");
		}

		@GetMapping("/test/status/{code}")
		String status(@PathVariable int code) {
			throw new ResponseStatusException(HttpStatus.valueOf(code));
		}

		@GetMapping("/test/unexpected")
		String unexpected() {
			throw new IllegalStateException("내보내면 안 되는 내부 문구");
		}

		@GetMapping("/test/denied")
		String denied() {
			throw new AccessDeniedException("거절");
		}

		@GetMapping("/test/unauthenticated")
		String unauthenticated() {
			throw new BadCredentialsException("틀린 토큰");
		}

	}

}
