package com.lolog.api.character;

import com.lolog.api.common.ApiException;
import com.lolog.api.storage.ImageStorage;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

// 화자 그림을 읽는 쪽 모음(characters.md 4장). 카드 응답, 만화 원본(ComicSource), 프로필·친구 응답이 쓴다.
// 그림은 card_speakers.character_type·character_id로 가리킨다. PROFILE의 id는 회원 ID를 글자로 적은 것이다.
@Component
public class CharacterViews {

	public static final String DEFAULT = "DEFAULT";
	public static final String PROFILE = "PROFILE";

	private final NamedParameterJdbcTemplate jdbc;
	private final ImageStorage storage;

	public CharacterViews(NamedParameterJdbcTemplate jdbc, ImageStorage storage) {
		this.jdbc = jdbc;
		this.storage = storage;
	}

	// 카드에 붙인 그림을 가리키는 값
	public record Ref(String type, String id) {
	}

	// 카드 응답 Speaker.character. label은 기본 화자 label 또는 그 사람의 닉네임(없으면 null)
	public record SpeakerCharacter(String type, String id, String label, String thumbnailUrl) {
	}

	// ComicSource Speaker.character. imageUrl은 768 원본의 10분 서명 주소
	public record SourceCharacter(String ref, String appearance, String imageUrl) {
	}

	// FriendItem.character. 친구에게 쓸 수 있는 그림이 있을 때만
	public record FriendCharacter(String thumbnailUrl) {
	}

	// ---- 프로필·친구 ----

	public ProfileCharacterService.ProfileCharacterResponse profileResponse(long userId) {
		List<ProfileCharacterService.ProfileCharacterResponse> found = jdbc.query("""
				select character_status, character_thumb_key, character_updated_at, character_voice, voice_clone_status,
				       voice_clone_preview_key, voice_clone_fail_reason, voice_clone_share_friends
				from user_profiles where user_id = :id
				""", Map.of("id", userId), (rs, n) -> {
			String status = rs.getString("character_status");
			if (status == null) {
				return null;
			}
			String thumb = rs.getString("character_thumb_key");
			// 내 목소리. 만든 적이 없으면 null(정함 11-14). previewUrl은 미리 듣기 wav의 10분 서명 주소
			String cloneStatus = rs.getString("voice_clone_status");
			String preview = rs.getString("voice_clone_preview_key");
			VoiceCloneService.VoiceCloneView clone = cloneStatus == null ? null
					: new VoiceCloneService.VoiceCloneView(cloneStatus, preview == null ? null : url(preview),
							rs.getString("voice_clone_fail_reason"), rs.getBoolean("voice_clone_share_friends"));
			return new ProfileCharacterService.ProfileCharacterResponse(status,
					thumb == null ? null : storage.presignedUrl(thumb).toString(),
					rs.getObject("character_updated_at", OffsetDateTime.class), rs.getString("character_voice"), clone);
		});
		return found.isEmpty() ? null : found.getFirst();
	}

	// 쓸 수 있는 그림이 없으면 null(친구의 status는 보내지 않는다).
	public FriendCharacter friendCharacter(String thumbKey) {
		return thumbKey == null ? null : new FriendCharacter(storage.presignedUrl(thumbKey).toString());
	}

	// ---- 카드 응답 ----

	public Map<Ref, SpeakerCharacter> speakerCharacters(Collection<Ref> refs) {
		Map<Ref, SpeakerCharacter> result = new HashMap<>();
		Set<String> defaults = ids(refs, DEFAULT);
		if (!defaults.isEmpty()) {
			jdbc.query("select id, label, thumb_key from default_characters where id in (:ids)", Map.of("ids", defaults),
					rs -> {
						result.put(new Ref(DEFAULT, rs.getString("id")), new SpeakerCharacter(DEFAULT,
								rs.getString("id"), rs.getString("label"), url(rs.getString("thumb_key"))));
					});
		}
		Set<Long> profiles = userIds(refs);
		if (!profiles.isEmpty()) {
			jdbc.query("select user_id, nickname, character_thumb_key from user_profiles where user_id in (:ids)",
					Map.of("ids", profiles), rs -> {
						String id = String.valueOf(rs.getLong("user_id"));
						result.put(new Ref(PROFILE, id), new SpeakerCharacter(PROFILE, id, rs.getString("nickname"),
								url(rs.getString("character_thumb_key"))));
					});
		}
		return result;
	}

