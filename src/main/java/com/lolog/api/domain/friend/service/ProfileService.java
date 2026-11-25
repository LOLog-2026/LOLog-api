package com.lolog.api.domain.friend.service;

import com.lolog.api.domain.character.service.CharacterViews;
import com.lolog.api.domain.friend.dto.FriendDtos.ProfileResponse;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.global.error.ApiException;
import java.security.SecureRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 회원 프로필(user_profiles): 닉네임과 친구 코드. 처음 볼 때(GET) 또는 닉네임을 정할 때(PATCH) 만든다(friends.md).
// 친구 코드는 프로필을 만들 때 발급하지만, 닉네임을 정하기 전에는 응답에 보이지 않고(null) 코드로 찾아지지도 않는다.
// 닉네임 없는 사람이 요청을 받고도 수락하지 못하는 흐름을 막는다(#40에서 정함).
@Service
public class ProfileService {

	// 대문자와 숫자에서 헷갈리는 글자(0·O·1·I·L)를 뺐다.
	static final String CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
	static final int CODE_LENGTH = 8;
	static final int NICKNAME_MAX = 10;
	// 31^8(약 8500억) 가지라 겹칠 일이 거의 없다. 그래도 겹치면 다시 뽑는다.
	private static final int CODE_TRIES = 5;

	private final UserProfileRepository profiles;
	private final CharacterViews characters;
	private final SecureRandom random = new SecureRandom();

	/** 프로필 리포지토리와 캐릭터 조회 도구를 받는다. */
	public ProfileService(UserProfileRepository profiles, CharacterViews characters) {
		this.profiles = profiles;
		this.characters = characters;
	}

	/**
	 * 내 프로필을 돌려준다. 없으면 먼저 만든다.
	 *
	 * @param userId 요청한 회원 ID
	 * @return 내 프로필
	 */
	@Transactional
	public ProfileResponse get(long userId) {
		ensure(userId);
		return read(userId);
	}

	/**
	 * 닉네임을 확인해 바꾸고 프로필을 돌려준다.
	 *
	 * @param userId 요청한 회원 ID
	 * @param nickname 바꿀 닉네임(null 가능, 앞뒤 공백을 뺀 뒤 1~10자)
	 * @return 바꾼 내 프로필
	 */
	@Transactional
	public ProfileResponse updateNickname(long userId, String nickname) {
		String trimmed = nickname == null ? "" : nickname.strip();
		int length = trimmed.codePointCount(0, trimmed.length());
		if (length < 1 || length > NICKNAME_MAX) {
			throw ApiException.invalid("nickname");
		}
		ensure(userId);
		profiles.findById(userId).orElseThrow().changeNickname(trimmed);
		return read(userId);
	}

	/**
	 * 프로필이 없으면 만든다. 같은 사용자가 동시에 불러도 줄은 하나다(user_profiles_pk).
	 * 친구 코드가 겹쳐 넣지 못했으면 코드를 다시 뽑는다.
	 *
	 * @param userId 프로필을 만들 회원 ID
	 */
	@Transactional
	public void ensure(long userId) {
		for (int i = 0; i < CODE_TRIES; i++) {
			profiles.insertIfAbsent(userId, newCode());
			if (profiles.existsById(userId)) {
				return;
			}
		}
		throw new IllegalStateException("친구 코드를 발급하지 못했습니다.");
	}

	/**
	 * 프로필을 읽어 응답 모양으로 바꾼다.
	 *
	 * @param userId 읽을 회원 ID(프로필이 있어야 함)
	 * @return 프로필 응답
	 */
	private ProfileResponse read(long userId) {
		UserProfile profile = profiles.findById(userId).orElseThrow();
		String nickname = profile.getNickname();
		return new ProfileResponse(userId, nickname, nickname == null ? null : profile.getFriendCode(),
				characters.profileResponse(userId));
	}

	// 친구 코드 하나를 무작위로 뽑는다.
	String newCode() {
		StringBuilder code = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++) {
			code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
		}
		return code.toString();
	}

}
