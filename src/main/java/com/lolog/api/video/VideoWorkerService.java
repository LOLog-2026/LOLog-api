package com.lolog.api.video;

import com.lolog.api.card.Card;
import com.lolog.api.card.CardRepository;
import com.lolog.api.character.CharacterViews;
import com.lolog.api.character.CharacterViews.Ref;
import com.lolog.api.comic.ComicWorkerDtos.Line;
import com.lolog.api.common.ApiException;
import com.lolog.api.job.Job;
import com.lolog.api.job.JobRepository;
import com.lolog.api.notification.PushService;
import com.lolog.api.storage.ImageStorage;
import com.lolog.api.video.VideoDtos.SourceCharacter;
import com.lolog.api.video.VideoDtos.Speaker;
import com.lolog.api.video.VideoDtos.VideoSource;
import com.lolog.api.video.VideoService.Video;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

// 워커가 영상을 만드는 단계를 받는다(video.md 1·3장).
// 3호: source(→ VOICE) → storyboard → audio(→ DRAWING, 2호 큐에 넣음). 2호: storyboard·audio 받기 → video(→ DONE, 푸시).
// 영상이 없거나 이미 끝났으면(DONE·FAILED) 받는 쪽(GET)은 404 VIDEO_NOT_FOUND이고, 보내는 쪽(POST)은 204로 받고 무시한다(만화와 같음).
@Service
public class VideoWorkerService {

	static final long MAX_BYTES = 20L * 1024 * 1024;

	private final JdbcTemplate jdbc;
	private final CardRepository cards;
	private final JobRepository jobs;
	private final CharacterViews characters;
	private final ImageStorage storage;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;
	private final VideoPublisher publisher;
	private final PushService push;

	public VideoWorkerService(JdbcTemplate jdbc, CardRepository cards, JobRepository jobs, CharacterViews characters,
			ImageStorage storage, ObjectMapper objectMapper, TransactionTemplate tx, VideoPublisher publisher,
			PushService push) {
		this.jdbc = jdbc;
		this.cards = cards;
		this.jobs = jobs;
		this.characters = characters;
		this.storage = storage;
		this.objectMapper = objectMapper;
		this.tx = tx;
		this.publisher = publisher;
		this.push = push;
	}

	// 카드 내용. 부르면 VOICE가 된다. 카드는 목소리 단계가 끝날 때까지 지울 수 없지만(CARD_LOCKED), 없으면 404다.
	public VideoSource source(long videoId) {
		return tx.execute(status -> {
			Video video = lockRunning(videoId);
			Card card = cards.findById(video.cardId() == null ? -1L : video.cardId()).orElseThrow(VideoService::notFound);
			jdbc.update("update videos set status = 'VOICE', updated_at = current_timestamp where id = ? and status = 'QUEUED'",
					videoId);
			List<Ref> refs = card.getSpeakers()
				.stream()
				.filter(s -> s.getCharacterType() != null)
				.map(s -> new Ref(s.getCharacterType(), s.getCharacterId()))
				.toList();
			Map<Ref, SourceCharacter> found = sourceCharacters(card.getUserId(), refs);
			return new VideoSource(videoId, card.getId(), card.getKind(), card.getSummary(), card.getPlace(),
					card.getSpeakers()
						.stream()
						.map(s -> new Speaker(s.getSpeakerKey(), s.getName(),
								s.getCharacterType() == null ? null : found.get(new Ref(s.getCharacterType(), s.getCharacterId()))))
						.toList(),
					card.getLines().stream().map(l -> new Line(l.getId(), l.getSpeakerKey(), l.getText())).toList());
		});
	}

	// 기본만 본다: JSON 객체, version 1, videoId·cardId가 이 영상과 같음(video.md 5장). 나머지는 워커끼리 정한다.
	public void saveStoryboard(long videoId, JsonNode body) {
		tx.executeWithoutResult(status -> {
			Video video = lock(videoId);
			if (!VideoService.IN_PROGRESS.contains(video.status())) {
				return;
			}
			if (body == null || !body.isObject()) {
				throw ApiException.invalid(null, "콘티는 JSON 객체여야 합니다.");
			}
			if (!body.path("version").isIntegralNumber() || body.path("version").asLong() != 1) {
				throw ApiException.invalid("version", "콘티 version은 1이어야 합니다.");
			}
			if (!body.path("videoId").isIntegralNumber() || body.path("videoId").asLong() != videoId) {
				throw ApiException.invalid("videoId", "주소의 영상과 videoId가 다릅니다.");
			}
			if (!body.path("cardId").isIntegralNumber() || video.cardId() == null
					|| body.path("cardId").asLong() != video.cardId()) {
				throw ApiException.invalid("cardId", "영상의 카드와 cardId가 다릅니다.");
			}
			jdbc.update("update videos set storyboard = ?::jsonb, updated_at = current_timestamp where id = ?",
					objectMapper.writeValueAsString(body), videoId);
		});
	}

