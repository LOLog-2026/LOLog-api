package com.lolog.api.audio;

import com.lolog.api.storage.ImageStorage;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

// 카드의 음성 상태(replay.md 1장, db.md 4장 "카드 음성 상태").
// READY: 카드 구간 음성이 있음. PENDING: 음성 저장을 켠 녹음인데 아직 안 올라옴(녹음이 끝난 지 7일 안).
// NONE: 음성 저장을 끈 녹음, 7일이 지나도록 안 올라옴, 녹음 기록이 지워짐(recording_id가 NULL).
@Component
public class CardAudioViews {

	private final NamedParameterJdbcTemplate jdbc;
	private final ImageStorage storage;

	public CardAudioViews(NamedParameterJdbcTemplate jdbc, ImageStorage storage) {
		this.jdbc = jdbc;
		this.storage = storage;
	}

	// CardDetail.audio. url·durationMs는 READY일 때만 있다. url은 10분 서명 주소(만화 그림과 같은 방식).
	public record CardAudio(String status, String url, Long durationMs) {
	}

	private record State(String status, String key, Long durationMs) {
	}

	// 카드 목록의 hasAudio용. 서명 주소는 만들지 않는다.
	public Map<Long, Boolean> ready(Collection<Long> cardIds) {
		Map<Long, Boolean> result = new HashMap<>();
		states(cardIds).forEach((id, state) -> result.put(id, "READY".equals(state.status())));
		return result;
	}

	public CardAudio detail(long cardId) {
		State state = states(java.util.List.of(cardId)).get(cardId);
		if (state == null || !"READY".equals(state.status())) {
			return new CardAudio(state == null ? "NONE" : state.status(), null, null);
		}
		return new CardAudio("READY", storage.presignedUrl(state.key()).toString(), state.durationMs());
	}

	private Map<Long, State> states(Collection<Long> cardIds) {
		Map<Long, State> result = new HashMap<>();
		if (cardIds.isEmpty()) {
			return result;
		}
		jdbc.query("""
				select c.id, c.audio_key, c.audio_duration_ms,
				       case when c.audio_key is not null then 'READY'
				            when r.audio_saved and r.ended_at >= now() - make_interval(secs => :keep) then 'PENDING'
				            else 'NONE' end as status
				from cards c left join recordings r on r.id = c.recording_id
				where c.id in (:ids)
				""", new MapSqlParameterSource("ids", cardIds).addValue("keep", AudioRetentionJob.KEEP.toSeconds()),
				rs -> {
					result.put(rs.getLong("id"), new State(rs.getString("status"), rs.getString("audio_key"),
							(Long) rs.getObject("audio_duration_ms")));
				});
		return result;
	}

}
