package com.lolog.api.character;

import com.lolog.api.common.ApiException;
import com.lolog.api.storage.ImageStorage;
import java.util.List;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 기본 화자(default_characters): 서비스가 넣어 둔 화자 그림 목록(characters.md 2장).
// 그림은 저장소의 defaults/characters/ 아래에 있고, 목록은 DB에 있다. 늘릴 때는 그림을 올리고 목록에 더하기만 한다.
@Service
public class DefaultCharacterService {

	private static final Set<String> AGE_GROUPS = Set.of("CHILD", "YOUNG", "MIDDLE");
	private static final Set<String> GENDERS = Set.of("F", "M");

	private final JdbcTemplate jdbc;
	private final ImageStorage storage;

	public DefaultCharacterService(JdbcTemplate jdbc, ImageStorage storage) {
		this.jdbc = jdbc;
		this.storage = storage;
	}

	// appearance는 그림 모델용이라 앱에 보내지 않는다. voiceUrl은 목소리 파일의 10분 서명 주소이고, 없으면 null(FR-CHAR-03).
	public record DefaultCharacterItem(String id, String label, String ageGroup, String gender, boolean glasses,
			String thumbnailUrl, String voiceUrl) {
	}

	public record DefaultCharacterList(List<DefaultCharacterItem> characters) {
	}

	@Transactional(readOnly = true)
	public DefaultCharacterList list() {
		return new DefaultCharacterList(jdbc.query("""
				select id, label, age_group, gender, glasses, thumb_key, voice_key from default_characters
				order by sort_order, id
				""", (rs, n) -> {
			String voice = rs.getString("voice_key");
			return new DefaultCharacterItem(rs.getString("id"), rs.getString("label"), rs.getString("age_group"),
					rs.getString("gender"), rs.getBoolean("glasses"), storage.presignedUrl(rs.getString("thumb_key")).toString(),
					voice == null ? null : storage.presignedUrl(voice).toString());
		}));
	}

	// 기본 화자 하나를 넣거나 바꾼다(PUT /internal/characters/defaults/{id}, 스크립트용).
	// image·thumbnail을 보내면 저장소에 올리고, 빼면 이미 올라가 있다고 보고 목록만 넣는다.
	// voice(wav)는 보내면 올리고 목소리를 붙인다. 빼면 지금 붙어 있는 목소리를 그대로 둔다(처음이면 없음).
	@Transactional
	public void upsert(String id, byte[] image, byte[] thumbnail, byte[] voice, String ageGroup, String gender,
			Boolean glasses, String label, String appearance, Integer sortOrder) {
		if (id == null || !id.matches("[a-z0-9-]{1,20}")) {
			throw ApiException.invalid("id", "id는 영어 소문자·숫자·-로 20자까지예요.");
		}
		if (!AGE_GROUPS.contains(ageGroup)) {
			throw ApiException.invalid("ageGroup", "ageGroup은 CHILD, YOUNG, MIDDLE 중 하나예요.");
		}
		if (!GENDERS.contains(gender)) {
			throw ApiException.invalid("gender", "gender는 F, M 중 하나예요.");
		}
		if (glasses == null) {
			throw ApiException.invalid("glasses", "glasses가 필요해요.");
		}
		if (label == null || label.isBlank() || label.length() > 50) {
			throw ApiException.invalid("label", "label은 1~50자예요.");
		}
		if (appearance == null || appearance.isBlank() || appearance.length() > 200) {
			throw ApiException.invalid("appearance", "appearance는 1~200자예요.");
		}
		if (sortOrder == null) {
			throw ApiException.invalid("sortOrder", "sortOrder가 필요해요.");
		}
		// 그림은 PNG 768×768, 줄인 그림은 WebP다(characters.md /internal 표). 크기가 틀린 그림이 목록에 오르지 않게 본다.
		if (image != null && !isPng(image, 768, 768)) {
			throw ApiException.invalid("image", "image는 768×768 PNG여야 해요.");
		}
		if (thumbnail != null && !isWebp(thumbnail)) {
			throw ApiException.invalid("thumbnail", "thumbnail은 WebP 파일이어야 해요.");
		}
		if (voice != null && !isWav(voice)) {
			throw ApiException.invalid("voice", "voice는 wav 파일이어야 해요.");
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
		jdbc.update("""
				insert into default_characters (id, age_group, gender, glasses, label, appearance, image_key, thumb_key,
				    voice_key, sort_order)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				on conflict (id) do update set age_group = excluded.age_group, gender = excluded.gender,
				    glasses = excluded.glasses, label = excluded.label, appearance = excluded.appearance,
				    image_key = excluded.image_key, thumb_key = excluded.thumb_key,
				    voice_key = coalesce(excluded.voice_key, default_characters.voice_key), sort_order = excluded.sort_order,
				    updated_at = current_timestamp
				""", id, ageGroup, gender, glasses, label.strip(), appearance.strip(), imageKey, thumbKey, voiceKey,
				sortOrder);
	}

	// RIFF....WAVE 머리말
	// PNG 머리말과 IHDR의 가로·세로
	static boolean isPng(byte[] b, int width, int height) {
		byte[] signature = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
		if (b.length < 24 || !java.util.Arrays.equals(b, 0, 8, signature, 0, 8) || b[12] != 'I' || b[13] != 'H'
				|| b[14] != 'D' || b[15] != 'R') {
			return false;
		}
		return readInt(b, 16) == width && readInt(b, 20) == height;
	}

	// RIFF 머리말과 WEBP 표시. 크기는 VP8·VP8L·VP8X마다 적는 곳이 달라 보지 않는다.
	static boolean isWebp(byte[] b) {
		return b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F' && b[8] == 'W' && b[9] == 'E'
				&& b[10] == 'B' && b[11] == 'P';
	}

	private static int readInt(byte[] b, int at) {
		return ((b[at] & 0xFF) << 24) | ((b[at + 1] & 0xFF) << 16) | ((b[at + 2] & 0xFF) << 8) | (b[at + 3] & 0xFF);
	}

	static boolean isWav(byte[] bytes) {
		return bytes.length >= 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
				&& bytes[8] == 'W' && bytes[9] == 'A' && bytes[10] == 'V' && bytes[11] == 'E';
	}

}
