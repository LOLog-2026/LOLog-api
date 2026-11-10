package com.lolog.api.common;

// 목록 API의 정렬(쿼리 sort). 안 보내면 최신순이다.
// cursor는 정렬과 상관없이 마지막으로 받은 항목의 ID라서, 다음 페이지도 처음과 같은 sort로 불러야 한다.
public enum PageSort {

	// 최신순(id 내림차순)
	LATEST,
	// 오래된순(id 오름차순)
	OLDEST;

	public static PageSort of(String sort) {
		if (sort == null) {
			return LATEST;
		}
		return switch (sort) {
			case "latest" -> LATEST;
			case "oldest" -> OLDEST;
			default -> throw ApiException.invalid("sort", "sort는 latest, oldest 중 하나입니다.");
		};
	}

}
