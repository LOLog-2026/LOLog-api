package com.lolog.api.domain.character.service;

import com.lolog.api.domain.character.entity.DefaultCharacter;
import com.lolog.api.domain.character.repository.DefaultCharacterRepository;
import com.lolog.api.domain.friend.repository.FriendshipRepository;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

// 화자 그림을 읽는 쪽 모음(characters.md 4장). 카드 응답, 만화 원본(ComicSource), 프로필·친구 응답이 쓴다.
// 그림은 card_speakers.character_type·character_id로 가리킨다. PROFILE의 id는 회원 ID를 글자로 적은 것이다.
@Component
public class CharacterViews {

	public static final String DEFAULT = "DEFAULT";
	public static final String PROFILE = "PROFILE";

	private final UserProfileRepository profiles;
	private final FriendshipRepository friendships;
	private final ImageStorage storage;
	private final DefaultCharacterRepository defaults;

	/** 친구인지는 친구 도메인의 리포지토리로 본다. FriendService가 이 클래스를 받으므로 FriendService는 받지 않는다. */
	public CharacterViews(UserProfileRepository profiles, FriendshipRepository friendships, ImageStorage storage,
			DefaultCharacterRepository defaults) {
		this.profiles = profiles;
		this.friendships = friendships;
		this.storage = storage;
		this.defaults = defaults;
	}

	/**
	 * 카드에 붙인 그림을 가리키는 값
	 *
	 * @param type 그림 종류(DEFAULT 또는 PROFILE)
	 * @param id 기본 화자 id, PROFILE이면 회원 ID를 글자로 적은 것
	 */
	public record Ref(String type, String id) {
	}

	/**
	 * 카드 응답 Speaker.character. label은 기본 화자 label 또는 그 사람의 닉네임(없으면 null)
	 *
	 * @param type 그림 종류(DEFAULT 또는 PROFILE)
	 * @param id 기본 화자 id 또는 회원 ID 글자
	 * @param label 기본 화자 label 또는 그 사람의 닉네임(없으면 null)
	 * @param thumbnailUrl 줄인 그림의 10분 서명 주소(없으면 null)
	 */
	public record SpeakerCharacter(String type, String id, String label, String thumbnailUrl) {
	}

	/**
	 * ComicSource Speaker.character. imageUrl은 768 원본의 10분 서명 주소
	 *
	 * @param ref 그림을 가리키는 글자(default:id 또는 profile:회원 ID)
	 * @param appearance 그림 모델용 생김새 설명
	 * @param imageUrl 768 원본의 10분 서명 주소
	 */
	public record SourceCharacter(String ref, String appearance, String imageUrl) {
	}

	/**
	 * FriendItem.character. 친구에게 쓸 수 있는 그림이 있을 때만
	 *
	 * @param thumbnailUrl 친구 캐릭터 줄인 그림의 10분 서명 주소
	 */
	public record FriendCharacter(String thumbnailUrl) {
	}

	// ---- 프로필·친구 ----

	/**
	 * 내 프로필 캐릭터 응답을 만든다. 만든 적이 없으면 null.
	 *
	 * @param userId 프로필 캐릭터 주인 회원 ID
	 * @return 프로필 캐릭터 응답(만든 적이 없으면 null)
	 */
	public ProfileCharacterService.ProfileCharacterResponse profileResponse(long userId) {
		UserProfile p = profiles.findById(userId).orElse(null);
		if (p == null || p.getCharacterStatus() == null) {
			return null;
		}
		String thumb = p.getCharacterThumbKey();
		// 내 목소리. 만든 적이 없으면 null(정함 11-14). previewUrl은 미리 듣기 wav의 10분 서명 주소
		String cloneStatus = p.getVoiceCloneStatus();
		String preview = p.getVoiceClonePreviewKey();
		VoiceCloneService.VoiceCloneView clone = cloneStatus == null ? null
				: new VoiceCloneService.VoiceCloneView(cloneStatus, preview == null ? null : url(preview),
						p.getVoiceCloneFailReason(), p.isVoiceCloneShareFriends());
		return new ProfileCharacterService.ProfileCharacterResponse(p.getCharacterStatus(),
				thumb == null ? null : storage.presignedUrl(thumb).toString(), p.getCharacterUpdatedAt(),
				p.getCharacterVoice(), clone);
	}

	/**
	 * 쓸 수 있는 그림이 없으면 null(친구의 status는 보내지 않는다).
	 *
	 * @param thumbKey 친구 캐릭터 줄인 그림의 저장소 키(없으면 null)
	 * @return 친구 캐릭터(그림이 없으면 null)
	 */
	public FriendCharacter friendCharacter(String thumbKey) {
		return thumbKey == null ? null : new FriendCharacter(storage.presignedUrl(thumbKey).toString());
	}

	// ---- 카드 응답 ----

	/**
	 * 카드 화자에 붙은 그림을 응답 모양으로 읽는다.
	 *
	 * @param refs 카드 화자에 붙은 그림들
	 * @return 찾은 그림별 응답(찾지 못한 것은 빠짐)
	 */
	public Map<Ref, SpeakerCharacter> speakerCharacters(Collection<Ref> refs) {
		Map<Ref, SpeakerCharacter> result = new HashMap<>();
		Set<String> defaults = ids(refs, DEFAULT);
		if (!defaults.isEmpty()) {
			for (DefaultCharacter c : this.defaults.findAllById(defaults)) {
				result.put(new Ref(DEFAULT, c.getId()), new SpeakerCharacter(DEFAULT, c.getId(), c.getLabel(), url(c.getThumbKey())));
			}
		}
		Set<Long> profiles = userIds(refs);
		if (!profiles.isEmpty()) {
			for (UserProfile p : this.profiles.findAllById(profiles)) {
				String id = String.valueOf(p.getUserId());
				result.put(new Ref(PROFILE, id), new SpeakerCharacter(PROFILE, id, p.getNickname(), url(p.getCharacterThumbKey())));
			}
		}
		return result;
	}

