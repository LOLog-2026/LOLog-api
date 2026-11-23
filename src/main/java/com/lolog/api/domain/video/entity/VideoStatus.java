package com.lolog.api.domain.video.entity;

import java.util.Set;

// 3호가 카드 내용을 받아 가면 VOICE, 대사 음성을 보내면 DRAWING(2호 차례), 영상을 보내면 DONE이다.
public enum VideoStatus {

	QUEUED, VOICE, DRAWING, DONE, FAILED;

	// 만드는 중인 상태. 한 카드에 하나만 있을 수 있다(uk_videos_card_id_in_progress, VIDEO_IN_PROGRESS).
	public static final Set<VideoStatus> IN_PROGRESS = Set.of(QUEUED, VOICE, DRAWING);

	// 3호가 카드 내용으로 목소리를 만드는 동안. 이때는 카드를 고치거나 지울 수 없다(CARD_LOCKED). 그림 단계는 카드를 보지 않는다.
	public static final Set<VideoStatus> BEFORE_DRAWING = Set.of(QUEUED, VOICE);

}
