package com.lolog.api.friend;

import com.lolog.api.common.ApiException;
import com.lolog.api.friend.FriendDtos.ProfileResponse;
import java.security.SecureRandom;
import org.springframework.jdbc.core.JdbcTemplate;
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

	private final JdbcTemplate jdbc;
	private final SecureRandom random = new SecureRandom();

	public ProfileService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional
	public ProfileResponse get(long userId) {
		ensure(userId);
		return read(userId);
	}

	@Transactional
	public ProfileResponse updateNickname(long userId, String nickname) {
		String trimmed = nickname == null ? "" : nickname.strip();
		int length = trimmed.codePointCount(0, trimmed.length());
		if (length < 1 || length > NICKNAME_MAX) {
			throw ApiException.invalid("nickname", "닉네임은 1~10자로 입력해 주세요.");
		}
		ensure(userId);
		jdbc.update("update user_profiles set nickname = ?, updated_at = current_timestamp where user_id = ?", trimmed,
				userId);
		return read(userId);
	}

	// 프로필이 없으면 만든다. 같은 사용자가 동시에 불러도 줄은 하나다(user_profiles_pk).
	// 친구 코드가 겹쳐 넣지 못했으면 코드를 다시 뽑는다.
	void ensure(long userId) {
		for (int i = 0; i < CODE_TRIES; i++) {
			jdbc.update("insert into user_profiles (user_id, friend_code) values (?, ?) on conflict do nothing", userId,
					newCode());
			if (exists(userId)) {
				return;
			}
		}
		throw new IllegalStateException("친구 코드를 발급하지 못했습니다.");
	}

	private boolean exists(long userId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists (select 1 from user_profiles where user_id = ?)", Boolean.class, userId));
	}

	private ProfileResponse read(long userId) {
		return jdbc.queryForObject("select nickname, friend_code from user_profiles where user_id = ?",
				(rs, n) -> {
					String nickname = rs.getString("nickname");
					return new ProfileResponse(nickname, nickname == null ? null : rs.getString("friend_code"), null);
				}, userId);
	}

	String newCode() {
		StringBuilder code = new StringBuilder(CODE_LENGTH);
		for (int i = 0; i < CODE_LENGTH; i++) {
			code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
		}
		return code.toString();
	}

}
