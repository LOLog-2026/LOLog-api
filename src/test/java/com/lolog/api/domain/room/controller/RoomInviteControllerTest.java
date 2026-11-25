package com.lolog.api.domain.room.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.room.service.RoomService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.support.AppControllerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("RoomInviteController")
@WebMvcTest(RoomInviteController.class)
class RoomInviteControllerTest extends AppControllerTestSupport {

	@MockitoBean
	RoomService rooms;

	@Test
	@DisplayName("토큰 없이 받은 초대를 부르면 401 UNAUTHORIZED다")
	void 토큰_없이_부르면_401이다() throws Exception {
		// when & then
		mvc.perform(get("/api/room-invites")).andExpect(status().isUnauthorized());

		verifyNoInteractions(rooms);
	}

	@Test
	@DisplayName("받은 초대 목록은 내 회원 ID를 넘긴다")
	void 받은_초대_목록() throws Exception {
		// when & then
		mvc.perform(get("/api/room-invites").header(AUTH, user())).andExpect(status().isOk());

		verify(rooms).receivedInvites(USER_ID);
	}

	@Test
	@DisplayName("초대를 받으면 200이고 초대 ID를 넘긴다")
	void 초대를_받으면_200이다() throws Exception {
		// when & then
		mvc.perform(post("/api/room-invites/8/accept").header(AUTH, user())).andExpect(status().isOk());

		verify(rooms).acceptInvite(USER_ID, 8L);
	}

	@Test
	@DisplayName("초대를 거절하면 204다")
	void 초대를_거절하면_204다() throws Exception {
		// when & then
		mvc.perform(delete("/api/room-invites/8").header(AUTH, user())).andExpect(status().isNoContent());

		verify(rooms).declineInvite(USER_ID, 8L);
	}

	@Test
	@DisplayName("초대 ID가 숫자가 아니면 400 INVALID_REQUEST이고 field는 inviteId다")
	void 초대_ID가_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(post("/api/room-invites/abc/accept").header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("inviteId"));

		verifyNoInteractions(rooms);
	}

	@Test
	@DisplayName("서비스가 ROOM_INVITE_NOT_FOUND를 던지면 404로 답한다")
	void 없는_초대는_404다() throws Exception {
		// given
		when(rooms.acceptInvite(USER_ID, 8L)).thenThrow(new ApiException(ErrorCode.ROOM_INVITE_NOT_FOUND));

		// when & then
		mvc.perform(post("/api/room-invites/8/accept").header(AUTH, user()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ROOM_INVITE_NOT_FOUND"));
	}

}
