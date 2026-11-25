package com.lolog.api.domain.comic.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Comic")
class ComicTest {

	private static Comic comic(ComicStatus status) {
		Comic comic = new Comic(1L, 2L, "요약");
		ReflectionTestUtils.setField(comic, "status", status);
		return comic;
	}

	@Test
	@DisplayName("새 만화는 QUEUED로 시작한다")
	void 새_만화는_QUEUED로_시작한다() {
		// when
		Comic comic = new Comic(1L, 2L, "요약");

		// then
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.QUEUED);
		assertThat(comic.isFinished()).isFalse();
	}

	@Test
	@DisplayName("startStoryboard는 QUEUED를 STORYBOARD로 바꾼다")
	void startStoryboard는_QUEUED를_STORYBOARD로_바꾼다() {
		// given
		Comic comic = comic(ComicStatus.QUEUED);

		// when
		comic.startStoryboard();

		// then
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.STORYBOARD);
	}

	@Test
	@DisplayName("콘티를 저장하면 DRAWING이 된다")
	void 콘티를_저장하면_DRAWING이_된다() {
		// given
		Comic comic = comic(ComicStatus.STORYBOARD);

		// when
		comic.saveStoryboard("{\"v\":1}");

		// then
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.DRAWING);
		assertThat(comic.getStoryboard()).isEqualTo("{\"v\":1}");
		assertThat(comic.isFinished()).isFalse();
	}

	@Test
	@DisplayName("finish는 이미지 키를 남기고 DONE이 된다")
	void finish는_이미지_키를_남기고_DONE이_된다() {
		// given
		Comic comic = comic(ComicStatus.DRAWING);

		// when
		comic.finish("comic/1.png");

		// then
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.DONE);
		assertThat(comic.getImageKey()).isEqualTo("comic/1.png");
		assertThat(comic.isFinished()).isTrue();
	}

	@ParameterizedTest
	@DisplayName("fail은 이유를 남기고 FAILED가 된다")
	@EnumSource(value = ComicStatus.class, names = { "QUEUED", "STORYBOARD", "DRAWING" })
	void fail은_이유를_남기고_FAILED가_된다(ComicStatus status) {
		// given
		Comic comic = comic(status);

		// when
		comic.fail("그리기 실패");

		// then
		assertThat(comic.getStatus()).isEqualTo(ComicStatus.FAILED);
		assertThat(comic.getFailReason()).isEqualTo("그리기 실패");
		assertThat(comic.isFinished()).isTrue();
	}

	@ParameterizedTest
	@DisplayName("끝난 상태는 isFinished가 true다")
	@EnumSource(value = ComicStatus.class, names = { "DONE", "FAILED" })
	void 끝난_상태는_isFinished가_true다(ComicStatus status) {
		// when & then
		assertThat(comic(status).isFinished()).isTrue();
	}

	@ParameterizedTest
	@DisplayName("만드는 중인 상태는 isFinished가 false다")
	@EnumSource(value = ComicStatus.class, names = { "QUEUED", "STORYBOARD", "DRAWING" })
	void 만드는_중인_상태는_isFinished가_false다(ComicStatus status) {
		// when & then
		assertThat(comic(status).isFinished()).isFalse();
	}

}