	// ---- 만화 원본 ----

	// 카드 주인 owner의 화자 그림. 붙어 있던 프로필 캐릭터가 그 사이 지워졌거나 친구가 끊겼으면 그 화자는 그림 없이 만든다(오류 아님).
	public Map<Ref, SourceCharacter> sourceCharacters(long owner, Collection<Ref> refs) {
		Map<Ref, SourceCharacter> result = new HashMap<>();
		Set<String> defaults = ids(refs, DEFAULT);
		if (!defaults.isEmpty()) {
			jdbc.query("select id, appearance, image_key from default_characters where id in (:ids)",
					Map.of("ids", defaults), rs -> {
						String id = rs.getString("id");
						result.put(new Ref(DEFAULT, id),
								new SourceCharacter("default:" + id, rs.getString("appearance"), url(rs.getString("image_key"))));
					});
		}
		Set<Long> profiles = userIds(refs);
		if (!profiles.isEmpty()) {
			jdbc.query("""
					select p.user_id, p.character_appearance, p.character_key from user_profiles p
					where p.user_id in (:ids) and p.character_key is not null and p.character_appearance is not null
					  and (p.user_id = :owner or exists (select 1 from friendships f where f.status = 'ACCEPTED'
					       and ((f.requester_id = :owner and f.addressee_id = p.user_id)
					         or (f.addressee_id = :owner and f.requester_id = p.user_id))))
					""", new MapSqlParameterSource("ids", profiles).addValue("owner", owner), rs -> {
				String id = String.valueOf(rs.getLong("user_id"));
				result.put(new Ref(PROFILE, id), new SourceCharacter("profile:" + id,
						rs.getString("character_appearance"), url(rs.getString("character_key"))));
			});
		}
		return result;
	}

	// ---- 카드 고치기 검사 ----

	// 붙일 수 있는 그림인지 본다. 없는 기본 화자, 나도 친구도 아닌 회원 → 400 UNKNOWN_CHARACTER.
	// 쓸 수 있는 그림이 없는 프로필 캐릭터(처음 만드는 중, 처음 만들기 실패, 지움, 만든 적 없음) → 409 CHARACTER_NOT_READY.
	public void checkAttachable(long owner, Ref ref, String field) {
		if (DEFAULT.equals(ref.type())) {
			Boolean exists = jdbc.queryForObject("select exists (select 1 from default_characters where id = :id)",
					Map.of("id", ref.id()), Boolean.class);
			if (!Boolean.TRUE.equals(exists)) {
				throw unknown(field);
			}
			return;
		}
		Long userId = parseUserId(ref.id());
		if (userId == null) {
			throw unknown(field);
		}
		boolean self = userId == owner;
		if (!self) {
			Boolean friends = jdbc.queryForObject("""
					select exists (select 1 from friendships where status = 'ACCEPTED'
					  and ((requester_id = :a and addressee_id = :b) or (requester_id = :b and addressee_id = :a)))
					""", new MapSqlParameterSource("a", owner).addValue("b", userId), Boolean.class);
			if (!Boolean.TRUE.equals(friends)) {
				throw unknown(field);
			}
		}
		List<String> key = jdbc.queryForList("select character_key from user_profiles where user_id = :id",
				Map.of("id", userId), String.class);
		if (key.isEmpty() || key.getFirst() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "CHARACTER_NOT_READY", "쓸 수 있는 그림이 없는 캐릭터예요.");
		}
	}

	private String url(String key) {
		return key == null ? null : storage.presignedUrl(key).toString();
	}

	private static Set<String> ids(Collection<Ref> refs, String type) {
		return refs.stream().filter(r -> type.equals(r.type())).map(Ref::id).collect(Collectors.toSet());
	}

	private static Set<Long> userIds(Collection<Ref> refs) {
		return refs.stream()
			.filter(r -> PROFILE.equals(r.type()))
			.map(r -> parseUserId(r.id()))
			.filter(java.util.Objects::nonNull)
			.collect(Collectors.toSet());
	}

	static Long parseUserId(String id) {
		if (id == null || !id.matches("\\d{1,18}")) {
			return null;
		}
		return Long.parseLong(id);
	}

	private static ApiException unknown(String field) {
		return new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_CHARACTER", "붙일 수 없는 그림이에요.", field);
	}

}
