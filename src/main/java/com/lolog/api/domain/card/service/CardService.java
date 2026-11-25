package com.lolog.api.domain.card.service;

import com.lolog.api.domain.audio.service.CardAudioViews;
import com.lolog.api.domain.card.dto.CardDtos.CardDetail;
import com.lolog.api.domain.card.dto.CardDtos.CardPage;
import com.lolog.api.domain.card.dto.CardDtos.CardSummary;
import com.lolog.api.domain.card.dto.CardDtos.CardUpdateRequest;
import com.lolog.api.domain.card.dto.CardDtos.CharacterRef;
import com.lolog.api.domain.card.dto.CardDtos.ComicRef;
import com.lolog.api.domain.card.dto.CardDtos.Line;
import com.lolog.api.domain.card.dto.CardDtos.LineUpdate;
import com.lolog.api.domain.card.dto.CardDtos.Speaker;
import com.lolog.api.domain.card.dto.CardDtos.SpeakerUpdate;
import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.entity.CardLine;
import com.lolog.api.domain.card.entity.CardSpeaker;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.character.service.CharacterViews.Ref;
import com.lolog.api.domain.character.service.CharacterViews.SpeakerCharacter;
import com.lolog.api.domain.character.service.CharacterViews;
import com.lolog.api.domain.comic.entity.ComicStatus;
import com.lolog.api.domain.comic.repository.ComicRepository;
import com.lolog.api.domain.text.service.TextService;
import com.lolog.api.domain.text.service.TextService.TextOrigin;
import com.lolog.api.domain.video.service.VideoService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.StorageDeletions;
import com.lolog.api.global.web.PageRequestParams;
import com.lolog.api.global.web.PageSort;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 카드 목록·상세·고치기·지우기를 맡는다.
@Service
public class CardService {

	static final int MAX_COMICS = 3;
	static final int MAX_NAME = 10;
	static final int MAX_PLACE = 20;
	static final int MAX_TEXT = 200;
	static final int MAX_SPEAKERS = 4;
	static final String SPEAKER_PREFIX = "화자";

	private final CardRepository cards;
	private final ComicRepository comics;
	private final TextService texts;
	private final StorageDeletions deletions;
	private final CharacterViews characters;
	private final CardAudioViews audio;
	private final EntityManager entityManager;
	private final VideoService videos;

	/** 쓰는 저장소와 도구를 받는다. */
	public CardService(CardRepository cards, ComicRepository comics, TextService texts, StorageDeletions deletions,
			CharacterViews characters, CardAudioViews audio, EntityManager entityManager, VideoService videos) {
		this.videos = videos;
		this.entityManager = entityManager;
		this.audio = audio;
		this.cards = cards;
		this.comics = comics;
		this.texts = texts;
		this.deletions = deletions;
		this.characters = characters;
	}

	/**
	 * 내 카드 목록을 최신순 또는 오래된순으로 돌려준다.
	 *
	 * @param userId 요청한 회원 ID
	 * @param cursor 마지막으로 받은 카드 ID(null이면 처음부터)
	 * @param size 페이지 크기(null이면 20, 1~50)
	 * @param sort latest 또는 oldest(null이면 latest)
	 * @return 카드 한 페이지
	 */
	@Transactional(readOnly = true)
	public CardPage list(long userId, String cursor, Integer size, String sort) {
		PageSort order = PageSort.of(sort);
		PageRequestParams params = PageRequestParams.of(cursor, size);
		int limit = params.size();
		List<Card> page = order == PageSort.OLDEST
				? cards.findPageOldest(userId, params.before(), Limit.of(limit + 1))
				: cards.findPage(userId, params.before(), Limit.of(limit + 1));
		boolean more = page.size() > limit;
		List<Card> shown = more ? page.subList(0, limit) : page;
		List<Long> ids = shown.stream().map(Card::getId).toList();
		Map<Long, Long> lineCounts = ids.isEmpty() ? Map.of() : toMap(cards.countLinesByCardIds(ids));
		Map<Long, Long> comicCounts = ids.isEmpty() ? Map.of() : toMap(comics.countNotFailedByCardIds(ids));
		// 들을 수 있는 카드 구간 음성이 있으면 true(replay.md 4장)
		Map<Long, Boolean> hasAudio = audio.ready(ids);
		Map<Long, TextOrigin> origins = texts.origins(shown.stream().map(Card::getTextId).filter(t -> t != null).toList());
		List<CardSummary> items = shown.stream()
			.map(c -> new CardSummary(c.getId(), c.getKind(), c.getSummary(), c.getRecordedAt(), c.getStartMs(),
					c.getEndMs(), c.getPlace(), Math.toIntExact(lineCounts.getOrDefault(c.getId(), 0L)),
					Math.toIntExact(comicCounts.getOrDefault(c.getId(), 0L)), hasAudio.getOrDefault(c.getId(), false),
					source(c, origins), c.getTextId(), c.getCreatedAt()))
			.toList();
		return new CardPage(items, more ? String.valueOf(shown.getLast().getId()) : null);
	}

