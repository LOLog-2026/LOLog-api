package com.lolog.api.card;

import com.lolog.api.audio.CardAudioViews;
import com.lolog.api.card.CardDtos.CardDetail;
import com.lolog.api.card.CardDtos.CharacterRef;
import com.lolog.api.character.CharacterViews;
import com.lolog.api.character.CharacterViews.Ref;
import com.lolog.api.character.CharacterViews.SpeakerCharacter;
import jakarta.persistence.EntityManager;
import java.util.Comparator;
import java.util.HashMap;
import com.lolog.api.card.CardDtos.CardPage;
import com.lolog.api.card.CardDtos.CardSummary;
import com.lolog.api.card.CardDtos.CardUpdateRequest;
import com.lolog.api.card.CardDtos.ComicRef;
import com.lolog.api.card.CardDtos.Line;
import com.lolog.api.card.CardDtos.LineUpdate;
import com.lolog.api.card.CardDtos.Speaker;
import com.lolog.api.card.CardDtos.SpeakerUpdate;
import com.lolog.api.comic.ComicRepository;
import com.lolog.api.comic.ComicStatus;
import com.lolog.api.common.ApiException;
import com.lolog.api.common.PageRequestParams;
import com.lolog.api.common.PageSort;
import com.lolog.api.common.SafeLog;
import com.lolog.api.storage.ImageStorage;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class CardService {

	static final int MAX_COMICS = 3;
	static final int MAX_NAME = 10;
	static final int MAX_PLACE = 20;
	static final int MAX_TEXT = 200;
	static final int MAX_SPEAKERS = 4;
	static final String SPEAKER_PREFIX = "화자";

	private static final Logger log = LoggerFactory.getLogger(CardService.class);

	private final CardRepository cards;
	private final ComicRepository comics;
	private final JdbcTemplate jdbc;
	private final ImageStorage storage;
	private final CharacterViews characters;
	private final CardAudioViews audio;
	private final EntityManager entityManager;

	public CardService(CardRepository cards, ComicRepository comics, JdbcTemplate jdbc, ImageStorage storage,
			CharacterViews characters, CardAudioViews audio, EntityManager entityManager) {
		this.entityManager = entityManager;
		this.audio = audio;
		this.cards = cards;
		this.comics = comics;
		this.jdbc = jdbc;
		this.storage = storage;
		this.characters = characters;
	}

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
		List<CardSummary> items = shown.stream()
			.map(c -> new CardSummary(c.getId(), c.getKind(), c.getSummary(), c.getRecordedAt(), c.getStartMs(),
					c.getEndMs(), c.getPlace(), Math.toIntExact(lineCounts.getOrDefault(c.getId(), 0L)),
					Math.toIntExact(comicCounts.getOrDefault(c.getId(), 0L)), hasAudio.getOrDefault(c.getId(), false),
					c.getCreatedAt()))
			.toList();
		return new CardPage(items, more ? String.valueOf(shown.getLast().getId()) : null);
	}

	@Transactional(readOnly = true)
	public CardDetail detail(long userId, long id) {
		return toDetail(find(userId, id));
	}

	// 바꾼 것만 받는다(api-dto CardUpdateRequest). 모두 검사한 뒤에 바꾸고, 하나라도 틀리면 아무것도 바꾸지 않는다.
	// 순서: 화자 더하기·이름·그림 → 대사 → 장소 → 화자 빼기와 key 다시 매기기. 요청 안의 key는 모두 빼기 전 기준이다.
	@Transactional
	public CardDetail update(long userId, long id, CardUpdateRequest request) {
		if (request.getSpeakers().isEmpty() && request.getRemoveSpeakers().isEmpty() && request.getLines().isEmpty()
				&& !request.isPlaceSent()) {
			throw ApiException.invalid(null, "바꿀 내용이 없습니다.");
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
				throw ApiException.invalid(at + ".key", "같은 화자를 두 번 보냈습니다.");
			}
			boolean isNew = !speakers.containsKey(update.getKey());
			if (isNew && !allowedNew.contains(update.getKey())) {
				throw unknownSpeaker(at + ".key");
			}
			// 새 화자는 이름·그림 없이 만들어도 된다.
			if (!isNew && !update.isNameSent() && !update.isCharacterSent()) {
				throw ApiException.invalid(at, "name이나 character가 필요합니다. 지우려면 null을 보내세요.");
			}
			if (update.isNameSent()) {
				checkText(update.getName(), MAX_NAME, at + ".name");
			}
			if (update.isCharacterSent() && update.getCharacter() != null) {
				CharacterRef ref = update.getCharacter();
				if (!CharacterViews.DEFAULT.equals(ref.type()) && !CharacterViews.PROFILE.equals(ref.type())) {
					throw ApiException.invalid(at + ".character.type", "type은 DEFAULT 또는 PROFILE이에요.");
				}
				if (ref.id() == null || ref.id().isBlank()) {
					throw ApiException.invalid(at + ".character.id", "id가 필요합니다.");
				}
				characters.checkAttachable(userId, new Ref(ref.type(), ref.id()), at + ".character");
			}
		}

		Set<String> removeKeys = new HashSet<>();
		for (int i = 0; i < request.getRemoveSpeakers().size(); i++) {
			String key = request.getRemoveSpeakers().get(i);
			String at = "removeSpeakers[" + i + "]";
			if (!removeKeys.add(key)) {
				throw ApiException.invalid(at, "같은 화자를 두 번 보냈습니다.");
			}
			if (!speakers.containsKey(key)) {
				throw unknownSpeaker(at);
			}
			if (seenKeys.contains(key)) {
				throw ApiException.invalid(at, "speakers에도 있는 화자는 뺄 수 없습니다.");
			}
		}

		Set<Long> seenIds = new HashSet<>();
		for (int j = 0; j < request.getLines().size(); j++) {
			LineUpdate update = request.getLines().get(j);
			String at = "lines[" + j + "]";
			if (!seenIds.add(update.id())) {
				throw ApiException.invalid(at + ".id", "같은 대사를 두 번 보냈습니다.");
			}
			if (!lines.containsKey(update.id())) {
				throw ApiException.invalid(at + ".id", "이 카드의 대사가 아닙니다.");
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
				throw ApiException.invalid("speakers[" + i + "]", "새 화자에게 줄 대사가 없습니다.");
			}
		}
		int countAfter = speakers.size() + newKeys.size() - removeKeys.size();
		if (countAfter < 1) {
			throw ApiException.invalid("removeSpeakers", "화자가 한 명은 남아야 합니다.");
		}
		if (countAfter > MAX_SPEAKERS) {
			throw new ApiException(HttpStatus.CONFLICT, "SPEAKER_LIMIT", "화자는 4명까지예요.");
		}
		// 빼는 화자에게 대사가 남아 있으면 뺄 수 없다(대사 변경을 적용한 뒤 기준).
		for (int i = 0; i < request.getRemoveSpeakers().size(); i++) {
			if (speakerOfLine.containsValue(request.getRemoveSpeakers().get(i))) {
				throw new ApiException(HttpStatus.CONFLICT, "SPEAKER_HAS_LINES", "대사가 남아 있는 화자는 뺄 수 없어요.");
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

	// 남은 화자의 key를 화자1부터 빠짐없이 다시 매긴다. 이름·그림과 그 화자의 대사가 따라간다.
	// 작은 번호부터 바꾸면 바꿀 자리는 늘 비어 있다(앞 화자는 이미 더 작은 번호로 옮겼고, 뒤 화자는 더 큰 번호다).
	private void renumberSpeakers(long cardId) {
		List<String> remaining = jdbc.queryForList("select speaker_key from card_speakers where card_id = ?", String.class,
				cardId).stream().sorted(Comparator.comparingInt(CardService::speakerNumber)).toList();
		for (int i = 0; i < remaining.size(); i++) {
			String from = remaining.get(i);
			String to = SPEAKER_PREFIX + (i + 1);
			if (from.equals(to)) {
				continue;
			}
			jdbc.update("update card_speakers set speaker_key = ?, updated_at = current_timestamp "
					+ "where card_id = ? and speaker_key = ?", to, cardId, from);
			jdbc.update("update card_lines set speaker_key = ?, updated_at = current_timestamp "
					+ "where card_id = ? and speaker_key = ?", to, cardId, from);
		}
	}

	private static int speakerNumber(String key) {
		return Integer.parseInt(key.substring(SPEAKER_PREFIX.length()));
	}

	// 카드를 지워도 그 카드로 만든 만화는 남는다(DB가 comics.card_id를 null로 바꾼다).
	@Transactional
	public void delete(long userId, long id) {
		Card card = findLocked(userId, id);
		// 카드 구간 음성도 같이 지운다(audio.md 5장). 행을 잠근 뒤 읽어서, 자르는 중인 쪽과 엇갈리지 않는다.
		String audioKey = jdbc.queryForObject("select audio_key from cards where id = ?", String.class, card.getId());
		cards.delete(card);
		if (audioKey != null) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					try {
						storage.delete(audioKey);
					}
					catch (Exception e) {
						log.warn("지운 카드 {}의 구간 음성 파일을 지우지 못했습니다.", id, SafeLog.withoutMessages(e));
					}
				}
			});
		}
	}

	private Card find(long userId, long id) {
		return cards.findByIdAndUserId(id, userId).orElseThrow(CardService::notFound);
	}

	// 만화를 만드는 중이면 고치기·지우기를 막는다(409 CARD_LOCKED).
	private Card findLocked(long userId, long id) {
		Card card = cards.findByIdAndUserIdForUpdate(id, userId).orElseThrow(CardService::notFound);
		if (comics.existsByCardIdAndStatusIn(card.getId(), ComicStatus.IN_PROGRESS)) {
			throw new ApiException(HttpStatus.CONFLICT, "CARD_LOCKED", "만화를 만드는 중인 카드입니다.");
		}
		return card;
	}

	// 한 카드에서 같은 그림을 두 화자에 붙일 수 없다(한 컷에 같은 얼굴이 둘 나온다). 고친 뒤 모습으로 본다.
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
				throw new ApiException(HttpStatus.BAD_REQUEST, "DUPLICATE_CHARACTER", "한 카드에서 같은 그림을 두 화자에 붙일 수 없어요.",
						"speakers[" + i + "].character");
			}
		}
	}

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
		return new CardDetail(card.getId(), card.getRecordingId(), card.getKind(), card.getSummary(), card.getPlace(),
				card.getRecordedAt(), card.getStartMs(), card.getEndMs(), speakers, lines, comicRefs,
				MAX_COMICS - card.getComicCount(), audio.detail(card.getId()), card.getCreatedAt());
	}

	// 앞뒤 공백을 뺀 뒤 1~max자. null은 "지움"이라 통과한다(대사 text는 null이면 부르지 않는다).
	private static void checkText(String value, int max, String field) {
		if (value == null) {
			return;
		}
		String trimmed = value.strip();
		if (trimmed.isEmpty() || trimmed.codePointCount(0, trimmed.length()) > max) {
			throw ApiException.invalid(field, "1~" + max + "자로 써야 합니다.");
		}
	}

	private static String trimOrNull(String value) {
		return value == null ? null : value.strip();
	}

	private static Map<Long, Long> toMap(List<Object[]> rows) {
		return rows.stream().collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "카드를 찾을 수 없습니다.");
	}

	private static ApiException unknownSpeaker(String field) {
		return new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_SPEAKER", "카드에 없는 화자입니다.", field);
	}

}
