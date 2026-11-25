package com.lolog.api.domain.audio.service;

import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.recording.entity.Recording;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.global.storage.ImageStorage;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// 카드의 음성 상태(replay.md 1장, db.md 4장 "카드 음성 상태").
// READY: 카드 구간 음성이 있음. PENDING: 음성 저장을 켠 녹음인데 아직 안 올라옴(녹음이 끝난 지 7일 안).
// NONE: 음성 저장을 끈 녹음, 7일이 지나도록 안 올라옴, 녹음 기록이 지워짐(recording_id가 NULL).
@Component
public class CardAudioViews {

	private final CardRepository cards;
	private final RecordingRepository recordings;
	private final ImageStorage storage;

	/** 쓰는 저장소와 도구를 받는다. */
	public CardAudioViews(CardRepository cards, RecordingRepository recordings, ImageStorage storage) {
		this.cards = cards;
		this.recordings = recordings;
		this.storage = storage;
	}

	/**
	 * CardDetail.audio. url·durationMs는 READY일 때만 있다. url은 10분 서명 주소(만화 그림과 같은 방식).
	 *
	 * @param status 음성 상태(READY·PENDING·NONE)
	 * @param url 재생할 10분 서명 주소. READY가 아니면 null
	 * @param durationMs 구간 음성 길이(ms). READY가 아니면 null
	 */
	public record CardAudio(String status, String url, Long durationMs) {
	}

	/**
	 * DB에서 읽은 카드 음성 상태.
	 *
	 * @param status 음성 상태(READY·PENDING·NONE)
	 * @param key 카드 구간 음성 키. 없으면 null
	 * @param durationMs 카드 구간 음성 길이(ms). 없으면 null
	 */
	private record State(String status, String key, Long durationMs) {
	}

	/**
	 * 카드 목록의 hasAudio용. 서명 주소는 만들지 않는다.
	 *
	 * @param cardIds 음성 상태를 볼 카드 ID들
	 * @return 카드 ID마다 READY인지. 없는 카드는 빠진다
	 */
	public Map<Long, Boolean> ready(Collection<Long> cardIds) {
		Map<Long, Boolean> result = new HashMap<>();
		states(cardIds).forEach((id, state) -> result.put(id, "READY".equals(state.status())));
		return result;
	}

	/**
	 * 카드 상세에 넣을 음성 상태와 재생 주소를 돌려준다.
	 *
	 * @param cardId 상세를 볼 카드 ID
	 * @return 음성 상태. READY면 재생 주소와 길이도 담는다
	 */
	public CardAudio detail(long cardId) {
		State state = states(List.of(cardId)).get(cardId);
		if (state == null || !"READY".equals(state.status())) {
			return new CardAudio(state == null ? "NONE" : state.status(), null, null);
		}
		return new CardAudio("READY", storage.presignedUrl(state.key()).toString(), state.durationMs());
	}

	/**
	 * 카드마다 음성 상태를 정한다. 카드와 그 녹음을 각각 한 번에 읽는다.
	 *
	 * @param cardIds 상태를 정할 카드 ID들
	 * @return 찾은 카드 ID마다 음성 상태
	 */
	private Map<Long, State> states(Collection<Long> cardIds) {
		Map<Long, State> result = new HashMap<>();
		if (cardIds.isEmpty()) {
			return result;
		}
		List<Card> found = cards.findAllById(cardIds);
		List<Long> recordingIds = found.stream().map(Card::getRecordingId).filter(Objects::nonNull).distinct().toList();
		Map<Long, Recording> byId = recordings.findAllById(recordingIds).stream()
			.collect(Collectors.toMap(Recording::getId, Function.identity()));
		OffsetDateTime keepFrom = OffsetDateTime.now().minus(AudioRetentionJob.KEEP);
		for (Card card : found) {
			Recording recording = card.getRecordingId() == null ? null : byId.get(card.getRecordingId());
			String status;
			if (card.getAudioKey() != null) {
				status = "READY";
			}
			else if (recording != null && recording.isAudioSaved() && recording.getEndedAt() != null
					&& !recording.getEndedAt().isBefore(keepFrom)) {
				status = "PENDING";
			}
			else {
				status = "NONE";
			}
			result.put(card.getId(), new State(status, card.getAudioKey(), card.getAudioDurationMs()));
		}
		return result;
	}

}
