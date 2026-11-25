package com.lolog.api.domain.recording.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.recording.dto.RecordingDtos.UploadRequest;
import com.lolog.api.domain.recording.service.RecordingService;
import com.lolog.api.support.AppControllerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("RecordingController")
@WebMvcTest(RecordingController.class)
class RecordingControllerTest extends AppControllerTestSupport {

	private static final String UUID = "123e4567-e89b-12d3-a456-426614174000";

	@MockitoBean
	RecordingService recordings;

	// 맞는 업로드 본문. 바꿀 칸만 바꿔 쓴다.
	private static String body(String clientRecordingId, String partNo, String durationSec, String sentences) {
		return "{\"clientRecordingId\":" + clientRecordingId + ",\"partNo\":" + partNo
				+ ",\"isLast\":true,\"startedAt\":\"2026-11-25T10:00:00+09:00\",\"durationSec\":" + durationSec
				+ ",\"sentences\":" + sentences + "}";
	}

	private static String validBody() {
		return body("\"" + UUID + "\"", "1", "60", "[{\"startMs\":0,\"endMs\":1000,\"text\":\"안녕\"}]");
	}

	private void expectInvalid(String content, String field) throws Exception {
		mvc.perform(post("/api/recordings").header(AUTH, user()).contentType(MediaType.APPLICATION_JSON).content(content))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value(field));
		verifyNoInteractions(recordings);
	}

	@Test
	@DisplayName("토큰 없이 올리면 401 UNAUTHORIZED다")
	void 토큰_없이_올리면_401이다() throws Exception {
		// when & then
		mvc.perform(post("/api/recordings").contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().isUnauthorized());

		verifyNoInteractions(recordings);
	}

	@Test
	@DisplayName("맞는 본문을 올리면 202이고 읽은 값을 그대로 넘긴다")
	void 올리면_202이고_값을_넘긴다() throws Exception {
		// when & then
		mvc.perform(post("/api/recordings").header(AUTH, user()).contentType(MediaType.APPLICATION_JSON).content(validBody()))
			.andExpect(status().isAccepted());

		ArgumentCaptor<UploadRequest> captor = ArgumentCaptor.forClass(UploadRequest.class);
		verify(recordings).upload(eq(USER_ID), captor.capture());
		UploadRequest request = captor.getValue();
		assertThat(request.clientRecordingId()).isEqualTo(UUID);
		assertThat(request.partNo()).isEqualTo(1);
		assertThat(request.isLast()).isTrue();
		assertThat(request.durationSec()).isEqualTo(60);
		assertThat(request.sentences()).hasSize(1);
		assertThat(request.sentences().get(0).text()).isEqualTo("안녕");
		assertThat(request.audioSaved()).isNull();
	}

	@Test
	@DisplayName("clientRecordingId가 UUID 모양이 아니면 field는 clientRecordingId다")
	void clientRecordingId가_UUID가_아니면_400이다() throws Exception {
		// when & then
		expectInvalid(body("\"not-uuid\"", "1", "60", "[]"), "clientRecordingId");
	}

	@Test
	@DisplayName("partNo가 0이면 field는 partNo다")
	void partNo가_0이면_400이다() throws Exception {
		// when & then
		expectInvalid(body("\"" + UUID + "\"", "0", "60", "[]"), "partNo");
	}

	@Test
	@DisplayName("durationSec가 86401이면 field는 durationSec다")
	void durationSec가_86401이면_400이다() throws Exception {
		// when & then
		expectInvalid(body("\"" + UUID + "\"", "1", "86401", "[]"), "durationSec");
	}

	@Test
	@DisplayName("durationSec가 86400이면 받는다")
	void durationSec가_86400이면_받는다() throws Exception {
		// when & then
		mvc.perform(post("/api/recordings").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body("\"" + UUID + "\"", "1", "86400", "[]")))
			.andExpect(status().isAccepted());
	}

	@Test
	@DisplayName("sentences가 없으면 field는 sentences다")
	void sentences가_없으면_400이다() throws Exception {
		// when & then
		expectInvalid(body("\"" + UUID + "\"", "1", "60", "null"), "sentences");
	}

	@Test
	@DisplayName("문장 글이 비어 있으면 field는 sentences[0].text다")
	void 문장_글이_비면_400이다() throws Exception {
		// when & then
		expectInvalid(body("\"" + UUID + "\"", "1", "60", "[{\"startMs\":0,\"endMs\":1,\"text\":\" \"}]"),
				"sentences[0].text");
	}

	@Test
	@DisplayName("문장 startMs가 음수면 field는 sentences[0].startMs다")
	void 문장_startMs가_음수면_400이다() throws Exception {
		// when & then
		expectInvalid(body("\"" + UUID + "\"", "1", "60", "[{\"startMs\":-1,\"endMs\":1,\"text\":\"a\"}]"),
				"sentences[0].startMs");
	}

	@Test
	@DisplayName("startedAt이 시각 형식이 아니면 field는 startedAt이다")
	void startedAt_형식이_틀리면_400이다() throws Exception {
		// when & then
		expectInvalid(validBody().replace("2026-11-25T10:00:00+09:00", "어제"), "startedAt");
	}

	@Test
	@DisplayName("본문이 1MB를 넘으면 413 PAYLOAD_TOO_LARGE다")
	void 본문이_1MB를_넘으면_413이다() throws Exception {
		// given
		byte[] large = new byte[1024 * 1024 + 1];

		// when & then
		mvc.perform(post("/api/recordings").header(AUTH, user()).contentType(MediaType.APPLICATION_JSON).content(large))
			.andExpect(status().isPayloadTooLarge())
			.andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));

		verifyNoInteractions(recordings);
	}

	@Test
	@DisplayName("목록은 cursor·size·status를 그대로 넘긴다")
	void 목록은_쿼리를_넘긴다() throws Exception {
		// when & then
		mvc.perform(get("/api/recordings").param("cursor", "9").param("size", "2").param("status", "DETECTING")
			.header(AUTH, user()))
			.andExpect(status().isOk());

		verify(recordings).list(USER_ID, "9", 2, "DETECTING");
	}

	@Test
	@DisplayName("상태 조회는 경로의 녹음 ID를 넘기고, 숫자가 아니면 field는 id다")
	void 상태_조회() throws Exception {
		// when & then
		mvc.perform(get("/api/recordings/3").header(AUTH, user())).andExpect(status().isOk());
		mvc.perform(get("/api/recordings/abc").header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("id"));

		verify(recordings).status(USER_ID, 3L);
	}

}
