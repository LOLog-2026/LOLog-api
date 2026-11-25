package com.lolog.api.domain.notification.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.notification.service.DeviceTokenService;
import com.lolog.api.support.AppControllerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("DeviceTokenController")
@WebMvcTest(DeviceTokenController.class)
class DeviceTokenControllerTest extends AppControllerTestSupport {

	@MockitoBean
	DeviceTokenService tokens;

	@Test
	@DisplayName("토큰 없이 등록하면 401 UNAUTHORIZED이고 서비스를 부르지 않는다")
	void 토큰_없이_등록하면_401이다() throws Exception {
		// when & then
		mvc.perform(put("/api/devices/fcm-token").contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"abc\"}"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
			.andExpect(jsonPath("$.field").isEmpty());

		verify(tokens, never()).register(anyLong(), anyString());
	}

	@Test
	@DisplayName("FCM 토큰을 등록하면 204이고 내 회원 ID와 토큰을 넘긴다")
	void 등록하면_204이고_회원_ID와_토큰을_넘긴다() throws Exception {
		// when & then
		mvc.perform(put("/api/devices/fcm-token").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"fcm-abc\"}"))
			.andExpect(status().isNoContent());

		verify(tokens).register(USER_ID, "fcm-abc");
	}

	@Test
	@DisplayName("FCM 토큰이 공백뿐이면 400 INVALID_REQUEST이고 field는 token이다")
	void 토큰이_공백뿐이면_400이다() throws Exception {
		// when & then
		mvc.perform(put("/api/devices/fcm-token").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"  \"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andExpect(jsonPath("$.field").value("token"));

		verify(tokens, never()).register(anyLong(), anyString());
	}

	@Test
	@DisplayName("FCM 토큰이 512자면 받고 513자면 400 INVALID_REQUEST다")
	void 토큰_길이는_512자까지다() throws Exception {
		// when & then
		mvc.perform(put("/api/devices/fcm-token").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + "a".repeat(512) + "\"}"))
			.andExpect(status().isNoContent());
		mvc.perform(put("/api/devices/fcm-token").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"" + "a".repeat(513) + "\"}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("token"));
	}

	@Test
	@DisplayName("FCM 토큰을 지우면 204이고 본문의 토큰을 넘긴다")
	void 지우면_204이고_토큰을_넘긴다() throws Exception {
		// when & then
		mvc.perform(delete("/api/devices/fcm-token").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"token\":\"fcm-abc\"}"))
			.andExpect(status().isNoContent());

		verify(tokens).remove(USER_ID, "fcm-abc");
	}

	@Test
	@DisplayName("지울 때 본문에 토큰이 없으면 400 INVALID_REQUEST이고 field는 token이다")
	void 지울_때_토큰이_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(delete("/api/devices/fcm-token").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("token"));

		verify(tokens, never()).remove(anyLong(), anyString());
	}

}
