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

	// appearance는 그림 모델용이라 앱에 보내지 않는다.
	public record DefaultCharacterItem(String id, String label, String ageGroup, String gender, boolean glasses,
			String thumbnailUrl) {
	}

	public record DefaultCharacterList(List<DefaultCharacterItem> characters) {
	}

	@Transactional(readOnly = true)
	public DefaultCharacterList list() {
		return new DefaultCharacterList(jdbc.query("""
				select id, label, age_group, gender, glasses, thumb_key from default_characters order by sort_order, id
				""", (rs, n) -> new DefaultCharacterItem(rs.getString("id"), rs.getString("label"),
				rs.getString("age_group"), rs.getString("gender"), rs.getBoolean("glasses"),
				storage.presignedUrl(rs.getString("thumb_key")).toString())));
	}

	// 기본 화자 하나를 넣거나 바꾼다(PUT /internal/characters/defaults/{id}, 스크립트용).
	// image·thumbnail을 보내면 저장소에 올리고, 빼면 이미 올라가 있다고 보고 목록만 넣는다.
	@Transactional
	public void upsert(String id, byte[] image, byte[] thumbnail, String ageGroup, String gender, Boolean glasses,
			String label, String appearance, Integer sortOrder) {
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
		String imageKey = ImageStorage.defaultCharacterKey(id);
		String thumbKey = ImageStorage.defaultCharacterThumbKey(id);
		if (image != null) {
			storage.putPng(imageKey, image);
		}
		if (thumbnail != null) {
			storage.put(thumbKey, thumbnail, "image/webp");
		}
		jdbc.update("""
				insert into default_characters (id, age_group, gender, glasses, label, appearance, image_key, thumb_key, sort_order)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?)
				on conflict (id) do update set age_group = excluded.age_group, gender = excluded.gender,
				    glasses = excluded.glasses, label = excluded.label, appearance = excluded.appearance,
				    image_key = excluded.image_key, thumb_key = excluded.thumb_key, sort_order = excluded.sort_order,
				    updated_at = current_timestamp
				""", id, ageGroup, gender, glasses, label.strip(), appearance.strip(), imageKey, thumbKey, sortOrder);
	}

}
