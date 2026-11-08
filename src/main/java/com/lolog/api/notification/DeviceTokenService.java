package com.lolog.api.notification;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

// 폰의 FCM 토큰(device_tokens). 토큰 하나는 한 사용자에게만 있다(uk_device_tokens_token).
@Service
public class DeviceTokenService {

	private final JdbcTemplate jdbc;

	public DeviceTokenService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// 같은 토큰이 다른 사용자에게 있으면 지금 사용자로 옮긴다(같은 폰에서 계정을 바꾼 경우, API 명세).
	// 같은 사용자가 같은 토큰을 다시 보내도 그대로 성공한다(앱은 로그인할 때와 토큰이 바뀔 때마다 보낸다).
	// 한 문장이라 동시에 와도 행이 둘 생기지 않는다.
	public void register(long userId, String token) {
		jdbc.update("""
				insert into device_tokens (user_id, token) values (?, ?)
				on conflict (token) do update set user_id = excluded.user_id, updated_at = current_timestamp
				""", userId, token);
	}

	// 이 사용자의 토큰일 때만 지운다. 없거나 다른 사용자의 토큰이면 아무것도 하지 않는다.
	public void remove(long userId, String token) {
		jdbc.update("delete from device_tokens where token = ? and user_id = ?", token, userId);
	}

	List<String> tokensOf(long userId) {
		return jdbc.queryForList("select token from device_tokens where user_id = ? order by id", String.class, userId);
	}

	void removeInvalid(List<String> tokens) {
		jdbc.batchUpdate("delete from device_tokens where token = ?", tokens.stream().map(t -> new Object[] { t }).toList());
	}

}