	/**
	 * 카드 하나의 상세를 돌려준다.
	 *
	 * @param userId 요청한 회원 ID
	 * @param id 내 카드 ID
	 * @return 카드 상세
	 */
	@Transactional(readOnly = true)
	public CardDetail detail(long userId, long id) {
		return toDetail(find(userId, id));
	}

	/**
	 * 바꾼 것만 받는다(api-dto CardUpdateRequest). 모두 검사한 뒤에 바꾸고, 하나라도 틀리면 아무것도 바꾸지 않는다.
	 * 순서: 화자 더하기·이름·그림 → 대사 → 장소 → 화자 빼기와 key 다시 매기기. 요청 안의 key는 모두 빼기 전 기준이다.
	 *
	 * @param userId 요청한 회원 ID
	 * @param id 고칠 내 카드 ID
	 * @param request 바꿀 화자·대사·장소
	 * @return 고친 카드 상세
	 */
	@Transactional
	public CardDetail update(long userId, long id, CardUpdateRequest request) {
		if (request.getSpeakers().isEmpty() && request.getRemoveSpeakers().isEmpty() && request.getLines().isEmpty()
				&& !request.isPlaceSent()) {
			throw ApiException.invalid(null);
		}
		Card card = findLocked(userId, id);
		Map<String, CardSpeaker> speakers = card.getSpeakers().stream()
			.collect(Collectors.toMap(CardSpeaker::getSpeakerKey, Function.identity()));
		Map<Long, CardLine> lines = card.getLines().stream()
			.collect(Collectors.toMap(CardLine::getId, Function.identity()));

		// 카드에 없는 key는 새 화자다. 지금 화자 수 다음 번호부터 빠짐없이여야 한다(2명이면 화자3, 둘을 더하면 화자3·화자4).
		List<String> newKeys = request.getSpeakers().stream()
			.map(SpeakerUpdate::getKey)
			.filter(key -> key != null && !speakers.containsKey(key))
			.distinct()
			.toList();
		Set<String> allowedNew = new HashSet<>();
		for (int n = speakers.size() + 1; n <= speakers.size() + newKeys.size(); n++) {
			allowedNew.add(SPEAKER_PREFIX + n);
		}

		Set<String> seenKeys = new HashSet<>();
		for (int i = 0; i < request.getSpeakers().size(); i++) {
			SpeakerUpdate update = request.getSpeakers().get(i);
			String at = "speakers[" + i + "]";
			if (!seenKeys.add(update.getKey())) {
				throw ApiException.invalid(at + ".key");
			}
			boolean isNew = !speakers.containsKey(update.getKey());
			if (isNew && !allowedNew.contains(update.getKey())) {
				throw unknownSpeaker(at + ".key");
			}
			// 새 화자는 이름·그림 없이 만들어도 된다.
			if (!isNew && !update.isNameSent() && !update.isCharacterSent()) {
				throw ApiException.invalid(at);
			}
			if (update.isNameSent()) {
				checkText(update.getName(), MAX_NAME, at + ".name");
			}
			if (update.isCharacterSent() && update.getCharacter() != null) {
				CharacterRef ref = update.getCharacter();
				if (!CharacterViews.DEFAULT.equals(ref.type()) && !CharacterViews.PROFILE.equals(ref.type())) {
					throw ApiException.invalid(at + ".character.type");
				}
				if (ref.id() == null || ref.id().isBlank()) {
					throw ApiException.invalid(at + ".character.id");
				}
				characters.checkAttachable(userId, new Ref(ref.type(), ref.id()), at + ".character");
			}
		}

		Set<String> removeKeys = new HashSet<>();
		for (int i = 0; i < request.getRemoveSpeakers().size(); i++) {
			String key = request.getRemoveSpeakers().get(i);
			String at = "removeSpeakers[" + i + "]";
			if (!removeKeys.add(key)) {
				throw ApiException.invalid(at);
			}
			if (!speakers.containsKey(key)) {
				throw unknownSpeaker(at);
			}
			if (seenKeys.contains(key)) {
				throw ApiException.invalid(at);
			}
		}

		Set<Long> seenIds = new HashSet<>();
		for (int j = 0; j < request.getLines().size(); j++) {
			LineUpdate update = request.getLines().get(j);
			String at = "lines[" + j + "]";
			if (!seenIds.add(update.id())) {
				throw ApiException.invalid(at + ".id");
			}
			if (!lines.containsKey(update.id())) {
				throw ApiException.invalid(at + ".id");
			}
			// 같은 요청에서 만드는 새 화자의 key도 쓸 수 있다.
			if (update.speaker() != null && !speakers.containsKey(update.speaker())
					&& !newKeys.contains(update.speaker())) {
				throw unknownSpeaker(at + ".speaker");
			}
			if (update.text() != null) {
				checkText(update.text(), MAX_TEXT, at + ".text");
			}
		}
		if (request.isPlaceSent()) {
			checkText(request.getPlace(), MAX_PLACE, "place");
		}

		// 대사를 바꾼 뒤 각 대사의 화자
		Map<Long, String> speakerOfLine = new HashMap<>();
		lines.forEach((lineId, line) -> speakerOfLine.put(lineId, line.getSpeakerKey()));
		request.getLines().stream()
			.filter(u -> u.speaker() != null)
			.forEach(u -> speakerOfLine.put(u.id(), u.speaker()));
		// 새 화자에게는 같은 요청에서 바꾼 대사가 하나 이상 있어야 한다.
		for (int i = 0; i < request.getSpeakers().size(); i++) {
			String key = request.getSpeakers().get(i).getKey();
			if (newKeys.contains(key) && !speakerOfLine.containsValue(key)) {
				throw ApiException.invalid("speakers[" + i + "]");
			}
		}
		int countAfter = speakers.size() + newKeys.size() - removeKeys.size();
		if (countAfter < 1) {
			throw ApiException.invalid("removeSpeakers");
		}
		if (countAfter > MAX_SPEAKERS) {
			throw new ApiException(ErrorCode.SPEAKER_LIMIT);
		}
		// 빼는 화자에게 대사가 남아 있으면 뺄 수 없다(대사 변경을 적용한 뒤 기준).
		for (int i = 0; i < request.getRemoveSpeakers().size(); i++) {
			if (speakerOfLine.containsValue(request.getRemoveSpeakers().get(i))) {
				throw new ApiException(ErrorCode.SPEAKER_HAS_LINES);
			}
		}
		checkNoDuplicateCharacter(card, request.getSpeakers(), removeKeys);

		for (String key : newKeys.stream().sorted(Comparator.comparingInt(CardService::speakerNumber)).toList()) {
			card.addSpeaker(key);
			speakers.put(key, card.getSpeakers().getLast());
		}
		for (SpeakerUpdate update : request.getSpeakers()) {
			CardSpeaker speaker = speakers.get(update.getKey());
			if (update.isNameSent()) {
				speaker.setName(trimOrNull(update.getName()));
			}
			if (update.isCharacterSent()) {
				CharacterRef ref = update.getCharacter();
				speaker.setCharacter(ref == null ? null : ref.type(), ref == null ? null : ref.id());
			}
		}
		for (LineUpdate update : request.getLines()) {
			CardLine line = lines.get(update.id());
			if (update.speaker() != null) {
				line.setSpeakerKey(update.speaker());
			}
			if (update.text() != null) {
				line.setText(update.text().strip());
			}
		}
		if (request.isPlaceSent()) {
			card.setPlace(trimOrNull(request.getPlace()));
		}
		if (!removeKeys.isEmpty()) {
			card.getSpeakers().removeIf(s -> removeKeys.contains(s.getSpeakerKey()));
			// 뺄 화자 줄을 먼저 지운 뒤(flush) 남은 화자와 대사의 key를 바꾼다. 거꾸로 하면 UNIQUE(card_id, speaker_key)에 걸린다.
			entityManager.flush();
			renumberSpeakers(card.getId());
			entityManager.clear();
			card = cards.findById(card.getId()).orElseThrow();
		}
		return toDetail(card);
	}

