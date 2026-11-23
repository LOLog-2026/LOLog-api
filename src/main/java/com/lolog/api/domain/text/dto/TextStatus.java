package com.lolog.api.domain.text.dto;

// 글 → 카드 상태(texts.md 2장 "상태 채우기"). 칸으로 두지 않고 카드·작업으로 채운다.
public enum TextStatus {
	QUEUED, DONE, FAILED
}
