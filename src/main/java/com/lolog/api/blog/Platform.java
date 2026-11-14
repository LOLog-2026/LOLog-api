package com.lolog.api.blog;

// 블로그 플랫폼(texts.md 4장 "플랫폼별 구현"). blog_sources.platform에 이 이름으로 저장하고, 서버 안에서만 쓴다.
// OTHER는 따로 맞추지 않은 블로그라 앱 응답의 optimized가 false다.
public enum Platform {

	NAVER, TISTORY, VELOG, MEDIUM, WORDPRESS, OTHER;

	public boolean optimized() {
		return this != OTHER;
	}

	// Medium·velog에는 카테고리가 없어 고를 수 없고 글을 전부 받는다(정함 11-14).
	public boolean categorySelectable() {
		return this != MEDIUM && this != VELOG;
	}

}
