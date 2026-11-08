package com.lolog.api.comic;

import java.util.Set;

public enum ComicStatus {

	QUEUED, STORYBOARD, DRAWING, DONE, FAILED;

	// 만드는 중인 상태. 이 상태의 만화가 있으면 카드를 고치거나 지울 수 없다(CARD_LOCKED).
	public static final Set<ComicStatus> IN_PROGRESS = Set.of(QUEUED, STORYBOARD, DRAWING);

}
