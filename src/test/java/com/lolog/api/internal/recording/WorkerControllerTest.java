package com.lolog.api.internal.recording;

import static com.lolog.api.support.InternalWebMvcTest.HEADER;
import static com.lolog.api.support.InternalWebMvcTest.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.global.auth.TestTokens;
import com.lolog.api.internal.recording.WorkerDtos.DetectResult;
import com.lolog.api.internal.recording.WorkerDtos.DetectedCard;
import com.lolog.api.support.InternalWebMvcTest;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("WorkerController")
@InternalWebMvcTest(WorkerController.class)
class WorkerControllerTest {

	private static final ObjectMapper JSON = JsonMapper.builder().build();

	// 제약을 모두 지킨 감지 결과. 경우마다 한 군데만 바꿔 보낸다.
	private static final String VALID = """
			{"jobId":11,"partNo":1,"carryFromMs":0,"cards":[
			  {"kind":"웃김","summary":"요약","startMs":0,"endMs":1000,"speakers":["화자1"],
			   "lines":[{"lineNo":1,"speaker":"화자1","text":"대사","startMs":0,"endMs":500}]}
			]}""";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	DetectResultService detectResults;

	private static ObjectNode valid() {
		return (ObjectNode) JSON.readTree(VALID);
	}

	private static ObjectNode card(ObjectNode body) {
		return (ObjectNode) body.get("cards").get(0);
	}

	private static ObjectNode line(ObjectNode body) {
		return (ObjectNode) card(body).get("lines").get(0);
	}