	/**
	 * 남은 화자의 key를 화자1부터 빠짐없이 다시 매긴다. 이름·그림과 그 화자의 대사가 따라간다.
	 * 작은 번호부터 바꾸면 바꿀 자리는 늘 비어 있다(앞 화자는 이미 더 작은 번호로 옮겼고, 뒤 화자는 더 큰 번호다).
	 *
	 * @param cardId 카드 ID
	 */
	private void renumberSpeakers(long cardId) {
		List<String> remaining = cards.findSpeakerKeys(cardId).stream().sorted(Comparator.comparingInt(CardService::speakerNumber)).toList();
		for (int i = 0; i < remaining.size(); i++) {
			String from = remaining.get(i);
			String to = SPEAKER_PREFIX + (i + 1);
			if (from.equals(to)) {
				continue;
			}
			cards.renameSpeaker(cardId, from, to);
			cards.renameLineSpeaker(cardId, from, to);
		}
	}

	/**
	 * "화자3"에서 번호 3을 꺼낸다.
	 *
	 * @param key 화자 key(화자 + 번호)
	 * @return 화자 번호
	 */
	private static int speakerNumber(String key) {
		return Integer.parseInt(key.substring(SPEAKER_PREFIX.length()));
	}

	/**
	 * 카드를 지워도 그 카드로 만든 만화는 남는다(DB가 comics.card_id를 null로 바꾼다).
	 *
	 * @param userId 요청한 회원 ID
	 * @param id 지울 내 카드 ID
	 */
	@Transactional
	public void delete(long userId, long id) {
		Card card = findLocked(userId, id);
		// 카드 구간 음성도 같이 지운다(audio.md 5장). 행을 잠근 뒤 읽어서, 자르는 중인 쪽과 엇갈리지 않는다.
		String audioKey = card.getAudioKey();
		Long textId = card.getTextId();
		cards.delete(card);
		// 글 카드면 그 글도 지운다(texts.md 2장). 블로그 글 줄(blog_posts)은 같은 글을 다시 가져오지 않게 남는다.
		if (textId != null) {
			entityManager.flush();
			texts.delete(textId);
		}
		if (audioKey != null) {
			deletions.schedule(audioKey);
		}
	}

