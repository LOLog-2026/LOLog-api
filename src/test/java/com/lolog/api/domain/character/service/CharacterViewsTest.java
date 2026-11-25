package com.lolog.api.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.character.entity.DefaultCharacter;
import com.lolog.api.domain.character.repository.DefaultCharacterRepository;
import com.lolog.api.domain.character.service.CharacterViews.Ref;
import com.lolog.api.domain.friend.repository.FriendshipRepository;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("CharacterViews")
class CharacterViewsTest {

	private static final long OWNER = 1L;
	private static final long FRIEND = 2L;
	private static final long STRANGER = 3L;

	@Mock
	private UserProfileRepository profiles;

	@Mock
	private FriendshipRepository friendships;

	@Mock
	private ImageStorage storage;

	@Mock
	private DefaultCharacterRepository defaults;

	@InjectMocks
	private CharacterViews views;

	@BeforeEach
	void setUp() {
		// 서명 주소는 키를 붙인 가짜 주소로 준다.
		lenient().when(storage.presignedUrl(anyString()))
			.thenAnswer(inv -> URI.create("https://s3.test/" + inv.getArgument(0)).toURL());
	}

	// ---- profileResponse ----

	@Test
	@DisplayName("프로필이 없거나 캐릭터를 만든 적이 없으면 null이다")
	void 캐릭터가_없으면_null() {
		// given
		given(profiles.findById(OWNER)).willReturn(Optional.empty());
		given(profiles.findById(FRIEND)).willReturn(Optional.of(profile(FRIEND, null, null, null, null)));

		// when & then
		assertThat(views.profileResponse(OWNER)).isNull();
		assertThat(views.profileResponse(FRIEND)).isNull();
	}

	@Test
	@DisplayName("상태·줄인 그림 주소·고른 목소리·내 목소리를 담는다")
	void 프로필_캐릭터_응답을_만든다() {
		// given
		UserProfile p = profile(OWNER, UserProfile.CHARACTER_READY, "c.png", "c-thumb.png", "appearance");
		OffsetDateTime updatedAt = OffsetDateTime.now();
		ReflectionTestUtils.setField(p, "characterUpdatedAt", updatedAt);
		ReflectionTestUtils.setField(p, "characterVoice", UserProfile.SELF_VOICE);
		ReflectionTestUtils.setField(p, "voiceCloneStatus", UserProfile.CHARACTER_READY);
		ReflectionTestUtils.setField(p, "voiceClonePreviewKey", "preview.wav");
		ReflectionTestUtils.setField(p, "voiceCloneShareFriends", true);
		given(profiles.findById(OWNER)).willReturn(Optional.of(p));

		// when
		ProfileCharacterService.ProfileCharacterResponse response = views.profileResponse(OWNER);

		// then
		assertThat(response.status()).isEqualTo(UserProfile.CHARACTER_READY);
		assertThat(response.thumbnailUrl()).isEqualTo("https://s3.test/c-thumb.png");
		assertThat(response.updatedAt()).isEqualTo(updatedAt);
		assertThat(response.voice()).isEqualTo(UserProfile.SELF_VOICE);
		assertThat(response.voiceClone())
			.isEqualTo(new VoiceCloneService.VoiceCloneView(UserProfile.CHARACTER_READY, "https://s3.test/preview.wav", null, true));
	}

