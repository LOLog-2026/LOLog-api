package com.lolog.api.internal.comic;

import static com.lolog.api.support.InternalWebMvcTest.HEADER;
import static com.lolog.api.support.InternalWebMvcTest.TOKEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.character.service.CharacterViews.SourceCharacter;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.internal.comic.ComicWorkerDtos.ComicSource;
import com.lolog.api.internal.comic.ComicWorkerDtos.Line;
import com.lolog.api.internal.comic.ComicWorkerDtos.Speaker;
import com.lolog.api.support.InternalWebMvcTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

@DisplayName("ComicWorkerController")
@InternalWebMvcTest(ComicWorkerController.class)
class ComicWorkerControllerTest {

	@Autowired
	MockMvc mvc;

	@MockitoBean
	ComicWorkerService service;

	@Test
	@DisplayName("카드 내용을 ComicSource 모양 그대로 JSON으로 돌려준다")
	void 카드_내용을_돌려준다() throws Exception {
		// given
		when(service.source(8L)).thenReturn(new ComicSource(8L, 2L, "웃김", "요약", "카페",
				List.of(new Speaker("화자1", "민수", new SourceCharacter("default:1", "안경", "http://img/1.png"))),
				List.of(new Line(31L, "화자1", "대사"))));

		// when & then
		mvc.perform(get("/internal/comics/8/source").header(HEADER, TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.comicId").value(8))
			.andExpect(jsonPath("$.cardId").value(2))
			.andExpect(jsonPath("$.place").value("카페"))
			.andExpect(jsonPath("$.speakers[0].key").value("화자1"))
			.andExpect(jsonPath("$.speakers[0].character.ref").value("default:1"))
			.andExpect(jsonPath("$.lines[0].id").value(31))
			.andExpect(jsonPath("$.lines[0].text").value("대사"));
	}

	@Test
	@DisplayName("만화가 없으면 서비스의 404 COMIC_NOT_FOUND를 그대로 답한다")
	void 만화가_없으면_404다() throws Exception {
		// given
		when(service.source(8L)).thenThrow(new ApiException(ErrorCode.COMIC_NOT_FOUND));

		// when & then
		mvc.perform(get("/internal/comics/8/source").header(HEADER, TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("COMIC_NOT_FOUND"));
	}

	@Test
	@DisplayName("스토리보드 JSON을 받은 그대로 서비스에 넘기고 204로 답한다")
	void 스토리보드를_넘긴다() throws Exception {
		// when & then
		mvc.perform(post("/internal/comics/8/storyboard").header(HEADER, TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"panels\":[{\"no\":1}]}")).andExpect(status().isNoContent());

		ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);
		verify(service).storyboard(eq(8L), captor.capture());
		assertThat(captor.getValue().get("panels").get(0).get("no").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("스토리보드 본문이 없으면 400이다")
	void 스토리보드_본문이_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(post("/internal/comics/8/storyboard").header(HEADER, TOKEN).contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("그림 파일 파트의 바이트를 서비스에 넘기고 204로 답한다")
	void 그림_바이트를_넘긴다() throws Exception {
		// given
		byte[] png = { 1, 2, 3 };

		// when & then
		mvc.perform(multipart("/internal/comics/8/image").file(new MockMultipartFile("file", "a.png", "image/png", png))
			.header(HEADER, TOKEN)).andExpect(status().isNoContent());

		verify(service).image(8L, png);
	}

	@Test
	@DisplayName("file 파트가 없으면 400, field는 file이다")
	void file_파트가_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(multipart("/internal/comics/8/image").file(new MockMultipartFile("other", new byte[] { 1 }))
			.header(HEADER, TOKEN))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("file"));

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("그림을 multipart가 아닌 본문으로 보내면 415다")
	void multipart가_아니면_415다() throws Exception {
		// when & then
		mvc.perform(post("/internal/comics/8/image").header(HEADER, TOKEN)
			.contentType(MediaType.IMAGE_PNG)
			.content(new byte[] { 1 })).andExpect(status().isUnsupportedMediaType());

		verifyNoInteractions(service);
	}

	@Test
	@DisplayName("내부 토큰이 없으면 401이다")
	void 내부_토큰이_없으면_401이다() throws Exception {
		// when & then
		mvc.perform(get("/internal/comics/8/source")).andExpect(status().isUnauthorized());

		verifyNoInteractions(service);
	}

}