	// 3호가 보낸 콘티 그대로. 그림이 붙은 인물(characters[].ref)에는 지금 서명한 imageUrl을 붙인다(10분).
	// 붙어 있던 프로필 캐릭터가 지워졌거나 친구가 끊겼으면 imageUrl 없이 준다(외모 글로만 그림).
	public JsonNode storyboard(long videoId) {
		Video video = running(videoId);
		String json = jdbc.queryForObject("select storyboard::text from videos where id = ?", String.class, videoId);
		if (json == null) {
			throw VideoService.notFound();
		}
		JsonNode board = objectMapper.readTree(json);
		JsonNode cast = board.path("characters");
		if (cast.isArray()) {
			List<Ref> refs = new ArrayList<>();
			for (JsonNode c : cast) {
				Ref ref = parseRef(c.path("ref").asString(null));
				if (ref != null) {
					refs.add(ref);
				}
			}
			Map<Ref, SourceCharacter> found = sourceCharacters(video.userId(), refs);
			for (JsonNode c : cast) {
				Ref ref = parseRef(c.path("ref").asString(null));
				if (c instanceof ObjectNode node) {
					SourceCharacter character = ref == null ? null : found.get(ref);
					if (character != null && character.imageUrl() != null) {
						node.put("imageUrl", character.imageUrl());
					}
					else {
						node.remove("imageUrl");
					}
				}
			}
		}
		return board;
	}

	// 대사 음성(wav). 콘티를 먼저 받아야 한다. 받으면 DRAWING이 되고 2호 큐에 넣는다. 다시 보내면 파일만 덮어쓴다.
	public void saveAudio(long videoId, byte[] wav) {
		Video found = find(videoId);
		if (!VideoService.IN_PROGRESS.contains(found.status())) {
			return;
		}
		checkSize(wav);
		if (wav.length < 12 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F' || wav[8] != 'W'
				|| wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') {
			throw ApiException.invalid("file", "wav 파일이 아닙니다.");
		}
		String key = ImageStorage.videoAudioKey(found.userId(), videoId);
		storage.put(key, wav, "audio/wav");
		Long drawJob = tx.execute(status -> {
			Video video = lock(videoId);
			if (!VideoService.IN_PROGRESS.contains(video.status())) {
				return null;
			}
			Boolean hasBoard = jdbc.queryForObject("select storyboard is not null from videos where id = ?", Boolean.class,
					videoId);
			if (!Boolean.TRUE.equals(hasBoard)) {
				throw ApiException.invalid(null, "콘티를 먼저 보내 주세요.");
			}
			jdbc.update("update videos set audio_key = ?, updated_at = current_timestamp where id = ?", key, videoId);
			if ("DRAWING".equals(video.status())) {
				return null;
			}
			jdbc.update("update videos set status = 'DRAWING', updated_at = current_timestamp where id = ?", videoId);
			completeJob(videoId, "VIDEO_VOICE");
			return jobs.save(Job.video("VIDEO_DRAW", videoId, video.userId())).getId();
		});
		if (drawJob != null) {
			publisher.publishQuietly(drawJob);
		}
	}

	public byte[] audio(long videoId) {
		running(videoId);
		String key = jdbc.queryForObject("select audio_key from videos where id = ?", String.class, videoId);
		if (key == null) {
			throw VideoService.notFound();
		}
		return storage.get(key);
	}

	// 완성 영상(mp4)과 목록용 그림(jpeg). 받으면 DONE, 중간 음성 파일을 지우고 푸시를 보낸다.
	public void saveVideo(long videoId, byte[] mp4, byte[] thumbnail, Long durationMs) {
		Video found = find(videoId);
		if (!VideoService.IN_PROGRESS.contains(found.status())) {
			return;
		}
		checkSize(mp4);
		checkSize(thumbnail);
		if (mp4.length < 12 || mp4[4] != 'f' || mp4[5] != 't' || mp4[6] != 'y' || mp4[7] != 'p') {
			throw ApiException.invalid("file", "mp4 파일이 아닙니다.");
		}
		if (thumbnail.length < 3 || (thumbnail[0] & 0xFF) != 0xFF || (thumbnail[1] & 0xFF) != 0xD8
				|| (thumbnail[2] & 0xFF) != 0xFF) {
			throw ApiException.invalid("thumbnail", "JPEG 파일이 아닙니다.");
		}
		if (durationMs == null || durationMs <= 0) {
			throw ApiException.invalid("durationMs", "durationMs는 0보다 커야 합니다.");
		}
		String videoKey = ImageStorage.videoKey(found.userId(), videoId);
		String thumbKey = ImageStorage.videoThumbKey(found.userId(), videoId);
		storage.put(videoKey, mp4, "video/mp4");
		storage.put(thumbKey, thumbnail, "image/jpeg");
		tx.executeWithoutResult(status -> {
			Video video = lock(videoId);
			if (!VideoService.IN_PROGRESS.contains(video.status())) {
				return;
			}
			jdbc.update("""
					update videos set status = 'DONE', video_key = ?, thumb_key = ?, duration_ms = ?, audio_key = null,
					    updated_at = current_timestamp where id = ?
					""", videoKey, thumbKey, durationMs, videoId);
			completeJob(videoId, "VIDEO_DRAW");
			push.notify(video.userId(), PushService.videoDone(videoId, video.cardId()));
			if (video.audioKey() != null) {
				VideoService.afterCommit(() -> storage.delete(video.audioKey()));
			}
		});
	}

