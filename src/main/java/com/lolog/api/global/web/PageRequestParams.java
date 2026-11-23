package com.lolog.api.global.web;

import com.lolog.api.global.error.ApiException;

// 목록 API 공통 쿼리(API DTO 명세 PageResponse): cursor는 마지막으로 받은 항목의 ID, size는 기본 20, 1~50.
// 앱은 cursor의 뜻을 해석하지 않고 그대로 돌려보낸다.
public record PageRequestParams(Long before, int size) {

	public static final int DEFAULT_SIZE = 20;
	public static final int MAX_SIZE = 50;

	// cursor와 size를 검사해 목록 조회 조건으로 바꾼다.
	public static PageRequestParams of(String cursor, Integer size) {
		int limit = size == null ? DEFAULT_SIZE : size;
		if (limit < 1 || limit > MAX_SIZE) {
			throw ApiException.invalid("size");
		}
		if (cursor == null) {
			return new PageRequestParams(null, limit);
		}
		if (!cursor.matches("\\d{1,18}")) {
			throw ApiException.invalid("cursor");
		}
		return new PageRequestParams(Long.parseLong(cursor), limit);
	}

}