	// ---- 만화 원본 ----

	/**
	 * 카드 주인 owner의 화자 그림. 붙어 있던 프로필 캐릭터가 그 사이 지워졌거나 친구가 끊겼으면 그 화자는 그림 없이 만든다(오류 아님).
	 *
	 * @param owner 카드 주인 회원 ID
	 * @param refs 카드 화자에 붙은 그림들
	 * @return 쓸 수 있는 그림별 만화 원본 값(쓸 수 없는 것은 빠짐)
	 */
	public Map<Ref, SourceCharacter> sourceCharacters(long owner, Collection<Ref> refs) {
		Map<Ref, SourceCharacter> result = new HashMap<>();
		Set<String> defaults = ids(refs, DEFAULT);
		if (!defaults.isEmpty()) {
			for (DefaultCharacter c : this.defaults.findAllById(defaults)) {
				result.put(new Ref(DEFAULT, c.getId()),
						new SourceCharacter("default:" + c.getId(), c.getAppearance(), url(c.getImageKey())));
			}
		}
		Set<Long> profiles = userIds(refs);
		if (!profiles.isEmpty()) {
			// 쓸 수 있는 그림과 외모 글이 있고, 카드 주인 본인이거나 친구인 회원만
			List<UserProfile> ready = new ArrayList<>();
			for (UserProfile p : this.profiles.findAllById(profiles)) {
				if (p.getCharacterKey() != null && p.getCharacterAppearance() != null) {
					ready.add(p);
				}
			}
			Set<Long> others = ready.stream().map(UserProfile::getUserId).filter(id -> id != owner).collect(Collectors.toSet());
			Set<Long> friendIds = others.isEmpty() ? Set.of() : new HashSet<>(friendships.findFriendIdsAmong(owner, others));
			for (UserProfile p : ready) {
				if (p.getUserId() == owner || friendIds.contains(p.getUserId())) {
					String id = String.valueOf(p.getUserId());
					result.put(new Ref(PROFILE, id),
							new SourceCharacter("profile:" + id, p.getCharacterAppearance(), url(p.getCharacterKey())));
				}
			}
		}
		return result;
	}

	// ---- 카드 고치기 검사 ----

	/**
	 * 붙일 수 있는 그림인지 본다. 없는 기본 화자, 나도 친구도 아닌 회원 → 400 UNKNOWN_CHARACTER.
	 * 쓸 수 있는 그림이 없는 프로필 캐릭터(처음 만드는 중, 처음 만들기 실패, 지움, 만든 적 없음) → 409 CHARACTER_NOT_READY.
	 *
	 * @param owner 카드 주인 회원 ID
	 * @param ref 붙이려는 그림
	 * @param field UNKNOWN_CHARACTER 오류에 적을 틀린 칸 이름
	 */
	public void checkAttachable(long owner, Ref ref, String field) {
		if (DEFAULT.equals(ref.type())) {
			if (!defaults.existsById(ref.id())) {
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
			if (!friendships.areFriends(owner, userId)) {
				throw unknown(field);
			}
		}
		String key = profiles.findById(userId).map(UserProfile::getCharacterKey).orElse(null);
		if (key == null) {
			throw new ApiException(ErrorCode.CHARACTER_NOT_READY);
		}
	}

	/**
	 * 저장소 키를 10분 서명 주소로 바꾼다. 키가 없으면 null.
	 *
	 * @param key 저장소 키(null 가능)
	 * @return 10분 서명 주소(키가 없으면 null)
	 */
	private String url(String key) {
		return key == null ? null : storage.presignedUrl(key).toString();
	}

	/**
	 * type이 같은 Ref의 id만 모은다.
	 *
	 * @param refs 그림들
	 * @param type 고를 그림 종류
	 * @return 그 종류의 id 모음
	 */
	private static Set<String> ids(Collection<Ref> refs, String type) {
		return refs.stream().filter(r -> type.equals(r.type())).map(Ref::id).collect(Collectors.toSet());
	}

	/**
	 * PROFILE Ref의 회원 ID만 모은다.
	 *
	 * @param refs 그림들
	 * @return 회원 ID 모음(모양이 틀린 id는 빠짐)
	 */
	private static Set<Long> userIds(Collection<Ref> refs) {
		return refs.stream()
			.filter(r -> PROFILE.equals(r.type()))
			.map(r -> parseUserId(r.id()))
			.filter(java.util.Objects::nonNull)
			.collect(Collectors.toSet());
	}

	/**
	 * 글자로 적은 회원 ID를 숫자로 바꾼다. 모양이 틀리면 null.
	 *
	 * @param id 글자로 적은 회원 ID(null 가능)
	 * @return 회원 ID(모양이 틀리면 null)
	 */
	static Long parseUserId(String id) {
		if (id == null || !id.matches("\\d{1,18}")) {
			return null;
		}
		return Long.parseLong(id);
	}

	/**
	 * UNKNOWN_CHARACTER 예외를 만든다.
	 *
	 * @param field 오류에 적을 틀린 칸 이름
	 * @return UNKNOWN_CHARACTER 예외
	 */
	private static ApiException unknown(String field) {
		return new ApiException(ErrorCode.UNKNOWN_CHARACTER, field);
	}

}
