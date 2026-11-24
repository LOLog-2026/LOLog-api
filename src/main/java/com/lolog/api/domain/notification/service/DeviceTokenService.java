package com.lolog.api.domain.notification.service;

import com.lolog.api.domain.notification.repository.DeviceTokenRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 폰의 FCM 토큰(device_tokens). 토큰 하나는 한 사용자에게만 있다(uk_device_tokens_token).
@Service
public class DeviceTokenService {

	private final DeviceTokenRepository repository;

	// 리포지토리를 받는다.
	public DeviceTokenService(DeviceTokenRepository repository) {
		this.repository = repository;
	}

	// 같은 토큰이 다른 사용자에게 있으면 지금 사용자로 옮긴다(같은 폰에서 계정을 바꾼 경우, API 명세).
	// 같은 사용자가 같은 토큰을 다시 보내도 그대로 성공한다(앱은 로그인할 때와 토큰이 바뀔 때마다 보낸다).
	@Transactional
	public void register(long userId, String token) {
		repository.upsert(userId, token);
	}

	// 이 사용자의 토큰일 때만 지운다. 없거나 다른 사용자의 토큰이면 아무것도 하지 않는다.
	@Transactional
	public void remove(long userId, String token) {
		repository.deleteMine(token, userId);
	}

	// 사용자의 모든 폰 토큰을 돌려준다.
	@Transactional(readOnly = true)
	List<String> tokensOf(long userId) {
		return repository.findTokensByUserId(userId);
	}

	// FCM이 무효라고 한 토큰을 지운다.
	@Transactional
	void removeInvalid(List<String> tokens) {
		repository.deleteByTokens(tokens);
	}

	// 사용자의 토큰을 모두 지운다(탈퇴).
	@Transactional
	public void removeAll(long userId) {
		repository.deleteAllByUserId(userId);
	}

}
