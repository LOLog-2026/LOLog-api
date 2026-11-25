package com.lolog.api.domain.notification.controller;

import com.lolog.api.domain.notification.service.DeviceTokenService;
import com.lolog.api.global.auth.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// 이 폰의 FCM 토큰 등록과 지우기(로그아웃할 때). 둘 다 204.
@RestController
@RequestMapping("/api/devices/fcm-token")
public class DeviceTokenController {

	/**
	 * API DTO 명세 FcmTokenRequest. 512자는 device_tokens.token 길이다.
	 *
	 * @param token 이 폰의 FCM 토큰(비어 있으면 안 됨, 512자 이하)
	 */
	public record FcmTokenRequest(@NotBlank @Size(max = 512) String token) {
	}

	private final DeviceTokenService tokens;
	private final CurrentUserProvider currentUser;

	/** 토큰 서비스와 현재 사용자 정보를 받는다. */
	public DeviceTokenController(DeviceTokenService tokens, CurrentUserProvider currentUser) {
		this.tokens = tokens;
		this.currentUser = currentUser;
	}

	/**
	 * 이 폰의 FCM 토큰을 등록한다.
	 *
	 * @param request 등록할 토큰을 담은 요청 본문
	 */
	@PutMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void register(@Valid @RequestBody FcmTokenRequest request) {
		tokens.register(currentUser.currentUserId(), request.token());
	}

	/**
	 * 이 폰의 FCM 토큰을 지운다.
	 *
	 * @param request 지울 토큰을 담은 요청 본문
	 */
	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@Valid @RequestBody FcmTokenRequest request) {
		tokens.remove(currentUser.currentUserId(), request.token());
	}

}