	@Test
	@DisplayName("처음 만드는 중이면 그림 주소가 null이고 내 목소리를 만든 적이 없으면 voiceClone이 null이다")
	void 그림과_목소리가_없으면_null_칸() {
		// given
		given(profiles.findById(OWNER))
			.willReturn(Optional.of(profile(OWNER, UserProfile.CHARACTER_PROCESSING, null, null, null)));

		// when
		ProfileCharacterService.ProfileCharacterResponse response = views.profileResponse(OWNER);

		// then
		assertThat(response.status()).isEqualTo(UserProfile.CHARACTER_PROCESSING);
		assertThat(response.thumbnailUrl()).isNull();
		assertThat(response.voiceClone()).isNull();
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("내 목소리가 실패면 미리 듣기 주소 없이 이유를 담는다")
	void 실패한_내_목소리는_이유를_담는다() {
		// given
		UserProfile p = profile(OWNER, UserProfile.CHARACTER_READY, "c.png", null, "a");
		ReflectionTestUtils.setField(p, "voiceCloneStatus", UserProfile.CHARACTER_FAILED);
		ReflectionTestUtils.setField(p, "voiceCloneFailReason", "잡음");
		given(profiles.findById(OWNER)).willReturn(Optional.of(p));

		// when
		VoiceCloneService.VoiceCloneView clone = views.profileResponse(OWNER).voiceClone();

		// then
		assertThat(clone).isEqualTo(new VoiceCloneService.VoiceCloneView(UserProfile.CHARACTER_FAILED, null, "잡음", false));
	}

	// ---- friendCharacter ----

	@Test
	@DisplayName("친구 그림 키가 없으면 null이고 있으면 서명 주소를 준다")
	void 친구_그림() {
		// when & then
		assertThat(views.friendCharacter(null)).isNull();
		assertThat(views.friendCharacter("t.png")).isEqualTo(new CharacterViews.FriendCharacter("https://s3.test/t.png"));
	}

	// ---- speakerCharacters ----

	@Test
	@DisplayName("기본 화자와 프로필 캐릭터를 응답 모양으로 읽고 모양이 틀린 회원 id는 뺀다")
	@SuppressWarnings("unchecked")
	void 카드_화자_그림을_읽는다() {
		// given
		given(defaults.findAllById(Set.of("young-f-01"))).willReturn(List.of(defaultCharacter("young-f-01")));
		UserProfile friend = profile(FRIEND, UserProfile.CHARACTER_READY, "f.png", "f-thumb.png", "a");
		ReflectionTestUtils.setField(friend, "nickname", "친구");
		given(profiles.findAllById(Set.of(FRIEND))).willReturn(List.of(friend));
		List<Ref> refs = List.of(new Ref(CharacterViews.DEFAULT, "young-f-01"), new Ref(CharacterViews.PROFILE, "2"),
				new Ref(CharacterViews.PROFILE, "abc"));

		// when
		Map<Ref, CharacterViews.SpeakerCharacter> result = views.speakerCharacters(refs);

		// then
		assertThat(result).containsOnlyKeys(new Ref(CharacterViews.DEFAULT, "young-f-01"), new Ref(CharacterViews.PROFILE, "2"));
		assertThat(result.get(new Ref(CharacterViews.DEFAULT, "young-f-01"))).isEqualTo(new CharacterViews.SpeakerCharacter(
				CharacterViews.DEFAULT, "young-f-01", "label-young-f-01", "https://s3.test/defaults/young-f-01-thumb.webp"));
		assertThat(result.get(new Ref(CharacterViews.PROFILE, "2")))
			.isEqualTo(new CharacterViews.SpeakerCharacter(CharacterViews.PROFILE, "2", "친구", "https://s3.test/f-thumb.png"));
		ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
		verify(profiles).findAllById(ids.capture());
		assertThat(ids.getValue()).containsExactly(FRIEND);
	}

	@Test
	@DisplayName("화자에 붙은 그림이 없으면 리포지토리를 부르지 않는다")
	void 그림이_없으면_읽지_않는다() {
		// when
		Map<Ref, CharacterViews.SpeakerCharacter> result = views.speakerCharacters(List.of());

		// then
		assertThat(result).isEmpty();
		verifyNoInteractions(defaults, profiles);
	}

	// ---- sourceCharacters ----

	@Test
	@DisplayName("만화 원본에는 카드 주인 본인과 친구의 쓸 수 있는 그림만 담는다")
	void 만화_원본은_본인과_친구만() {
		// given
		UserProfile owner = profile(OWNER, UserProfile.CHARACTER_READY, "o.png", "o-t.png", "주인 생김새");
		UserProfile friend = profile(FRIEND, UserProfile.CHARACTER_READY, "f.png", "f-t.png", "친구 생김새");
		UserProfile stranger = profile(STRANGER, UserProfile.CHARACTER_READY, "s.png", "s-t.png", "남 생김새");
		given(profiles.findAllById(Set.of(OWNER, FRIEND, STRANGER))).willReturn(List.of(owner, friend, stranger));
		given(friendships.findFriendIdsAmong(OWNER, Set.of(FRIEND, STRANGER))).willReturn(List.of(FRIEND));
		List<Ref> refs = List.of(new Ref(CharacterViews.PROFILE, "1"), new Ref(CharacterViews.PROFILE, "2"),
				new Ref(CharacterViews.PROFILE, "3"));

		// when
		Map<Ref, CharacterViews.SourceCharacter> result = views.sourceCharacters(OWNER, refs);

		// then
		assertThat(result).containsOnlyKeys(new Ref(CharacterViews.PROFILE, "1"), new Ref(CharacterViews.PROFILE, "2"));
		assertThat(result.get(new Ref(CharacterViews.PROFILE, "2")))
			.isEqualTo(new CharacterViews.SourceCharacter("profile:2", "친구 생김새", "https://s3.test/f.png"));
		assertThat(result.get(new Ref(CharacterViews.PROFILE, "1")).ref()).isEqualTo("profile:1");
	}

	@Test
	@DisplayName("그림이나 외모 글이 없는 프로필은 빼고, 본인만 남으면 친구를 묻지 않는다")
	void 쓸_수_없는_그림은_뺀다() {
		// given
		UserProfile owner = profile(OWNER, UserProfile.CHARACTER_READY, "o.png", "o-t.png", "주인 생김새");
		UserProfile noAppearance = profile(FRIEND, UserProfile.CHARACTER_PROCESSING, "f.png", "f-t.png", null);
		given(profiles.findAllById(Set.of(OWNER, FRIEND))).willReturn(List.of(owner, noAppearance));
		List<Ref> refs = List.of(new Ref(CharacterViews.PROFILE, "1"), new Ref(CharacterViews.PROFILE, "2"));

		// when
		Map<Ref, CharacterViews.SourceCharacter> result = views.sourceCharacters(OWNER, refs);

		// then
		assertThat(result).containsOnlyKeys(new Ref(CharacterViews.PROFILE, "1"));
		verifyNoInteractions(friendships);
	}

	@Test
	@DisplayName("기본 화자는 default:id와 원본 그림 주소로 담는다")
	void 기본_화자_원본() {
		// given
		given(defaults.findAllById(Set.of("young-f-01"))).willReturn(List.of(defaultCharacter("young-f-01")));

		// when
		Map<Ref, CharacterViews.SourceCharacter> result = views.sourceCharacters(OWNER,
				List.of(new Ref(CharacterViews.DEFAULT, "young-f-01")));

		// then
		assertThat(result.get(new Ref(CharacterViews.DEFAULT, "young-f-01"))).isEqualTo(new CharacterViews.SourceCharacter(
				"default:young-f-01", "appearance-young-f-01", "https://s3.test/defaults/young-f-01.png"));
		verifyNoInteractions(profiles, friendships);
	}

	// ---- checkAttachable ----

	@Test
	@DisplayName("있는 기본 화자는 붙일 수 있다")
	void 있는_기본_화자는_붙인다() {
		// given
		given(defaults.existsById("young-f-01")).willReturn(true);

		// when & then
		assertThatCode(() -> views.checkAttachable(OWNER, new Ref(CharacterViews.DEFAULT, "young-f-01"), "speakers[0].character"))
			.doesNotThrowAnyException();
	}

	@Test
	@DisplayName("없는 기본 화자는 받은 field로 UNKNOWN_CHARACTER다")
	void 없는_기본_화자는_UNKNOWN_CHARACTER() {
		// given
		given(defaults.existsById("nope")).willReturn(false);

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> views.checkAttachable(OWNER, new Ref(CharacterViews.DEFAULT, "nope"), "speakers[1].character"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNKNOWN_CHARACTER);
		assertThat(e.getField()).isEqualTo("speakers[1].character");
	}

	@Test
	@DisplayName("모양이 틀린 회원 id는 UNKNOWN_CHARACTER이고 친구를 묻지 않는다")
	void 틀린_회원_id는_UNKNOWN_CHARACTER() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> views.checkAttachable(OWNER, new Ref(CharacterViews.PROFILE, "-1"), "f"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNKNOWN_CHARACTER);
		verifyNoInteractions(friendships, profiles);
	}