	/**
	 * 내 카드를 찾는다. 없으면 CARD_NOT_FOUND.
	 *
	 * @param userId 카드 주인 회원 ID
	 * @param id 카드 ID
	 * @return 찾은 카드
	 */
	private Card find(long userId, long id) {
		return cards.findByIdAndUserId(id, userId).orElseThrow(CardService::notFound);
	}

	/**
	 * 만화를 만드는 중이면 고치기·지우기를 막는다(409 CARD_LOCKED).
	 *
	 * @param userId 카드 주인 회원 ID
	 * @param id 카드 ID
	 * @return 잠근 카드
	 */
	private Card findLocked(long userId, long id) {
		Card card = cards.findByIdAndUserIdForUpdate(id, userId).orElseThrow(CardService::notFound);
		if (comics.existsByCardIdAndStatusIn(card.getId(), ComicStatus.IN_PROGRESS)) {
			throw new ApiException(ErrorCode.CARD_LOCKED);
		}
		// 영상은 3호가 카드 내용을 받아 목소리를 만드는 동안(QUEUED·VOICE)만 막는다. 그림 단계는 카드를 보지 않는다.
		if (videos.hasRunningBeforeDrawing(card.getId())) {
			throw new ApiException(ErrorCode.CARD_LOCKED);
		}
		return card;
	}

	/**
	 * 한 카드에서 같은 그림을 두 화자에 붙일 수 없다(한 컷에 같은 얼굴이 둘 나온다). 고친 뒤 모습으로 본다.
	 *
	 * @param card 고치기 전 카드
	 * @param updates 요청의 화자 변경
	 * @param removed 뺄 화자 key
	 */
	private static void checkNoDuplicateCharacter(Card card, List<SpeakerUpdate> updates, Set<String> removed) {
		Map<String, Ref> after = new HashMap<>();
		for (CardSpeaker speaker : card.getSpeakers()) {
			if (speaker.getCharacterType() != null) {
				after.put(speaker.getSpeakerKey(), new Ref(speaker.getCharacterType(), speaker.getCharacterId()));
			}
		}
		for (SpeakerUpdate update : updates) {
			if (update.isCharacterSent()) {
				CharacterRef ref = update.getCharacter();
				if (ref == null) {
					after.remove(update.getKey());
				}
				else {
					after.put(update.getKey(), new Ref(ref.type(), ref.id()));
				}
			}
		}
		removed.forEach(after::remove);
		for (int i = 0; i < updates.size(); i++) {
			Ref ref = after.get(updates.get(i).getKey());
			if (ref != null && updates.get(i).isCharacterSent() && after.values().stream().filter(ref::equals).count() > 1) {
				throw new ApiException(ErrorCode.DUPLICATE_CHARACTER, "speakers[" + i + "].character");
			}
		}
	}

