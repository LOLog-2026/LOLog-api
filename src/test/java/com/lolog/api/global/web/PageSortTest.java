package com.lolog.api.global.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PageSort")
class PageSortTest {

	@Test
	@DisplayName("sort가 없으면 최신순이다")
	void sort가_없으면_최신순이다() {
		// when & then
		assertThat(PageSort.of(null)).isEqualTo(PageSort.LATEST);
	}

	@Test
	@DisplayName("latest와 oldest를 읽는다")
	void latest와_oldest를_읽는다() {
		// when & then
		assertThat(PageSort.of("latest")).isEqualTo(PageSort.LATEST);
		assertThat(PageSort.of("oldest")).isEqualTo(PageSort.OLDEST);
	}

	@Test
	@DisplayName("대문자 LATEST는 400이다")
	void 대문자_LATEST는_400이다() {
		// when & then
		assertInvalid("LATEST");
	}

	@Test
	@DisplayName("빈 값은 400이다")
	void 빈_값은_400이다() {
		// when & then
		assertInvalid("");
	}

	private static void assertInvalid(String sort) {
		assertThatThrownBy(() -> PageSort.of(sort))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
				assertThat(e.getField()).isEqualTo("sort");
			});
	}

}
