package com.lolog.api.domain.blog.platform;

import com.lolog.api.domain.blog.service.FeedParser.Feed;
import com.lolog.api.domain.blog.service.FeedParser.Item;
import java.util.ArrayList;
import java.util.List;

/**
 * 피드 하나의 카테고리 규칙(texts.md 4장 "플랫폼별 카테고리"). 태그가 카테고리에 섞이지 않게 플랫폼마다 다르게 만든다.
 * - selectable: false면 카테고리를 고를 수 없는 플랫폼(Medium·velog)이라 글을 전부 받는다.
 * - known: null이 아니면 진짜 카테고리 이름 목록(WordPress REST). 글의 {@code <category>} 중 이 이름만 카테고리로 보고, 선택지로도 이것을 보여 준다.
 * - hierarchical: 티스토리처럼 하위 카테고리가 "상위/하위"로 오는 곳. "상위"를 고르면 하위 카테고리 글도 맞는다.
 *
 * @param selectable 카테고리를 고를 수 있으면 true
 * @param known 진짜 카테고리 이름 목록. 없으면 null
 * @param hierarchical 하위 카테고리가 "상위/하위"로 오면 true
 */
public record Categories(boolean selectable, List<String> known, boolean hierarchical) {

	private static final String SEPARATOR = "/";

	// <category>를 그대로 본다(네이버·Other).
	public static Categories plain() {
		return new Categories(true, null, false);
	}

	// 카테고리를 고를 수 없는 규칙(Medium·velog).
	public static Categories none() {
		return new Categories(false, null, false);
	}

	// "상위/하위" 카테고리 규칙(티스토리).
	public static Categories hierarchy() {
		return new Categories(true, null, true);
	}

	/**
	 * 진짜 카테고리 이름만 보는 규칙(WordPress REST).
	 *
	 * @param known 진짜 카테고리 이름 목록
	 * @return known 이름만 카테고리로 보는 규칙
	 */
	public static Categories only(List<String> known) {
		return new Categories(true, List.copyOf(known), false);
	}

	/**
	 * 이 글의 진짜 카테고리
	 *
	 * @param item 피드의 글 하나
	 * @return 이 규칙으로 본 글의 카테고리. 고를 수 없는 규칙이면 빈 목록
	 */
	public List<String> of(Item item) {
		if (!selectable) {
			return List.of();
		}
		if (known == null) {
			return item.categories();
		}
		return item.categories().stream().filter(known::contains).toList();
	}

	/**
	 * 선택지: known이 있으면 그것(최근 글에 없는 카테고리까지), 없으면 피드 글에 나온 카테고리. 하위 카테고리면 상위도 넣는다.
	 *
	 * @param feed 읽은 피드
	 * @return 고를 수 있는 카테고리 이름. 고를 수 없는 규칙이면 빈 목록
	 */
	public List<String> options(Feed feed) {
		if (!selectable) {
			return List.of();
		}
		if (known != null) {
			return known;
		}
		List<String> result = new ArrayList<>();
		for (Item item : feed.items()) {
			for (String category : item.categories()) {
				if (hierarchical) {
					int at = category.indexOf(SEPARATOR);
					while (at > 0) {
						addOnce(result, category.substring(0, at).strip());
						at = category.indexOf(SEPARATOR, at + 1);
					}
				}
				addOnce(result, category);
			}
		}
		return result;
	}

	/**
	 * 고른 카테고리와 맞는 이 글의 첫 카테고리. 고른 것이 없으면(전부 받음) 글의 첫 카테고리, 카테고리가 없으면 null.
	 * 고른 것이 있는데 맞는 것이 없으면 null이고 이 글은 가져오지 않는다.
	 *
	 * @param item 피드의 글 하나
	 * @param selected 고른 카테고리. 비면 전부 받기
	 * @return 맞은 카테고리. 없으면 null
	 */
	public String matched(Item item, List<String> selected) {
		List<String> mine = of(item);
		if (selected.isEmpty()) {
			return mine.isEmpty() ? null : mine.getFirst();
		}
		for (String category : mine) {
			for (String chosen : selected) {
				if (category.equals(chosen) || hierarchical && category.startsWith(chosen + SEPARATOR)) {
					return category;
				}
			}
		}
		return null;
	}

	/**
	 * 비어 있지 않고 아직 없는 값만 더한다.
	 *
	 * @param list 값을 더할 목록
	 * @param value 더할 카테고리 이름
	 */
	private static void addOnce(List<String> list, String value) {
		if (!value.isEmpty() && !list.contains(value)) {
			list.add(value);
		}
	}

}
