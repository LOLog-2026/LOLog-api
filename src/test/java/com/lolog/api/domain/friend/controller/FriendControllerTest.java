package com.lolog.api.domain.friend.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.lolog.api.domain.friend.service.FriendService;
import com.lolog.api.domain.friend.service.ProfileService;
import com.lolog.api.support.AppControllerTestSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@DisplayName("FriendController")
@WebMvcTest(FriendController.class)
class FriendControllerTest extends AppControllerTestSupport {

	@MockitoBean
	ProfileService profiles;

	@MockitoBean
	FriendService friends;

	@Test
	@DisplayName("토큰 없이 내 프로필을 부르면 401 UNAUTHORIZED다")
	void 토큰_없이_부르면_401이다() throws Exception {
		// when & then
		mvc.perform(get("/api/me/profile")).andExpect(status().isUnauthorized());

		verifyNoInteractions(profiles);
	}

	@Test
	@DisplayName("내 프로필은 내 회원 ID로 읽는다")
	void 내_프로필은_내_ID로_읽는다() throws Exception {
		// when & then
		mvc.perform(get("/api/me/profile").header(AUTH, user())).andExpect(status().isOk());

		verify(profiles).get(USER_ID);
	}

	@Test
	@DisplayName("닉네임을 고치면 본문의 nickname을 넘긴다")
	void 닉네임을_고치면_nickname을_넘긴다() throws Exception {
		// when & then
		mvc.perform(patch("/api/me/profile").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"nickname\":\"웃음왕\"}"))
			.andExpect(status().isOk());

		verify(profiles).updateNickname(USER_ID, "웃음왕");
	}

	@Test
	@DisplayName("친구 코드로 요청하면 201이고 userId는 null로 넘긴다")
	void 친구_코드로_요청하면_201이다() throws Exception {
		// when & then
		mvc.perform(post("/api/friend-requests").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"friendCode\":\"ABC123\"}"))
			.andExpect(status().isCreated());

		verify(friends).sendRequest(USER_ID, "ABC123", null);
	}

	@Test
	@DisplayName("회원 ID로 요청하면 friendCode는 null로 넘긴다")
	void 회원_ID로_요청하면_코드는_null이다() throws Exception {
		// when & then
		mvc.perform(post("/api/friend-requests").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\":5}"))
			.andExpect(status().isCreated());

		verify(friends).sendRequest(USER_ID, null, 5L);
	}

	@Test
	@DisplayName("친구 요청 목록은 direction을 그대로 넘긴다")
	void 요청_목록은_direction을_넘긴다() throws Exception {
		// when & then
		mvc.perform(get("/api/friend-requests").param("direction", "received").header(AUTH, user()))
			.andExpect(status().isOk());

		verify(friends).requests(USER_ID, "received");
	}

	@Test
	@DisplayName("요청을 받으면 200이고 요청 ID를 넘긴다")
	void 요청을_받으면_200이다() throws Exception {
		// when & then
		mvc.perform(post("/api/friend-requests/4/accept").header(AUTH, user())).andExpect(status().isOk());

		verify(friends).acceptRequest(USER_ID, 4L);
	}

	@Test
	@DisplayName("요청을 지우면 204다")
	void 요청을_지우면_204다() throws Exception {
		// when & then
		mvc.perform(delete("/api/friend-requests/4").header(AUTH, user())).andExpect(status().isNoContent());

		verify(friends).deleteRequest(USER_ID, 4L);
	}

	@Test
	@DisplayName("친구 목록과 친구 끊기는 내 회원 ID를 넘긴다")
	void 친구_목록과_끊기() throws Exception {
		// when & then
		mvc.perform(get("/api/friends").header(AUTH, user())).andExpect(status().isOk());
		mvc.perform(delete("/api/friends/5").header(AUTH, user())).andExpect(status().isNoContent());

		verify(friends).friends(USER_ID);
		verify(friends).unfriend(USER_ID, 5L);
	}

	@Test
	@DisplayName("차단할 때 hideInRooms를 빼면 false로 넘긴다")
	void 차단할_때_hideInRooms를_빼면_false다() throws Exception {
		// when & then
		mvc.perform(post("/api/blocks").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\":5}"))
			.andExpect(status().isNoContent());

		verify(friends).block(USER_ID, 5L, false);
	}

	@Test
	@DisplayName("차단할 때 hideInRooms가 true면 true로 넘긴다")
	void 차단할_때_hideInRooms가_true면_true다() throws Exception {
		// when & then
		mvc.perform(post("/api/blocks").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"userId\":5,\"hideInRooms\":true}"))
			.andExpect(status().isNoContent());

		verify(friends).block(USER_ID, 5L, true);
	}

	@Test
	@DisplayName("차단할 때 userId가 없으면 400 INVALID_REQUEST이고 field는 userId다")
	void 차단할_때_userId가_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(post("/api/blocks").header(AUTH, user()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("userId"));

		verifyNoInteractions(friends);
	}

	@Test
	@DisplayName("차단 설정을 고칠 때 hideInRooms가 없으면 400 INVALID_REQUEST이고 field는 hideInRooms다")
	void 차단_설정에_hideInRooms가_없으면_400이다() throws Exception {
		// when & then
		mvc.perform(patch("/api/blocks/5").header(AUTH, user()).contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("hideInRooms"));

		verifyNoInteractions(friends);
	}

	@Test
	@DisplayName("차단 설정을 고치면 204이고 경로의 회원 ID와 값을 넘긴다")
	void 차단_설정을_고치면_204다() throws Exception {
		// when & then
		mvc.perform(patch("/api/blocks/5").header(AUTH, user())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"hideInRooms\":false}"))
			.andExpect(status().isNoContent());

		verify(friends).updateBlock(USER_ID, 5L, false);
	}

	@Test
	@DisplayName("차단 목록과 차단 풀기는 내 회원 ID를 넘긴다")
	void 차단_목록과_풀기() throws Exception {
		// when & then
		mvc.perform(get("/api/blocks").header(AUTH, user())).andExpect(status().isOk());
		mvc.perform(delete("/api/blocks/5").header(AUTH, user())).andExpect(status().isNoContent());

		verify(friends).blocks(USER_ID);
		verify(friends).unblock(USER_ID, 5L);
	}

	@Test
	@DisplayName("차단 풀기의 회원 ID가 숫자가 아니면 400 INVALID_REQUEST이고 field는 userId다")
	void 차단_풀기_ID가_숫자가_아니면_400이다() throws Exception {
		// when & then
		mvc.perform(delete("/api/blocks/abc").header(AUTH, user()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.field").value("userId"));
	}

}
