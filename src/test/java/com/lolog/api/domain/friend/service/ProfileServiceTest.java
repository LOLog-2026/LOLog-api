package com.lolog.api.domain.friend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lolog.api.domain.character.service.CharacterViews;
import com.lolog.api.domain.friend.dto.FriendDtos.ProfileResponse;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("ProfileService")
@ExtendWith(MockitoExtension.class)
class ProfileServiceTest {

	private static final long USER = 7L;

	@Mock
	UserProfileRepository profiles;
	@Mock
	CharacterViews characters;

	@InjectMocks
	ProfileService service;

	@Test
	@DisplayName("닉네임이 없으면 친구 코드를 응답에 보이지 않는다")
	void 닉네임이_없으면_친구_코드를_숨긴다() {
		// given
		when(profiles.existsById(USER)).thenReturn(true);
		when(profiles.findById(USER)).thenReturn(Optional.of(profile(null, "ABCD2345")));

		// when
		ProfileResponse response = service.get(USER);

		// then
		assertThat(response.nickname()).isNull();
		assertThat(response.friendCode()).isNull();
	}

	@Test
	@DisplayName("닉네임이 있으면 친구 코드를 보인다")
	void 닉네임이_있으면_친구_코드를_보인다() {
		// given
		when(profiles.existsById(USER)).thenReturn(true);
		when(profiles.findById(USER)).thenReturn(Optional.of(profile("철수", "ABCD2345")));

		// when
		ProfileResponse response = service.get(USER);

		// then
		assertThat(response.friendCode()).isEqualTo("ABCD2345");
	}

	@Test
	@DisplayName("코드가 겹쳐 프로필이 생기지 않으면 새 코드로 다시 넣는다")
	void 코드가_겹치면_다시_뽑는다() {
		// given
		when(profiles.existsById(USER)).thenReturn(false, false, true);

		// when
		service.ensure(USER);

		// then
		verify(profiles, times(3)).insertIfAbsent(eq(USER), anyString());
	}

	@Test
	@DisplayName("5번 넣어도 프로필이 없으면 IllegalStateException이다")
	void 다섯_번_실패하면_예외다() {
		// given
		when(profiles.existsById(USER)).thenReturn(false);

		// when & then
		assertThatThrownBy(() -> service.ensure(USER)).isInstanceOf(IllegalStateException.class);
		verify(profiles, times(5)).insertIfAbsent(eq(USER), anyString());
	}

	@Test
	@DisplayName("친구 코드는 헷갈리는 글자를 뺀 8자다")
	void 친구_코드는_8자다() {
		// when
		String code = service.newCode();

		// then
		assertThat(code).hasSize(8).matches("[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}");
	}

	@Test
	@DisplayName("닉네임이 공백뿐이거나 10자를 넘으면 프로필을 만들지 않고 nickname이 틀린 것이다")
	void 잘못된_닉네임은_INVALID다() {
		// when & then
		assertInvalidNickname(null);
		assertInvalidNickname("   ");
		assertInvalidNickname("가".repeat(11));
		verify(profiles, never()).insertIfAbsent(anyLong(), anyString());
	}

	@Test
	@DisplayName("닉네임은 앞뒤 공백을 빼고 저장한다")
	void 닉네임은_공백을_빼고_저장한다() {
		// given
		UserProfile profile = profile(null, "ABCD2345");
		when(profiles.existsById(USER)).thenReturn(true);
		when(profiles.findById(USER)).thenReturn(Optional.of(profile));

		// when
		ProfileResponse response = service.updateNickname(USER, "  철수 ");

		// then
		assertThat(profile.getNickname()).isEqualTo("철수");
		assertThat(response.nickname()).isEqualTo("철수");
		assertThat(response.friendCode()).isEqualTo("ABCD2345");
	}

	private void assertInvalidNickname(String nickname) {
		assertThatThrownBy(() -> service.updateNickname(USER, nickname)).isInstanceOfSatisfying(ApiException.class,
				e -> {
					assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
					assertThat(e.getField()).isEqualTo("nickname");
				});
	}

	private static UserProfile profile(String nickname, String code) {
		UserProfile profile = new UserProfile();
		ReflectionTestUtils.setField(profile, "userId", USER);
		ReflectionTestUtils.setField(profile, "nickname", nickname);
		ReflectionTestUtils.setField(profile, "friendCode", code);
		return profile;
	}

}
