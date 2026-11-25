package com.lolog.api.domain.character.service;

import com.lolog.api.domain.character.repository.DefaultCharacterRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.storage.ImageStorage;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 기본 화자(default_characters): 서비스가 넣어 둔 화자 그림 목록(characters.md 2장).
// 그림은 저장소의 defaults/characters/ 아래에 있고, 목록은 DB에 있다. 늘릴 때는 그림을 올리고 목록에 더하기만 한다.
@Service
public class DefaultCharacterService {

	private static final Set<String> AGE_GROUPS = Set.of("CHILD", "YOUNG", "MIDDLE");
	private static final Set<String> GENDERS = Set.of("F", "M");

	private final DefaultCharacterRepository repository;
	private final ImageStorage storage;

	/** 리포지토리와 저장소를 받는다. */
	public DefaultCharacterService(DefaultCharacterRepository repository, ImageStorage storage) {
		this.repository = repository;
		this.storage = storage;
	}

	/**
	 * appearance는 그림 모델용이라 앱에 보내지 않는다. voiceUrl은 목소리 파일의 10분 서명 주소이고, 없으면 null(FR-CHAR-03).
	 *
	 * @param id 기본 화자 id
	 * @param label 화자 이름
	 * @param ageGroup 나이대(CHILD, YOUNG, MIDDLE)
	 * @param gender 성별(F, M)
	 * @param glasses 안경을 썼으면 true
	 * @param thumbnailUrl 줄인 그림의 10분 서명 주소
	 * @param voiceUrl 목소리 파일의 10분 서명 주소(없으면 null)
	 */
	public record DefaultCharacterItem(String id, String label, String ageGroup, String gender, boolean glasses,
			String thumbnailUrl, String voiceUrl) {
	}

	/**
	 * 기본 화자 목록 응답.
	 *
	 * @param characters 기본 화자 목록(정한 순서대로)
	 */
	public record DefaultCharacterList(List<DefaultCharacterItem> characters) {
	}

	// 기본 화자 목록을 정한 순서대로 돌려준다.
	@Transactional(readOnly = true)
	public DefaultCharacterList list() {
		return new DefaultCharacterList(repository.findAllByOrderBySortOrderAscIdAsc().stream()
				.map(c -> new DefaultCharacterItem(c.getId(), c.getLabel(), c.getAgeGroup(), c.getGender(), c.isGlasses(),
						storage.presignedUrl(c.getThumbKey()).toString(),
						c.getVoiceKey() == null ? null : storage.presignedUrl(c.getVoiceKey()).toString()))
				.toList());
	}

