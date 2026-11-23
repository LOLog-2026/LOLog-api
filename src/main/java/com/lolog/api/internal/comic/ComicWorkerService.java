package com.lolog.api.internal.comic;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.character.service.CharacterViews.Ref;
import com.lolog.api.domain.character.service.CharacterViews.SourceCharacter;
import com.lolog.api.domain.character.service.CharacterViews;
import com.lolog.api.domain.comic.entity.Comic;
import com.lolog.api.domain.comic.repository.ComicRepository;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.internal.comic.ComicWorkerDtos.ComicSource;
import com.lolog.api.internal.comic.ComicWorkerDtos.Line;
import com.lolog.api.internal.comic.ComicWorkerDtos.Speaker;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

// 워커가 만화를 만드는 단계(카드 내용 받기 → 콘티 → 완성 이미지)를 받는다.
// 이미 끝난(DONE·FAILED) 만화에는 source만 404이고, 콘티·이미지는 받기만 하고 아무것도 바꾸지 않는다(LOLog-docs PR #26).
@Service
public class ComicWorkerService {

	private static final Logger log = LoggerFactory.getLogger(ComicWorkerService.class);

	static final int WIDTH = 800;
	static final int HEIGHT = 3152;
	static final long MAX_BYTES = 20L * 1024 * 1024;
	private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private final ComicRepository comics;
	private final CardRepository cards;
	private final JobRepository jobs;
	private final ImageStorage storage;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;
	private final PushService push;
	private final CharacterViews characters;

	public ComicWorkerService(ComicRepository comics, CardRepository cards, JobRepository jobs, ImageStorage storage,
			ObjectMapper objectMapper, TransactionTemplate tx, PushService push, CharacterViews characters) {
		this.characters = characters;
		this.comics = comics;
		this.cards = cards;
		this.jobs = jobs;
		this.storage = storage;
		this.objectMapper = objectMapper;
		this.tx = tx;
		this.push = push;
	}

	// 만화를 만들 카드 내용을 주고 콘티 단계로 바꾼다.
	public ComicSource source(long comicId) {
		return tx.execute(status -> {
			Comic comic = comics.findByIdForUpdate(comicId).filter(c -> !c.isFinished()).orElseThrow(ComicWorkerService::notFound);
			// 만드는 중인 만화의 카드는 지울 수 없다(CARD_LOCKED). 그래도 없으면 만들 수 없는 만화로 본다.
			Card card = cards.findById(comic.getCardId() == null ? -1L : comic.getCardId())
				.orElseThrow(ComicWorkerService::notFound);
			comic.startStoryboard();
			// 붙어 있던 프로필 캐릭터가 지워졌거나 친구가 끊겼으면 그 화자는 그림 없이 만든다(오류 아님).
			List<Ref> refs = card.getSpeakers().stream()
				.filter(s -> s.getCharacterType() != null)
				.map(s -> new Ref(s.getCharacterType(), s.getCharacterId()))
				.toList();
			Map<Ref, SourceCharacter> found = refs.isEmpty() ? Map.of()
					: characters.sourceCharacters(card.getUserId(), refs);
			return new ComicSource(comic.getId(), card.getId(), card.getKind(), card.getSummary(), card.getPlace(),
					card.getSpeakers().stream()
						.map(s -> new Speaker(s.getSpeakerKey(), s.getName(), s.getCharacterType() == null ? null
								: found.get(new Ref(s.getCharacterType(), s.getCharacterId()))))
						.toList(),
					card.getLines().stream().map(l -> new Line(l.getId(), l.getSpeakerKey(), l.getText())).toList());
		});
	}

	// 콘티를 검사해 저장한다.
	public void storyboard(long comicId, JsonNode body) {
		tx.executeWithoutResult(status -> {
			Comic comic = comics.findByIdForUpdate(comicId).orElseThrow(ComicWorkerService::notFound);
			if (comic.isFinished()) {
				return;
			}
			checkStoryboard(comic, body);
			comic.saveStoryboard(objectMapper.writeValueAsString(body));
		});
	}

	// 완성 이미지를 올리고 만화를 끝낸 뒤 푸시를 보낸다.
	public void image(long comicId, byte[] png) {
		Comic found = comics.findById(comicId).orElseThrow(ComicWorkerService::notFound);
		if (found.isFinished()) {
			return;
		}
		// 서버 앞단의 multipart 제한(20MB)이 먼저 막지만, 설정이 바뀌어도 넘지 않게 여기서도 본다.
		if (png.length > MAX_BYTES) {
			throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
		}
		checkPng(png);
		// 올린 뒤에 상태를 바꾼다. 같은 키라 다시 올려도 덮어쓴다.
		String key = ImageStorage.comicKey(found.getUserId(), found.getId());
		storage.putPng(key, png);
		tx.executeWithoutResult(status -> {
			Comic comic = comics.findByIdForUpdate(comicId).orElse(null);
			if (comic == null) {
				// 올리는 사이에 만화가 지워졌으면 방금 올린 그림도 지운다. 지우지 못해도 404는 그대로 준다.
				try {
					storage.delete(key);
				}
				catch (RuntimeException e) {
					log.warn("지운 만화 {}의 그림을 지우지 못했습니다.", comicId, e);
				}
				throw notFound();
			}
			if (comic.isFinished()) {
				return;
			}
			comic.finish(key);
			jobs.findByComicId(comic.getId()).ifPresent(job -> job.complete());
			push.notify(comic.getUserId(), PushService.comicDone(comic.getId(), comic.getCardId()));
		});
	}

	// 기본만 본다: JSON 객체, version 1, comicId·cardId가 이 만화와 같음, panels 4개. 나머지 규칙은 워커가 검사한다.
	private static void checkStoryboard(Comic comic, JsonNode body) {
		if (body == null || !body.isObject()) {
			throw ApiException.invalid(null);
		}
		if (!body.path("version").isIntegralNumber() || body.path("version").asLong() != 1) {
			throw ApiException.invalid("version");
		}
		if (!body.path("comicId").isIntegralNumber() || body.path("comicId").asLong() != comic.getId()) {
			throw ApiException.invalid("comicId");
		}
		if (!body.path("cardId").isIntegralNumber() || comic.getCardId() == null
				|| body.path("cardId").asLong() != comic.getCardId()) {
			throw ApiException.invalid("cardId");
		}
		if (!body.path("panels").isArray() || body.path("panels").size() != 4) {
			throw ApiException.invalid("panels");
		}
	}

	// PNG 서명과 IHDR의 가로·세로(800×3152)를 본다.
	private static void checkPng(byte[] png) {
		boolean header = png.length >= 24 && java.util.Arrays.equals(png, 0, 8, PNG_SIGNATURE, 0, 8)
				&& png[12] == 'I' && png[13] == 'H' && png[14] == 'D' && png[15] == 'R';
		if (!header) {
			throw ApiException.invalid("file");
		}
		if (readInt(png, 16) != WIDTH || readInt(png, 20) != HEIGHT) {
			throw ApiException.invalid("file");
		}
	}

	// 4바이트를 정수로 읽는다.
	private static int readInt(byte[] b, int at) {
		return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
	}

	// 만화를 찾지 못했을 때의 오류다.
	private static ApiException notFound() {
		return new ApiException(ErrorCode.COMIC_NOT_FOUND);
	}

}