	/**
	 * 카드를 상세 응답으로 바꾼다.
	 *
	 * @param card 응답으로 바꿀 카드
	 * @return 카드 상세
	 */
	private CardDetail toDetail(Card card) {
		List<Ref> refs = card.getSpeakers().stream()
			.filter(s -> s.getCharacterType() != null)
			.map(s -> new Ref(s.getCharacterType(), s.getCharacterId()))
			.toList();
		Map<Ref, SpeakerCharacter> views = refs.isEmpty() ? Map.of() : characters.speakerCharacters(refs);
		List<Speaker> speakers = card.getSpeakers().stream()
			.map(s -> new Speaker(s.getSpeakerKey(), s.getName(),
					s.getCharacterType() == null ? null : views.get(new Ref(s.getCharacterType(), s.getCharacterId()))))
			.toList();
		List<Line> lines = card.getLines().stream()
			.map(l -> new Line(l.getId(), l.getSpeakerKey(), l.getText()))
			.toList();
		List<ComicRef> comicRefs = comics.findByCardIdOrderByIdDesc(card.getId()).stream()
			.map(m -> new ComicRef(m.getId(), m.getStatus()))
			.toList();
		Map<Long, TextOrigin> origins = card.getTextId() == null ? Map.of() : texts.origins(List.of(card.getTextId()));
		TextOrigin origin = card.getTextId() == null ? null : origins.get(card.getTextId());
		return new CardDetail(card.getId(), card.getRecordingId(), card.getKind(), card.getSummary(), card.getPlace(),
				card.getRecordedAt(), card.getStartMs(), card.getEndMs(), speakers, lines, comicRefs,
				MAX_COMICS - card.getComicCount(), videos.refs(card.getId()), videos.remaining(card.getId()),
				audio.detail(card.getId()),
				source(card, origins), card.getTextId(), origin != null && origin.truncated(), card.getCreatedAt());
	}

	/**
	 * 녹음 카드는 녹음을 지워 recording_id가 비어도 RECORDING이다(글 카드는 늘 text_id가 있다).
	 *
	 * @param card 카드
	 * @param origins 글 ID별 출처
	 * @return RECORDING·WRITTEN·BLOG
	 */
	private static String source(Card card, Map<Long, TextOrigin> origins) {
		TextOrigin origin = card.getTextId() == null ? null : origins.get(card.getTextId());
		return origin == null ? "RECORDING" : origin.source();
	}

	/**
	 * 앞뒤 공백을 뺀 뒤 1~max자. null은 "지움"이라 통과한다(대사 text는 null이면 부르지 않는다).
	 *
	 * @param value 검사할 값(null 가능)
	 * @param max 최대 글자 수
	 * @param field 틀렸을 때 알릴 필드 이름
	 */
	private static void checkText(String value, int max, String field) {
		if (value == null) {
			return;
		}
		String trimmed = value.strip();
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > max) {
			throw ApiException.invalid(field);
		}
	}

	/**
	 * null은 그대로, 아니면 앞뒤 공백을 뺀다.
	 *
	 * @param value 다듬을 값(null 가능)
	 * @return 앞뒤 공백을 뺀 값(null이면 null)
	 */
	private static String trimOrNull(String value) {
		return value == null ? null : value.strip();
	}

	/**
	 * [카드 ID, 개수] 줄을 Map으로 바꾼다.
	 *
	 * @param rows [카드 ID, 개수] 줄
	 * @return 카드 ID별 개수
	 */
	private static Map<Long, Long> toMap(List<Object[]> rows) {
		return rows.stream().collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));
	}

	// CARD_NOT_FOUND 예외를 만든다.
	private static ApiException notFound() {
		return new ApiException(ErrorCode.CARD_NOT_FOUND);
	}

	/**
	 * UNKNOWN_SPEAKER 예외를 만든다.
	 *
	 * @param field 틀린 필드 이름
	 * @return UNKNOWN_SPEAKER 예외
	 */
	private static ApiException unknownSpeaker(String field) {
		return new ApiException(ErrorCode.UNKNOWN_SPEAKER, field);
	}

}
