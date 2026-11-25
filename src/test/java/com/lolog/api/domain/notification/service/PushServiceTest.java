package com.lolog.api.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.notification.repository.NotificationRepository;
import com.lolog.api.global.push.PushSender;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("PushService")
class PushServiceTest {

	private static final long USER_ID = 7L;

	@Mock
	private DeviceTokenService tokens;

	@Mock
	private PushSender sender;

	@Mock
	private NotificationRepository notifications;

	private final ObjectMapper objectMapper = JsonMapper.builder().build();

	private PushService service;

	@BeforeEach
	void setUp() {
		service = new PushService(tokens, sender, notifications, objectMapper);
	}

	@AfterEach
	void tearDown() {
		service.destroy();
	}

	// ---- notify ----

	@Test
	@DisplayName("알림 목록에는 type을 따로 넣고 data에는 type을 뺀 나머지 값만 JSON으로 넣는다")
	void 알림_목록에_type을_빼고_남긴다() {
		// given
		given(sender.enabled()).willReturn(false);

		// when
		service.notify(USER_ID, PushService.roomInvite(1L, 2L, "우리 방"));

		// then
		ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
		verify(notifications).insert(eq(USER_ID), eq("ROOM_INVITE"), json.capture());
		Map<String, Object> data = objectMapper.readValue(json.getValue(), new TypeReference<Map<String, Object>>() {
		});
		assertThat(data).containsExactlyInAnyOrderEntriesOf(Map.of("inviteId", "1", "roomId", "2", "roomName", "우리 방"));
	}

	@Test
	@DisplayName("푸시가 꺼져 있으면 알림 목록에만 남기고 토큰을 읽지 않는다")
	void 푸시가_꺼져_있으면_목록에만_남긴다() {
		// given
		given(sender.enabled()).willReturn(false);

		// when
		service.notify(USER_ID, PushService.profileCharacterReady());

		// then
		verify(notifications).insert(USER_ID, "PROFILE_CHARACTER_READY", "{}");
		verifyNoInteractions(tokens);
	}

	@Test
	@DisplayName("트랜잭션 밖에서 부르면 바로 따로 보낸다")
	void 트랜잭션_밖이면_바로_보낸다() throws Exception {
		// given
		Map<String, String> data = PushService.comicDone(3L, 4L);
		given(sender.enabled()).willReturn(true);
		given(tokens.tokensOf(USER_ID)).willReturn(List.of("t1"));
		given(sender.send(List.of("t1"), data)).willReturn(List.of());

		// when
		service.notify(USER_ID, data);

		// then
		verify(sender, timeout(2000)).send(List.of("t1"), data);
	}

	// ---- deliver ----

	@Test
	@DisplayName("토큰이 없으면 보내지 않는다")
	void 토큰이_없으면_보내지_않는다() throws Exception {
		// given
		given(tokens.tokensOf(USER_ID)).willReturn(List.of());

		// when
		service.deliver(USER_ID, PushService.voiceCloneReady());

		// then
		verify(sender, never()).send(anyList(), any());
		verify(tokens, never()).removeInvalid(anyList());
	}

	@Test
	@DisplayName("FCM이 무효라고 한 토큰만 지운다")
	void 무효_토큰만_지운다() throws Exception {
		// given
		Map<String, String> data = PushService.cardsCreated(1L, 2);
		given(tokens.tokensOf(USER_ID)).willReturn(List.of("t1", "t2"));
		given(sender.send(List.of("t1", "t2"), data)).willReturn(List.of("t2"));

		// when
		service.deliver(USER_ID, data);

		// then
		verify(tokens).removeInvalid(List.of("t2"));
	}

	@Test
	@DisplayName("무효 토큰이 없으면 지우지 않는다")
	void 무효_토큰이_없으면_지우지_않는다() throws Exception {
		// given
		Map<String, String> data = PushService.cardsCreated(1L, 2);
		given(tokens.tokensOf(USER_ID)).willReturn(List.of("t1"));
		given(sender.send(List.of("t1"), data)).willReturn(List.of());

		// when
		service.deliver(USER_ID, data);

		// then
		verify(tokens, never()).removeInvalid(anyList());
	}

	@Test
	@DisplayName("보내다 실패해도 예외를 밖으로 던지지 않고 토큰을 지우지 않는다")
	void 보내다_실패해도_예외를_던지지_않는다() throws Exception {
		// given
		Map<String, String> data = PushService.cardsCreated(1L, 2);
		given(tokens.tokensOf(USER_ID)).willReturn(List.of("t1"));
		given(sender.send(List.of("t1"), data)).willThrow(new IllegalStateException("FCM 오류"));

		// when & then
		assertThatCode(() -> service.deliver(USER_ID, data)).doesNotThrowAnyException();
		verify(tokens, never()).removeInvalid(anyList());
	}

	// ---- 푸시 값 ----

	@Test
	@DisplayName("만화·영상 푸시는 카드가 지워졌으면 cardId를 빈 문자열로 보낸다")
	void 카드가_없으면_cardId는_빈_문자열() {
		// when & then
		assertThat(PushService.comicFailed(3L, null)).containsEntry("cardId", "").containsEntry("comicId", "3");
		assertThat(PushService.videoDone(5L, null)).containsEntry("cardId", "").containsEntry("type", "VIDEO_DONE");
		assertThat(PushService.videoFailed(5L, 9L)).containsEntry("cardId", "9");
	}

	@Test
	@DisplayName("글 카드 푸시는 CARDS_CREATED이고 textId와 cardCount 1을 보낸다")
	void 글_카드_푸시() {
		// when & then
		assertThat(PushService.textCardsCreated(8L)).containsExactlyInAnyOrderEntriesOf(
				Map.of("type", "CARDS_CREATED", "textId", "8", "cardCount", "1"));
	}

	@Test
	@DisplayName("방 댓글·해산·친구 요청 푸시 값은 모두 문자열이다")
	void 방_친구_푸시_값() {
		// when & then
		assertThat(PushService.roomComment(1L, 2L, "철수")).containsExactlyInAnyOrderEntriesOf(
				Map.of("type", "ROOM_COMMENT", "roomId", "1", "postId", "2", "nickname", "철수"));
		assertThat(PushService.roomDisbanded("방")).containsExactlyInAnyOrderEntriesOf(
				Map.of("type", "ROOM_DISBANDED", "roomName", "방"));
		assertThat(PushService.friendRequest(4L, "영희")).containsExactlyInAnyOrderEntriesOf(
				Map.of("type", "FRIEND_REQUEST", "requestId", "4", "nickname", "영희"));
	}

}
