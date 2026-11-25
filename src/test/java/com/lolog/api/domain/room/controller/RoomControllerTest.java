package com.lolog.api.domain.room.controller;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.room.service.RoomService;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.support.AppControllerTestSupport;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("RoomController")
@WebMvcTest(RoomController.class)
class RoomControllerTest extends AppControllerTestSupport {

	@MockitoBean
	RoomService rooms;

	@Test
	@DisplayName("토큰 없이 방 목록을 부르면 401 UNAUTHORIZED다")
	void 토큰_없이_부르면_401이다() throws Exception {
		// when & then
		mvc.perform(get("/api/rooms")).andExpect(status().isUnauthorized());

		verifyNoInteractions(rooms);
	}

	@Test
	@DisplayName("방을 만들면 201이고 이름과 멤버 목록을 넘긴다")
	void 방을_만들면_201이다() throws Exception {
		// when & then
		mvc.perform(post("/api/rooms").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"우리 방\",\"memberIds\":[2,3]}"))
			.andExpect(status().isCreated());

		verify(rooms).create(USER_ID, "우리 방", List.of(2L, 3L));
	}

	@Test
	@DisplayName("방 목록·방 하나·이름 바꾸기·해산은 경로의 방 ID를 넘긴다")
	void 방_기본_주소() throws Exception {
		// when & then
		mvc.perform(get("/api/rooms").header(AUTH, user())).andExpect(status().isOk());
		mvc.perform(get("/api/rooms/4").header(AUTH, user())).andExpect(status().isOk());
		mvc.perform(patch("/api/rooms/4").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"새 이름\"}")).andExpect(status().isOk());
		mvc.perform(delete("/api/rooms/4").header(AUTH, user())).andExpect(status().isNoContent());

		verify(rooms).rooms(USER_ID);
		verify(rooms).room(USER_ID, 4L);
		verify(rooms).rename(USER_ID, 4L, "새 이름");
		verify(rooms).disband(USER_ID, 4L);
	}

	@Test
	@DisplayName("members/me는 나가기이고 newOwnerId가 없으면 null로 넘긴다")
	void members_me는_나가기다() throws Exception {
		// when & then
		mvc.perform(delete("/api/rooms/4/members/me").header(AUTH, user())).andExpect(status().isNoContent());

		verify(rooms).leave(USER_ID, 4L, null);
		verify(rooms, never()).kick(anyLong(), anyLong(), anyLong());
	}

	@Test
	@DisplayName("members/me에 newOwnerId를 주면 그 값을 넘긴다")
	void 나가기에_새_방장을_넘긴다() throws Exception {
		// when & then
		mvc.perform(delete("/api/rooms/4/members/me").param("newOwnerId", "9").header(AUTH, user()))
			.andExpect(status().isNoContent());

		verify(rooms).leave(USER_ID, 4L, 9L);
	}

	@Test
	@DisplayName("members/{userId}는 내보내기다")
	void members_userId는_내보내기다() throws Exception {
		// when & then
		mvc.perform(delete("/api/rooms/4/members/9").header(AUTH, user())).andExpect(status().isNoContent());

		verify(rooms).kick(USER_ID, 4L, 9L);
		verify(rooms, never()).leave(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());
	}

	@Test
	@DisplayName("members/ 뒤가 me도 숫자도 아니면 400 INVALID_REQUEST이고 field는 userId다")
	void members_뒤가_틀리면_400이다() throws Exception {
		// when & then
		mvc.perform(delete("/api/rooms/4/members/you").header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("userId"));

		verifyNoInteractions(rooms);
	}

	@Test
	@DisplayName("방장이 아니면 서비스의 403 ROOM_OWNER_ONLY를 그대로 답한다")
	void 방장이_아니면_403이다() throws Exception {
		// given
		doThrow(new ApiException(ErrorCode.ROOM_OWNER_ONLY)).when(rooms).kick(USER_ID, 4L, 9L);

		// when & then
		mvc.perform(delete("/api/rooms/4/members/9").header(AUTH, user()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("ROOM_OWNER_ONLY"));
	}

	@Test
	@DisplayName("초대하면 201이고 userIds를 넘기며, 초대 취소는 204다")
	void 초대와_취소() throws Exception {
		// when & then
		mvc.perform(post("/api/rooms/4/invites").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"userIds\":[5]}")).andExpect(status().isCreated());
		mvc.perform(delete("/api/rooms/4/invites/8").header(AUTH, user())).andExpect(status().isNoContent());

		verify(rooms).invite(USER_ID, 4L, List.of(5L));
		verify(rooms).cancelInvite(USER_ID, 4L, 8L);
	}

	@Test
	@DisplayName("만화 올리기·목록·내리기는 방 ID와 만화·글 ID를 넘긴다")
	void 만화_올리기와_내리기() throws Exception {
		// when & then
		mvc.perform(get("/api/rooms/4/posts").header(AUTH, user())).andExpect(status().isOk());
		mvc.perform(post("/api/rooms/4/posts").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"comicId\":11}")).andExpect(status().isCreated());
		mvc.perform(delete("/api/rooms/4/posts/12").header(AUTH, user())).andExpect(status().isNoContent());

		verify(rooms).posts(USER_ID, 4L);
		verify(rooms).postComic(USER_ID, 4L, 11L);
		verify(rooms).removePost(USER_ID, 4L, 12L);
	}

	@Test
	@DisplayName("좋아요는 PUT, 좋아요 취소는 DELETE이고 둘 다 200이다")
	void 좋아요와_취소() throws Exception {
		// when & then
		mvc.perform(put("/api/rooms/4/posts/12/like").header(AUTH, user())).andExpect(status().isOk());
		mvc.perform(delete("/api/rooms/4/posts/12/like").header(AUTH, user())).andExpect(status().isOk());

		verify(rooms).like(USER_ID, 4L, 12L);
		verify(rooms).unlike(USER_ID, 4L, 12L);
	}

	@Test
	@DisplayName("메시지 목록은 cursor를, 댓글은 text와 postId를 넘긴다")
	void 메시지와_댓글() throws Exception {
		// when & then
		mvc.perform(get("/api/rooms/4/messages").param("cursor", "30").header(AUTH, user())).andExpect(status().isOk());
		mvc.perform(post("/api/rooms/4/messages").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"text\":\"ㅋㅋ\",\"postId\":12}")).andExpect(status().isCreated());

		verify(rooms).messages(USER_ID, 4L, "30");
		verify(rooms).comment(USER_ID, 4L, "ㅋㅋ", 12L);
	}

}