	/**
	 * 기본 화자 하나를 넣거나 바꾼다(PUT /internal/characters/defaults/{id}, 스크립트용).
	 * image·thumbnail을 보내면 저장소에 올리고, 빼면 이미 올라가 있다고 보고 목록만 넣는다.
	 * voice(wav)는 보내면 올리고 목소리를 붙인다. 빼면 지금 붙어 있는 목소리를 그대로 둔다(처음이면 없음).
	 *
	 * @param id 기본 화자 id(영어 소문자·숫자·-, 20자 이하)
	 * @param image 원본 그림(PNG 768×768, null이면 올리지 않음)
	 * @param thumbnail 줄인 그림(WebP, null이면 올리지 않음)
	 * @param voice 목소리 파일(wav, null이면 지금 목소리를 그대로 둠)
	 * @param ageGroup 나이대(CHILD, YOUNG, MIDDLE)
	 * @param gender 성별(F, M)
	 * @param glasses 안경을 썼으면 true(null이면 400)
	 * @param label 화자 이름(50자 이하)
	 * @param appearance 그림 모델용 생김새 설명(200자 이하)
	 * @param sortOrder 목록에서의 순서(null이면 400)
	 */
	@Transactional
	public void upsert(String id, byte[] image, byte[] thumbnail, byte[] voice, String ageGroup, String gender,
			Boolean glasses, String label, String appearance, Integer sortOrder) {
		if (id == null || !id.matches("[a-z0-9-]{1,20}")) {
			throw ApiException.invalid("id");
		}
		if (!AGE_GROUPS.contains(ageGroup)) {
			throw ApiException.invalid("ageGroup");
		}
		if (!GENDERS.contains(gender)) {
			throw ApiException.invalid("gender");
		}
		if (glasses == null) {
			throw ApiException.invalid("glasses");
		}
		if (label == null || label.isBlank() || label.length() > 50) {
			throw ApiException.invalid("label");
		}
		if (appearance == null || appearance.isBlank() || appearance.length() > 200) {
			throw ApiException.invalid("appearance");
		}
		if (sortOrder == null) {
			throw ApiException.invalid("sortOrder");
		}
		// 그림은 PNG 768×768, 줄인 그림은 WebP다(characters.md /internal 표). 크기가 틀린 그림이 목록에 오르지 않게 본다.
		if (image != null && !isPng(image, 768, 768)) {
			throw ApiException.invalid("image");
		}
		if (thumbnail != null && !isWebp(thumbnail)) {
			throw ApiException.invalid("thumbnail");
		}
		if (voice != null && !isWav(voice)) {
			throw ApiException.invalid("voice");
		}
		String imageKey = ImageStorage.defaultCharacterKey(id);
		String thumbKey = ImageStorage.defaultCharacterThumbKey(id);
		if (image != null) {
			storage.putPng(imageKey, image);
		}
		if (thumbnail != null) {
			storage.put(thumbKey, thumbnail, "image/webp");
		}
		String voiceKey = null;
		if (voice != null) {
			voiceKey = ImageStorage.defaultCharacterVoiceKey(id);
			storage.put(voiceKey, voice, "audio/wav");
		}
		repository.upsert(id, ageGroup, gender, glasses, label.strip(), appearance.strip(), imageKey, thumbKey, voiceKey,
				sortOrder);
	}

	/**
	 * 목소리가 붙어 있는 기본 화자인지 본다(TTS 참조 음성으로 쓸 수 있는지).
	 *
	 * @param id 기본 화자 id
	 * @return 목소리가 붙어 있으면 true
	 */
	@Transactional(readOnly = true)
	public boolean hasVoice(String id) {
		return repository.existsByIdAndVoiceKeyIsNotNull(id);
	}

	/**
	 * RIFF....WAVE 머리말
	 * PNG 머리말과 IHDR의 가로·세로
	 *
	 * @param b 살펴볼 파일 바이트
	 * @param width 맞아야 할 가로 픽셀
	 * @param height 맞아야 할 세로 픽셀
	 * @return PNG이고 크기가 맞으면 true
	 */
	static boolean isPng(byte[] b, int width, int height) {
		byte[] signature = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
		if (b.length < 24 || !java.util.Arrays.equals(b, 0, 8, signature, 0, 8) || b[12] != 'I' || b[13] != 'H'
				|| b[14] != 'D' || b[15] != 'R') {
			return false;
		}
		return readInt(b, 16) == width && readInt(b, 20) == height;
	}

	/**
	 * RIFF 머리말과 WEBP 표시. 크기는 VP8·VP8L·VP8X마다 적는 곳이 달라 보지 않는다.
	 *
	 * @param b 살펴볼 파일 바이트
	 * @return WebP면 true
	 */
	static boolean isWebp(byte[] b) {
		return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E'
				&& b[10] == 'B' && b[11] == 'P';
	}

	/**
	 * 4바이트를 큰 수 먼저(big-endian) int로 읽는다.
	 *
	 * @param b 읽을 바이트
	 * @param at 읽기 시작할 위치
	 * @return 읽은 int 값
	 */
	private static int readInt(byte[] b, int at) {
		return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
	}

	/**
	 * wav 머리말(RIFF....WAVE)인지 본다.
	 *
	 * @param bytes 살펴볼 파일 바이트
	 * @return wav 머리말이면 true
	 */
	static boolean isWav(byte[] bytes) {
		return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
	}

}
