package com.lolog.api.global.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PageRequestParams")
class PageRequestParamsTest {

	@Test
	@DisplayName("size가 없으면 20이다")
	void size가_없으면_20이다() {
		// when
		PageRequestParams params = PageRequestParams.of(null, null);

		// then
		assertThat(params.size()).isEqualTo(20);
		assertThat(params.before()).isNull();
	}

	@Test
	@DisplayName("size 1과 50은 통과한다")
	void size_1과_50은_통과한다() {
		// when & then
		assertThat(PageRequestParams.of(null, 1).size()).isEqualTo(1);
		assertThat(PageRequestParams.of(null, 50).size()).isEqualTo(50);
	}

	@Test
	@DisplayName("size 0과 51은 400이다")
	void size_0과_51은_400이다() {
		// when & then
		assertInvalid(null, 0, "size");
		assertInvalid(null, 51, "size");
	}

	@Test
	@DisplayName("cursor 18자리는 통과한다")
	void cursor_18자리는_통과한다() {
		// when & then
		assertThat(PageRequestParams.of("999999999999999999", null).before()).isEqualTo(999999999999999999L);
	}

	@Test
	@DisplayName("cursor 19자리는 400이다")
	void cursor_19자리는_400이다() {
		// when & then
		assertInvalid("1000000000000000000", null, "cursor");
	}

	@Test
	@DisplayName("숫자가 아닌 cursor는 400이다")
	void 숫자가_아닌_cursor는_400이다() {
		// when & then
		assertInvalid("", null, "cursor");
		assertInvalid("+1", null, "cursor");
		assertInvalid("-1", null, "cursor");
		assertInvalid(" 1", null, "cursor");
	}

	@Test
	@DisplayName("size가 틀리면 cursor보다 먼저 size로 거절한다")
	void size가_틀리면_cursor보다_먼저_size로_거절한다() {
		// when & then
		assertInvalid("abc", 0, "size");
	}

	private static void assertInvalid(String cursor, Integer size, String field) {
		assertThatThrownBy(() -> PageRequestParams.of(cursor, size))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
				assertThat(e.getField()).isEqualTo(field);
			});
	}

}
