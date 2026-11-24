package com.lolog.api.internal.video;

import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.character.service.CharacterViews.Ref;
import com.lolog.api.domain.character.service.CharacterViews;
import com.lolog.api.domain.character.service.DefaultCharacterService;
import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.domain.video.entity.Video;
import com.lolog.api.domain.video.repository.VideoRepository;
import com.lolog.api.domain.video.service.VideoPublisher;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.storage.StorageDeletions;
import com.lolog.api.internal.comic.ComicWorkerDtos.Line;
import com.lolog.api.internal.video.VideoWorkerDtos.SourceCharacter;
import com.lolog.api.internal.video.VideoWorkerDtos.Speaker;
import com.lolog.api.internal.video.VideoWorkerDtos.VideoSource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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

	private final UserProfileRepository profiles;
	private final VideoRepository videos;
	private final CardRepository cards;
	private final JobRepository jobs;
	private final CharacterViews characters;
	private final ImageStorage storage;
	private final StorageDeletions deletions;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;
	private final VideoPublisher publisher;
	private final PushService push;
	private final DefaultCharacterService defaultCharacters;

	public VideoWorkerService(UserProfileRepository profiles, VideoRepository videos, CardRepository cards, JobRepository jobs,
			CharacterViews characters, ImageStorage storage, StorageDeletions deletions, ObjectMapper objectMapper, TransactionTemplate tx,
			VideoPublisher publisher, PushService push, DefaultCharacterService defaultCharacters) {
		this.profiles = profiles;
		this.videos = videos;
		this.cards = cards;
		this.jobs = jobs;
		this.characters = characters;
		this.storage = storage;
		this.deletions = deletions;
		this.objectMapper = objectMapper;
		this.tx = tx;
		this.publisher = publisher;
		this.push = push;
		this.defaultCharacters = defaultCharacters;
	}

	// 카드 내용. 부르면 VOICE가 된다. 카드는 목소리 단계가 끝날 때까지 지울 수 없지만(CARD_LOCKED), 없으면 404다.
	public VideoSource source(long videoId) {
		return tx.execute(status -> {
			Video video = lockRunning(videoId);
			Card card = cards.findById(video.getCardId() == null ? -1L : video.getCardId())
				.orElseThrow(VideoWorkerService::notFound);
			video.startVoice();
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
			if (!video.isInProgress()) {
				return;
			}
			if (body == null || !body.isObject()) {
				throw ApiException.invalid(null);
			}
			if (!body.path("version").isIntegralNumber() || body.path("version").asLong() != 1) {
				throw ApiException.invalid("version");
			}
			if (!body.path("videoId").isIntegralNumber() || body.path("videoId").asLong() != videoId) {
				throw ApiException.invalid("videoId");
			}
			if (!body.path("cardId").isIntegralNumber() || video.getCardId() == null
					|| body.path("cardId").asLong() != video.getCardId()) {
				throw ApiException.invalid("cardId");
			}
			video.saveStoryboard(objectMapper.writeValueAsString(body));
		});
	}

	// 3호가 보낸 콘티 그대로. 그림이 붙은 인물(characters[].ref)에는 지금 서명한 imageUrl을 붙인다(10분).
	// 붙어 있던 프로필 캐릭터가 지워졌거나 친구가 끊겼으면 imageUrl 없이 준다(외모 글로만 그림).
	public JsonNode storyboard(long videoId) {
		Video video = running(videoId);
		if (!video.hasStoryboard()) {
			throw notFound();
		}
		JsonNode board = objectMapper.readTree(video.getStoryboard());
		JsonNode cast = board.path("characters");
		if (cast.isArray()) {
			List<Ref> refs = new ArrayList<>();
			for (JsonNode c : cast) {
				Ref ref = parseRef(c.path("ref").asString(null));
				if (ref != null) {
					refs.add(ref);
				}
			}
			Map<Ref, SourceCharacter> found = sourceCharacters(video.getUserId(), refs);
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
		if (!found.isInProgress()) {
			return;
		}
		checkSize(wav);
		if (wav.length < 12 || wav[0] != 'R' || wav[1] != 'I' || wav[2] != 'F' || wav[3] != 'F' || wav[8] != 'W'
				|| wav[9] != 'A' || wav[10] != 'V' || wav[11] != 'E') {
			throw ApiException.invalid("file");
		}
		String key = ImageStorage.videoAudioKey(found.getUserId(), videoId);
		storage.put(key, wav, "audio/wav");
		Long drawJob = tx.execute(status -> {
			Video video = lock(videoId);
			if (!video.isInProgress()) {
				return null;
			}
			if (!video.hasStoryboard()) {
				throw ApiException.invalid(null);
			}
			if (!video.attachAudio(key)) {
				return null;
			}
			completeJob(videoId, "VIDEO_VOICE");
			return jobs.save(Job.video("VIDEO_DRAW", videoId, video.getUserId())).getId();
		});
		if (drawJob != null) {
			publisher.publishQuietly(drawJob);
		}
	}

	// 저장한 대사 음성을 꺼낸다. 아직 없으면 404다.
	public byte[] audio(long videoId) {
		String key = running(videoId).getAudioKey();
		if (key == null) {
			throw notFound();
		}
		return storage.get(key);
	}

	// 완성 영상(mp4)과 목록용 그림(jpeg). 받으면 DONE, 중간 음성 파일을 지우고 푸시를 보낸다.
	public void saveVideo(long videoId, byte[] mp4, byte[] thumbnail, Long durationMs) {
		Video found = find(videoId);
		if (!found.isInProgress()) {
			return;
		}
		checkSize(mp4);
		checkSize(thumbnail);
		if (mp4.length < 12 || mp4[4] != 'f' || mp4[5] != 't' || mp4[6] != 'y' || mp4[7] != 'p') {
			throw ApiException.invalid("file");
		}
		if (thumbnail.length < 3 || (thumbnail[0] & 0xFF) != 0xFF || (thumbnail[1] & 0xFF) != 0xD8
				|| (thumbnail[2] & 0xFF) != 0xFF) {
			throw ApiException.invalid("thumbnail");
		}
		if (durationMs == null || durationMs <= 0) {
			throw ApiException.invalid("durationMs");
		}
		String videoKey = ImageStorage.videoKey(found.getUserId(), videoId);
		String thumbKey = ImageStorage.videoThumbKey(found.getUserId(), videoId);
		storage.put(videoKey, mp4, "video/mp4");
		storage.put(thumbKey, thumbnail, "image/jpeg");
		tx.executeWithoutResult(status -> {
			Video video = lock(videoId);
			if (!video.isInProgress()) {
				return;
			}
			String audioKey = video.getAudioKey();
			video.finish(videoKey, thumbKey, durationMs);
			completeJob(videoId, "VIDEO_DRAW");
			push.notify(video.getUserId(), PushService.videoDone(videoId, video.getCardId()));
			if (audioKey != null) {
				deletions.schedule(audioKey);
			}
		});
	}

	// ---- 안에서 쓰는 것 ----

	// 화자마다 그림과 목소리를 모은다.
	private Map<Ref, SourceCharacter> sourceCharacters(long owner, List<Ref> refs) {
		Map<Ref, SourceCharacter> result = new HashMap<>();
		if (refs.isEmpty()) {
			return result;
		}
		characters.sourceCharacters(owner, refs).forEach((ref, c) -> {
			Voice voice = voice(owner, ref);
			result.put(ref, new SourceCharacter(c.ref(), c.appearance(), c.imageUrl(), voice.voice(), voice.voiceText()));
		});
		return result;
	}

	// 프로필 캐릭터는 고른 목소리(없으면 null), 기본 화자는 목소리 파일이 있으면 그 id다.
	// 내 목소리("self")는 다 만든(READY) 때만 보낸다. 친구가 만드는 쇼츠면 친구 쇼츠 동의(③)를 했을 때만이고,
	// 아니면 null이라 워커가 기본 화자 목소리로 읽는다(characters.md "내 목소리", video.md 6장).
	// voiceText는 voice가 "self"일 때 읽은 문장(워커의 ref_text), 아니면 null
	private record Voice(String voice, String voiceText) {

		static final Voice NONE = new Voice(null, null);

	}

	// 화자에 붙일 목소리를 찾는다.
	private Voice voice(long owner, Ref ref) {
		if (CharacterViews.PROFILE.equals(ref.type())) {
			long userId = Long.parseLong(ref.id());
			return profiles.findById(userId).map(p -> {
				String voice = p.getCharacterVoice();
				if (!UserProfile.SELF_VOICE.equals(voice)) {
					return new Voice(voice, null);
				}
				boolean ready = UserProfile.CHARACTER_READY.equals(p.getVoiceCloneStatus());
				boolean allowed = userId == owner || p.isVoiceCloneShareFriends();
				return ready && allowed ? new Voice(voice, p.getVoiceCloneText()) : Voice.NONE;
			}).orElse(Voice.NONE);
		}
		return defaultCharacters.hasVoice(ref.id()) ? new Voice(ref.id(), null) : Voice.NONE;
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

	// 영상의 그 단계 작업을 DONE으로 바꾼다.
	private void completeJob(long videoId, String type) {
		jobs.completeVideoStep(videoId, type);
	}

	// 영상을 찾는다. 없으면 404다.
	private Video find(long videoId) {
		return videos.findById(videoId).orElseThrow(VideoWorkerService::notFound);
	}

	// 영상을 잠그고 찾는다.
	private Video lock(long videoId) {
		return videos.findByIdForUpdate(videoId).orElseThrow(VideoWorkerService::notFound);
	}

	// 만드는 중인 영상만 잠그고 찾는다.
	private Video lockRunning(long videoId) {
		return videos.findByIdForUpdate(videoId).filter(Video::isInProgress).orElseThrow(VideoWorkerService::notFound);
	}

	// 만드는 중인 영상만 찾는다.
	private Video running(long videoId) {
		return videos.findById(videoId).filter(Video::isInProgress).orElseThrow(VideoWorkerService::notFound);
	}

	// 영상이 없거나 이미 끝났을 때(DONE·FAILED). 앱 쪽 404와 같은 코드·문구다.
	private static ApiException notFound() {
		return new ApiException(ErrorCode.VIDEO_NOT_FOUND);
	}

	// multipart 한도(파일 20MB)가 먼저 막지만, 설정이 바뀌어도 넘지 않게 여기서도 본다.
	private static void checkSize(byte[] bytes) {
		if (bytes.length > MAX_BYTES) {
			throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
		}
	}

}
