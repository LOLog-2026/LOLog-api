package com.lolog.api.card;

import com.lolog.api.card.CardDtos.CardDetail;
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

	private static final Logger log = LoggerFactory.getLogger(CardService.class);

	private final CardRepository cards;
	private final ComicRepository comics;
	private final JdbcTemplate jdbc;
	private final ImageStorage storage;

	public CardService(CardRepository cards, ComicRepository comics, JdbcTemplate jdbc, ImageStorage storage) {
		this.cards = cards;
		this.comics = comics;
		this.jdbc = jdbc;
		this.storage = storage;
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
		List<CardSummary> items = shown.stream()
			.map(c -> new CardSummary(c.getId(), c.getKind(), c.getSummary(), c.getRecordedAt(), c.getStartMs(),
					c.getEndMs(), c.getPlace(), Math.toIntExact(lineCounts.getOrDefault(c.getId(), 0L)),
					Math.toIntExact(comicCounts.getOrDefault(c.getId(), 0L)), c.getCreatedAt()))
			.toList();
		return new CardPage(items, more ? String.valueOf(shown.getLast().getId()) : null);
	}

	@Transactional(readOnly = true)
	public CardDetail detail(long userId, long id) {
		return toDetail(find(userId, id));
	}

	@Transactional
	public CardDetail update(long userId, long id, CardUpdateRequest request) {
		if (request.getSpeakers().isEmpty() && request.getLines().isEmpty() && !request.isPlaceSent()) {
			throw ApiException.invalid(null, "바꿀 내용이 없습니다.");
		}
		Card card = findLocked(userId, id);
		Map<String, CardSpeaker> speakers = card.getSpeakers().stream()
			.collect(Collectors.toMap(CardSpeaker::getSpeakerKey, Function.identity()));
		Map<Long, CardLine> lines = card.getLines().stream()
			.collect(Collectors.toMap(CardLine::getId, Function.identity()));

		// 모두 검사한 뒤에 바꾼다. 하나라도 틀리면 아무것도 바꾸지 않는다.
		Set<String> seenKeys = new HashSet<>();
		for (int i = 0; i < request.getSpeakers().size(); i++) {
			SpeakerUpdate update = request.getSpeakers().get(i);
			String at = "speakers[" + i + "]";
			if (!seenKeys.add(update.getKey())) {
				throw ApiException.invalid(at + ".key", "같은 화자를 두 번 보냈습니다.");
			}
			if (!speakers.containsKey(update.getKey())) {
				throw unknownSpeaker(at + ".key");
			}
			if (!update.isNameSent()) {
				throw ApiException.invalid(at + ".name", "name이 필요합니다. 지우려면 null을 보내세요.");
			}
			checkText(update.getName(), MAX_NAME, at + ".name");
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
			if (update.speaker() != null && !speakers.containsKey(update.speaker())) {
				throw unknownSpeaker(at + ".speaker");
			}
			if (update.text() != null) {
				checkText(update.text(), MAX_TEXT, at + ".text");
			}
		}
		if (request.isPlaceSent()) {
			checkText(request.getPlace(), MAX_PLACE, "place");
		}

		request.getSpeakers().forEach(u -> speakers.get(u.getKey()).setName(trimOrNull(u.getName())));
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
		return toDetail(card);
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

	private CardDetail toDetail(Card card) {
		List<Speaker> speakers = card.getSpeakers().stream()
			.map(s -> new Speaker(s.getSpeakerKey(), s.getName()))
			.toList();
		List<Line> lines = card.getLines().stream()
			.map(l -> new Line(l.getId(), l.getSpeakerKey(), l.getText()))
			.toList();
		List<ComicRef> comicRefs = comics.findByCardIdOrderByIdDesc(card.getId()).stream()
			.map(m -> new ComicRef(m.getId(), m.getStatus()))
			.toList();
		return new CardDetail(card.getId(), card.getRecordingId(), card.getKind(), card.getSummary(), card.getPlace(),
				card.getRecordedAt(), card.getStartMs(), card.getEndMs(), speakers, lines, comicRefs,
				MAX_COMICS - card.getComicCount(), card.getCreatedAt());
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