	private ResultActions send(String path, String body) throws Exception {
		return mvc.perform(post(path).header(HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static Arguments invalid(String label, String field, Consumer<ObjectNode> change) {
		return Arguments.of(Named.of(label, change), field);
	}

	static Stream<Arguments> 제약_위반() {
		return Stream.of(
				invalid("jobId 없음", "jobId", b -> b.remove("jobId")),
				invalid("partNo 없음", "partNo", b -> b.remove("partNo")),
				invalid("partNo 0", "partNo", b -> b.put("partNo", 0)),
				invalid("carryFromMs 음수", "carryFromMs", b -> b.put("carryFromMs", -1)),
				invalid("cards 없음", "cards", b -> b.remove("cards")),
				invalid("cards 안에 null", "cards[0]", b -> b.putArray("cards").addNull()),
				invalid("kind 없음", "cards[0].kind", b -> card(b).remove("kind")),
				invalid("kind가 정한 값 밖", "cards[0].kind", b -> card(b).put("kind", "슬픔")),
				invalid("summary 공백", "cards[0].summary", b -> card(b).put("summary", " ")),
				invalid("summary 31자", "cards[0].summary", b -> card(b).put("summary", "가".repeat(31))),
				invalid("카드 startMs 없음", "cards[0].startMs", b -> card(b).remove("startMs")),
				invalid("카드 startMs 음수", "cards[0].startMs", b -> card(b).put("startMs", -1)),
				invalid("카드 endMs 없음", "cards[0].endMs", b -> card(b).remove("endMs")),
				invalid("speakers 없음", "cards[0].speakers", b -> card(b).remove("speakers")),
				invalid("speakers 0명", "cards[0].speakers", b -> card(b).putArray("speakers")),
				invalid("speakers 5명", "cards[0].speakers",
						b -> card(b).putArray("speakers").add("a").add("b").add("c").add("d").add("e")),
				invalid("speakers 안에 null", "cards[0].speakers[0]", b -> card(b).putArray("speakers").addNull()),
				invalid("lines 없음", "cards[0].lines", b -> card(b).remove("lines")),
				invalid("lines 0줄", "cards[0].lines", b -> card(b).putArray("lines")),
				invalid("lines 안에 null", "cards[0].lines[0]", b -> card(b).putArray("lines").addNull()),
				invalid("lineNo 없음", "cards[0].lines[0].lineNo", b -> line(b).remove("lineNo")),
				invalid("lineNo 0", "cards[0].lines[0].lineNo", b -> line(b).put("lineNo", 0)),
				invalid("대사 speaker 없음", "cards[0].lines[0].speaker", b -> line(b).remove("speaker")),
				invalid("대사 text 공백", "cards[0].lines[0].text", b -> line(b).put("text", "")),
				invalid("대사 startMs 음수", "cards[0].lines[0].startMs", b -> line(b).put("startMs", -1)),
				invalid("대사 endMs 음수", "cards[0].lines[0].endMs", b -> line(b).put("endMs", -1)));
	}

	@ParameterizedTest(name = "{0}이면 400, field는 {1}이다")
	@MethodSource("제약_위반")
	@DisplayName("감지 결과가 제약을 어기면 400 INVALID_REQUEST와 어긴 자리의 field 경로로 답한다")
	void 제약을_어기면_field_경로로_400이다(Consumer<ObjectNode> change, String field) throws Exception {
		// given
		ObjectNode body = valid();
		change.accept(body);

		// when & then
		send("/internal/recordings/5/cards", body.toString()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value(field));

		verifyNoInteractions(detectResults);
	}

	@Test
	@DisplayName("제약을 모두 지키면 204이고 녹음 ID와 본문을 서비스에 넘긴다")
	void 올바른_감지_결과는_204이다() throws Exception {
		// when & then
		send("/internal/recordings/5/cards", VALID).andExpect(status().isNoContent());

		ArgumentCaptor<DetectResult> captor = ArgumentCaptor.forClass(DetectResult.class);
		verify(detectResults).receive(eq(5L), captor.capture());
		DetectResult result = captor.getValue();
		assertThat(result.jobId()).isEqualTo(11L);
		assertThat(result.partNo()).isEqualTo(1);
		assertThat(result.carryFromMs()).isZero();
		DetectedCard card = result.cards().get(0);
		assertThat(card.kind()).isEqualTo("웃김");
		assertThat(card.speakers()).containsExactly("화자1");
		assertThat(card.lines()).singleElement().satisfies(line -> {
			assertThat(line.lineNo()).isEqualTo(1);
			assertThat(line.text()).isEqualTo("대사");
		});
	}

	@Test
	@DisplayName("cards가 빈 배열이어도 받는다(웃음이 없던 조각)")
	void 카드가_없는_조각도_받는다() throws Exception {
		// given
		ObjectNode body = valid();
		body.putArray("cards");

		// when & then
		send("/internal/recordings/5/cards", body.toString()).andExpect(status().isNoContent());

		verify(detectResults).receive(eq(5L), argThat(r -> r.cards().isEmpty()));
	}

	@Test
	@DisplayName("carryFromMs와 대사 시각은 없어도 받는다")
	void 선택_값은_없어도_받는다() throws Exception {
		// given
		ObjectNode body = valid();
		body.remove("carryFromMs");
		line(body).remove("startMs");
		line(body).remove("endMs");

		// when & then
		send("/internal/recordings/5/cards", body.toString()).andExpect(status().isNoContent());

		verify(detectResults).receive(eq(5L), argThat(r -> r.carryFromMs() == null));
	}

	@Test
	@DisplayName("숫자 자리에 글자가 오면 400이고 field는 그 경로다")
	void 숫자_자리에_글자가_오면_400이다() throws Exception {
		// given
		ObjectNode body = valid();
		card(body).put("startMs", "abc");

		// when & then
		send("/internal/recordings/5/cards", body.toString()).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("cards[0].startMs"));

		verifyNoInteractions(detectResults);
	}

	@Test
	@DisplayName("녹음 ID가 숫자가 아니면 400, field는 id다")
	void 녹음_ID가_숫자가_아니면_400이다() throws Exception {
		// when & then
		send("/internal/recordings/abc/cards", VALID).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("id"));

		verifyNoInteractions(detectResults);
	}

	@Test
	@DisplayName("내부 토큰이 틀리면 401이고 서비스를 부르지 않는다")
	void 내부_토큰이_틀리면_401이다() throws Exception {
		// when & then
		mvc.perform(post("/internal/recordings/5/cards").header(HEADER, "wrong")
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

		verifyNoInteractions(detectResults);
	}

	@Test
	@DisplayName("액세스 토큰(Bearer)만 있고 내부 토큰이 없으면 401이다")
	void 액세스_토큰만으로는_401이다() throws Exception {
		// when & then
		mvc.perform(post("/internal/recordings/5/cards")
			.header("Authorization", TestTokens.bearer(1))
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID)).andExpect(status().isUnauthorized());

		verifyNoInteractions(detectResults);
	}

}