	@Test
	@DisplayName("친구가 아닌 회원은 UNKNOWN_CHARACTER이고 프로필을 읽지 않는다")
	void 친구가_아니면_UNKNOWN_CHARACTER() {
		// given
		given(friendships.areFriends(OWNER, STRANGER)).willReturn(false);

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> views.checkAttachable(OWNER, new Ref(CharacterViews.PROFILE, "3"), "f"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNKNOWN_CHARACTER);
		assertThat(e.getField()).isEqualTo("f");
		verifyNoInteractions(profiles);
	}

	@Test
	@DisplayName("친구인데 쓸 수 있는 그림이 없으면 CHARACTER_NOT_READY다")
	void 친구_그림이_없으면_CHARACTER_NOT_READY() {
		// given
		given(friendships.areFriends(OWNER, FRIEND)).willReturn(true);
		given(profiles.findById(FRIEND))
			.willReturn(Optional.of(profile(FRIEND, UserProfile.CHARACTER_PROCESSING, null, null, null)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> views.checkAttachable(OWNER, new Ref(CharacterViews.PROFILE, "2"), "f"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHARACTER_NOT_READY);
		assertThat(e.getField()).isNull();
	}

	@Test
	@DisplayName("다시 만드는 중이어도 예전 그림이 있는 본인 캐릭터는 붙일 수 있고 친구를 묻지 않는다")
	void 본인_그림이_있으면_붙인다() {
		// given
		given(profiles.findById(OWNER))
			.willReturn(Optional.of(profile(OWNER, UserProfile.CHARACTER_PROCESSING, "o.png", "o-t.png", "a")));

		// when & then
		assertThatCode(() -> views.checkAttachable(OWNER, new Ref(CharacterViews.PROFILE, "1"), "f"))
			.doesNotThrowAnyException();
		verifyNoInteractions(friendships);
	}

	@Test
	@DisplayName("프로필이 없는 본인은 CHARACTER_NOT_READY다")
	void 본인_프로필이_없으면_CHARACTER_NOT_READY() {
		// given
		given(profiles.findById(OWNER)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> views.checkAttachable(OWNER, new Ref(CharacterViews.PROFILE, "1"), "f"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHARACTER_NOT_READY);
	}

	// ---- parseUserId ----

	@Test
	@DisplayName("1~18자리 숫자만 회원 ID로 읽는다")
	void 회원_ID_모양() {
		// when & then
		assertThat(CharacterViews.parseUserId("1")).isEqualTo(1L);
		assertThat(CharacterViews.parseUserId("9".repeat(18))).isEqualTo(999_999_999_999_999_999L);
		assertThat(CharacterViews.parseUserId("1".repeat(19))).isNull();
		assertThat(CharacterViews.parseUserId("")).isNull();
		assertThat(CharacterViews.parseUserId(" 1")).isNull();
		assertThat(CharacterViews.parseUserId(null)).isNull();
	}

	// ---- 도움 ----

	// 회원 프로필
	private static UserProfile profile(long userId, String status, String key, String thumbKey, String appearance) {
		UserProfile p = new UserProfile();
		ReflectionTestUtils.setField(p, "userId", userId);
		ReflectionTestUtils.setField(p, "characterStatus", status);
		ReflectionTestUtils.setField(p, "characterKey", key);
		ReflectionTestUtils.setField(p, "characterThumbKey", thumbKey);
		ReflectionTestUtils.setField(p, "characterAppearance", appearance);
		return p;
	}

	// 기본 화자
	private static DefaultCharacter defaultCharacter(String id) {
		DefaultCharacter c = new DefaultCharacter();
		ReflectionTestUtils.setField(c, "id", id);
		ReflectionTestUtils.setField(c, "label", "label-" + id);
		ReflectionTestUtils.setField(c, "appearance", "appearance-" + id);
		ReflectionTestUtils.setField(c, "imageKey", "defaults/" + id + ".png");
		ReflectionTestUtils.setField(c, "thumbKey", "defaults/" + id + "-thumb.webp");
		return c;
	}

}
