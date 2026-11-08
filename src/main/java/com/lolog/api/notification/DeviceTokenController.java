package com.lolog.api.notification;

import com.lolog.api.auth.CurrentUserProvider;
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

	// API DTO 명세 FcmTokenRequest. 512자는 device_tokens.token 길이다.
	public record FcmTokenRequest(@NotBlank @Size(max = 512) String token) {
	}

	private final DeviceTokenService tokens;
	private final CurrentUserProvider currentUser;

	public DeviceTokenController(DeviceTokenService tokens, CurrentUserProvider currentUser) {
		this.tokens = tokens;
		this.currentUser = currentUser;
	}

	@PutMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void register(@Valid @RequestBody FcmTokenRequest request) {
		tokens.register(currentUser.currentUserId(), request.token());
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void remove(@Valid @RequestBody FcmTokenRequest request) {
		tokens.remove(currentUser.currentUserId(), request.token());
	}

}
