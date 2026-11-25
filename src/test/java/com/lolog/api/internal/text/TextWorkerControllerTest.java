package com.lolog.api.internal.text;

import static com.lolog.api.support.InternalWebMvcTest.HEADER;
import static com.lolog.api.support.InternalWebMvcTest.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.text.dto.TextDtos.TextResult;
import com.lolog.api.domain.text.service.TextService;
import com.lolog.api.support.InternalWebMvcTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@DisplayName("TextWorkerController")
@InternalWebMvcTest(TextWorkerController.class)
class TextWorkerControllerTest {

	private static final String CARD = """
			{"kind":"황당","summary":"요약","speakers":["화자1"],"lines":[{"lineNo":1,"speaker":"화자1","text":"대사"}]}""";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	TextService texts;

	private ResultActions send(String cards) throws Exception {
		return mvc.perform(post("/internal/texts/4/cards").header(HEADER, TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"jobId\":21,\"cards\":" + cards + "}"));
	}

	@Test
	@DisplayName("카드가 1장이면 204이고 글 ID와 결과를 서비스에 넘긴다")
	void 카드_1장이면_204이다() throws Exception {
		// when & then
		send("[" + CARD + "]").andExpect(status().isNoContent());

		ArgumentCaptor<TextResult> captor = ArgumentCaptor.forClass(TextResult.class);
		verify(texts).receive(eq(4L), captor.capture());
		assertThat(captor.getValue().jobId()).isEqualTo(21L);
		assertThat(captor.getValue().cards()).singleElement().satisfies(card -> {
			assertThat(card.kind()).isEqualTo("황당");
			assertThat(card.startMs()).isNull();
		});
	}

	@Test
	@DisplayName("카드가 0장이면 400, field는 cards다")
	void 카드_0장이면_400이다() throws Exception {
		// when & then
		send("[]").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("cards"));

		verifyNoInteractions(texts);
	}

	@Test
	@DisplayName("카드가 2장이면 400, field는 cards다")
	void 카드_2장이면_400이다() throws Exception {
		// when & then
		send("[" + CARD + "," + CARD + "]").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("cards"));

		verifyNoInteractions(texts);
	}

	@Test
	@DisplayName("카드 안의 제약을 어기면 그 자리의 field 경로로 400이다")
	void 카드_안의_제약을_어기면_400이다() throws Exception {
		// when & then
		send("[" + CARD.replace("\"황당\"", "\"기타\"") + "]").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("cards[0].kind"));

		verifyNoInteractions(texts);
	}

	@Test
	@DisplayName("jobId가 없으면 400, field는 jobId다")
	void jobId가_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(post("/internal/texts/4/cards").header(HEADER, TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"cards\":[" + CARD + "]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("jobId"));

		verifyNoInteractions(texts);
	}

	@Test
	@DisplayName("내부 토큰이 없으면 401이다")
	void 내부_토큰이_없으면_401이다() throws Exception {
		// when & then
		mvc.perform(post("/internal/texts/4/cards").contentType(MediaType.APPLICATION_JSON)
			.content("{\"jobId\":21,\"cards\":[" + CARD + "]}")).andExpect(status().isUnauthorized());

		verifyNoInteractions(texts);
	}

}
