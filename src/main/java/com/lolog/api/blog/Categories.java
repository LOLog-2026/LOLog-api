package com.lolog.api.blog;

import com.lolog.api.blog.FeedParser.Feed;
import com.lolog.api.blog.FeedParser.Item;
import java.util.ArrayList;
import java.util.List;

// 피드 하나의 카테고리 규칙(texts.md 4장 "플랫폼별 카테고리"). 태그가 카테고리에 섞이지 않게 플랫폼마다 다르게 만든다.
// - selectable: false면 카테고리를 고를 수 없는 플랫폼(Medium·velog)이라 글을 전부 받는다.
// - known: null이 아니면 진짜 카테고리 이름 목록(WordPress REST). 글의 <category> 중 이 이름만 카테고리로 보고, 선택지로도 이것을 보여 준다.
// - hierarchical: 티스토리처럼 하위 카테고리가 "상위/하위"로 오는 곳. "상위"를 고르면 하위 카테고리 글도 맞는다.
public record Categories(boolean selectable, List<String> known, boolean hierarchical) {

	private static final String SEPARATOR = "/";

	// <category>를 그대로 본다(네이버·Other).
	public static Categories plain() {
		return new Categories(true, null, false);
	}

	public static Categories none() {
		return new Categories(false, null, false);
	}

	public static Categories hierarchy() {
		return new Categories(true, null, true);
	}

	public static Categories only(List<String> known) {
		return new Categories(true, List.copyOf(known), false);
	}

	// 이 글의 진짜 카테고리
	public List<String> of(Item item) {
		if (!selectable) {
			return List.of();
		}
		if (known == null) {
			return item.categories();
		}
		return item.categories().stream().filter(known::contains).toList();
	}

	// 선택지: known이 있으면 그것(최근 글에 없는 카테고리까지), 없으면 피드 글에 나온 카테고리. 하위 카테고리면 상위도 넣는다.
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

	// 고른 카테고리와 맞는 이 글의 첫 카테고리. 고른 것이 없으면(전부 받음) 글의 첫 카테고리, 카테고리가 없으면 null.
	// 고른 것이 있는데 맞는 것이 없으면 null이고 이 글은 가져오지 않는다.
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

	private static void addOnce(List<String> list, String value) {
		if (!value.isEmpty() && !list.contains(value)) {
			list.add(value);
		}
	}

}
