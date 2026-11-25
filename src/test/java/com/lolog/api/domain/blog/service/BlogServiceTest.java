package com.lolog.api.domain.blog.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.blog.platform.Categories;
import com.lolog.api.domain.blog.service.FeedParser.Item;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BlogService")
class BlogServiceTest {

	private static final OffsetDateTime BASE = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);

	// ---- added ----

	@Test
	@DisplayName("전과 후가 모두 비면 null이다")
	void 전과_후가_모두_비면_null이다() {
		// when & then
		assertThat(BlogService.added(List.of(), List.of())).isNull();
	}

	@Test
	@DisplayName("카테고리를 모두 빼면 전부 받기라 빈 목록이다")
	void 카테고리를_모두_빼면_전부_받기라_빈_목록이다() {
		// when & then
		assertThat(BlogService.added(List.of("개발", "일상"), List.of())).isNotNull().isEmpty();
	}

	@Test
	@DisplayName("전부 받던 블로그에 카테고리를 고르면 고른 것 전체가 새로 더한 것이다")
	void 전부_받던_블로그에_카테고리를_고르면_고른_것_전체가_새로_더한_것이다() {
		// when & then
		assertThat(BlogService.added(List.of(), List.of("개발", "일상"))).containsExactly("개발", "일상");
	}

	@Test
	@DisplayName("같은 카테고리를 그대로 보내면 null이다")
	void 같은_카테고리를_그대로_보내면_null이다() {
		// when & then
		assertThat(BlogService.added(List.of("개발"), List.of("개발"))).isNull();
	}

	@Test
	@DisplayName("빼기만 하면 null이다")
	void 빼기만_하면_null이다() {
		// when & then
		assertThat(BlogService.added(List.of("개발", "일상"), List.of("개발"))).isNull();
	}

	@Test
	@DisplayName("새로 더한 카테고리만 돌려준다")
	void 새로_더한_카테고리만_돌려준다() {
		// when & then
		assertThat(BlogService.added(List.of("개발"), List.of("일상", "개발", "여행"))).containsExactly("일상", "여행");
	}

	// ---- recent ----

	@Test
	@DisplayName("고른 카테고리가 없으면 전체에서 최근 3개다")
	void 고른_카테고리가_없으면_전체에서_최근_3개다() {
		// given
		List<Item> items = List.of(item("p1", 1), item("p2", 2), item("p3", 3), item("p4", 4), item("p5", 5));

		// when
		Set<Item> recent = BlogService.recent(items, List.of(), Categories.plain());

		// then
		assertThat(recent).containsExactlyInAnyOrder(items.get(2), items.get(3), items.get(4));
	}

	@Test
	@DisplayName("카테고리마다 3개이고 두 카테고리에 든 글은 한 번만 든다")
	void 카테고리마다_3개이고_두_카테고리에_든_글은_한_번만_든다() {
		// given
		Item a1 = item("a1", 1, "A");
		Item a2 = item("a2", 2, "A");
		Item b1 = item("b1", 3, "B");
		Item ab = item("ab", 4, "A", "B");
		Item a3 = item("a3", 5, "A");
		Item b2 = item("b2", 6, "B");
		Item b3 = item("b3", 7, "B");
		List<Item> items = List.of(a1, a2, b1, ab, a3, b2, b3);

		// when
		Set<Item> recent = BlogService.recent(items, List.of("A", "B"), Categories.plain());

		// then
		// A: a3·ab·a2, B: b3·b2·ab
		assertThat(recent).hasSize(5).containsExactlyInAnyOrder(a3, ab, a2, b3, b2);
	}

	@Test
	@DisplayName("고른 카테고리에 글이 3개보다 적으면 있는 만큼만 든다")
	void 고른_카테고리에_글이_3개보다_적으면_있는_만큼만_든다() {
		// given
		Item a1 = item("a1", 1, "A");
		Item b1 = item("b1", 2, "B");

		// when
		Set<Item> recent = BlogService.recent(List.of(a1, b1), List.of("A"), Categories.plain());

		// then
		assertThat(recent).containsExactly(a1);
	}

	// importPosts는 날짜 없는 글을 가장 오래된 쪽(앞)에 둔 뒤 넘긴다.
	@Test
	@DisplayName("날짜 없는 글은 날짜 있는 글이 3개면 빠진다")
	void 날짜_없는_글은_날짜_있는_글이_3개면_빠진다() {
		// given
		Item undated = item("u", null);
		List<Item> items = List.of(undated, item("d1", 1), item("d2", 2), item("d3", 3));

		// when
		Set<Item> recent = BlogService.recent(items, List.of(), Categories.plain());

		// then
		assertThat(recent).hasSize(3).doesNotContain(undated);
	}

	@Test
	@DisplayName("날짜 있는 글이 3개보다 적으면 날짜 없는 글도 든다")
	void 날짜_있는_글이_3개보다_적으면_날짜_없는_글도_든다() {
		// given
		Item undated = item("u", null);
		List<Item> items = List.of(undated, item("d1", 1), item("d2", 2));

		// when
		Set<Item> recent = BlogService.recent(items, List.of(), Categories.plain());

		// then
		assertThat(recent).hasSize(3).contains(undated);
	}

	// ---- inScope ----

	@Test
	@DisplayName("계층 카테고리는 상위를 고르면 하위 글도 범위에 든다")
	void 계층_카테고리는_상위를_고르면_하위_글도_범위에_든다() {
		// given
		Item parent = item("p", 1, "개발");
		Item child = item("c", 2, "개발/자바");
		Item other = item("o", 3, "일상");
		Item similar = item("s", 4, "개발자");

		// when
		Set<Item> scope = BlogService.inScope(List.of(parent, child, other, similar), List.of("개발"),
				Categories.hierarchy());

		// then
		assertThat(scope).containsExactlyInAnyOrder(parent, child);
	}

	@Test
	@DisplayName("계층이 아닌 규칙은 하위 카테고리 글을 넣지 않는다")
	void 계층이_아닌_규칙은_하위_카테고리_글을_넣지_않는다() {
		// given
		Item parent = item("p", 1, "개발");
		Item child = item("c", 2, "개발/자바");

		// when
		Set<Item> scope = BlogService.inScope(List.of(parent, child), List.of("개발"), Categories.plain());

		// then
		assertThat(scope).containsExactly(parent);
	}

	@Test
	@DisplayName("하위를 고르면 상위 글은 범위에 들지 않는다")
	void 하위를_고르면_상위_글은_범위에_들지_않는다() {
		// given
		Item parent = item("p", 1, "개발");
		Item child = item("c", 2, "개발/자바");

		// when
		Set<Item> scope = BlogService.inScope(List.of(parent, child), List.of("개발/자바"), Categories.hierarchy());

		// then
		assertThat(scope).containsExactly(child);
	}

	@Test
	@DisplayName("고른 카테고리가 없으면 카테고리 없는 글까지 모두 범위에 든다")
	void 고른_카테고리가_없으면_카테고리_없는_글까지_모두_범위에_든다() {
		// given
		Item none = item("n", 1);
		Item dev = item("d", 2, "개발");

		// when
		Set<Item> scope = BlogService.inScope(List.of(none, dev), List.of(), Categories.hierarchy());

		// then
		assertThat(scope).containsExactlyInAnyOrder(none, dev);
	}

	// day가 null이면 날짜 없는 글
	private static Item item(String guid, Integer day, String... categories) {
		OffsetDateTime at = day == null ? null : BASE.plusDays(day);
		return new Item(guid, guid, "https://blog.example/" + guid, null, null, at, List.of(categories));
	}

}