	// ---- 안에서 쓰는 것 ----

	private Map<Ref, SourceCharacter> sourceCharacters(long owner, List<Ref> refs) {
		Map<Ref, SourceCharacter> result = new HashMap<>();
		if (refs.isEmpty()) {
			return result;
		}
		characters.sourceCharacters(owner, refs).forEach((ref, c) -> result.put(ref,
				new SourceCharacter(c.ref(), c.appearance(), c.imageUrl(), voice(owner, ref))));
		return result;
	}

	// 프로필 캐릭터는 고른 목소리(없으면 null), 기본 화자는 목소리 파일이 있으면 그 id다.
	// 내 목소리("self")는 다 만든(READY) 때만 보낸다. 친구가 만드는 쇼츠면 친구 쇼츠 동의(③)를 했을 때만이고,
	// 아니면 null이라 워커가 기본 화자 목소리로 읽는다(characters.md "내 목소리", video.md 6장).
	private String voice(long owner, Ref ref) {
		if (CharacterViews.PROFILE.equals(ref.type())) {
			long userId = Long.parseLong(ref.id());
			return jdbc.query("""
					select character_voice, voice_clone_status, voice_clone_share_friends from user_profiles where user_id = ?
					""", (rs, n) -> {
				String voice = rs.getString("character_voice");
				if (!"self".equals(voice)) {
					return voice;
				}
				boolean ready = "READY".equals(rs.getString("voice_clone_status"));
				boolean allowed = userId == owner || rs.getBoolean("voice_clone_share_friends");
				return ready && allowed ? voice : null;
			}, userId).stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
		}
		Boolean has = jdbc.queryForObject("select exists (select 1 from default_characters where id = ? and voice_key is not null)",
				Boolean.class, ref.id());
		return Boolean.TRUE.equals(has) ? ref.id() : null;
	}

	// "default:young-f-02", "profile:42"
	private static Ref parseRef(String ref) {
		if (ref == null) {
			return null;
		}
		if (ref.startsWith("default:")) {
			return new Ref(CharacterViews.DEFAULT, ref.substring("default:".length()));
		}
		if (ref.startsWith("profile:") && ref.substring("profile:".length()).matches("\\d{1,18}")) {
			return new Ref(CharacterViews.PROFILE, ref.substring("profile:".length()));
		}
		return null;
	}

	private void completeJob(long videoId, String type) {
		jdbc.update("update jobs set status = 'DONE', updated_at = current_timestamp where video_id = ? and job_type = ? "
				+ "and status not in ('DONE', 'FAILED')", videoId, type);
	}

	private Video find(long videoId) {
		return jdbc.query("select * from videos where id = ?", (rs, n) -> Video.of(rs), videoId)
			.stream()
			.findFirst()
			.orElseThrow(VideoService::notFound);
	}

	private Video lock(long videoId) {
		return jdbc.query("select * from videos where id = ? for update", (rs, n) -> Video.of(rs), videoId)
			.stream()
			.findFirst()
			.orElseThrow(VideoService::notFound);
	}

	private Video lockRunning(long videoId) {
		Video video = lock(videoId);
		if (!VideoService.IN_PROGRESS.contains(video.status())) {
			throw VideoService.notFound();
		}
		return video;
	}

	private Video running(long videoId) {
		Video video = find(videoId);
		if (!VideoService.IN_PROGRESS.contains(video.status())) {
			throw VideoService.notFound();
		}
		return video;
	}

	// multipart 한도(파일 20MB)가 먼저 막지만, 설정이 바뀌어도 넘지 않게 여기서도 본다.
	private static void checkSize(byte[] bytes) {
		if (bytes.length > MAX_BYTES) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "파일이 너무 큽니다.");
		}
	}

}
