package com.lolog.api.notification;

import com.lolog.api.common.SafeLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

// 카드·만화 결과, 친구 요청, 방 소식을 사용자의 모든 폰에 알린다(FR-NOTI, API DTO 명세 3장).
// 값은 모두 문자열이고 대사는 넣지 않는다(FR-NOTI-03). 알림 문구는 앱이 만든다.
// 저장이 확정된 뒤(커밋 뒤)에 따로 보내고, 실패하면 다시 보내지 않고 로그만 남긴다. 앱은 푸시가 없어도 조회로 상태를 안다.
// 보낼 때마다 같은 내용을 알림 목록(notifications)에 한 줄 남긴다. 푸시가 꺼져 있거나 가지 않아도 남는다(inbox.md 1장).
@Service
public class PushService implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(PushService.class);

	private final DeviceTokenService tokens;
	private final PushSender sender;
	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;
	// 워커·앱 요청의 응답을 붙잡지 않게 따로 보낸다. 빈으로 두지 않는다(Spring 기본 작업 실행기를 대신하지 않게).
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public PushService(DeviceTokenService tokens, PushSender sender, JdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.tokens = tokens;
		this.sender = sender;
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	@Override
	public void destroy() {
		executor.close();
	}

	public static Map<String, String> cardsCreated(long recordingId, int cardCount) {
		return Map.of("type", "CARDS_CREATED", "recordingId", String.valueOf(recordingId), "cardCount",
				String.valueOf(cardCount));
	}

	// 직접 쓴 글의 카드가 생김(texts.md 6장 푸시). 녹음과 같은 type이고 recordingId 대신 textId가 온다.
	public static Map<String, String> textCardsCreated(long textId) {
		return Map.of("type", "CARDS_CREATED", "textId", String.valueOf(textId), "cardCount", "1");
	}

	public static Map<String, String> comicDone(long comicId, Long cardId) {
		return comic("COMIC_DONE", comicId, cardId);
	}

	public static Map<String, String> comicFailed(long comicId, Long cardId) {
		return comic("COMIC_FAILED", comicId, cardId);
	}

	// 카드 영상이 완성됨·실패함(video.md 2장). 카드를 지웠으면 cardId는 빈 문자열이다.
	public static Map<String, String> videoDone(long videoId, Long cardId) {
		return video("VIDEO_DONE", videoId, cardId);
	}

	public static Map<String, String> videoFailed(long videoId, Long cardId) {
		return video("VIDEO_FAILED", videoId, cardId);
	}

	private static Map<String, String> video(String type, long videoId, Long cardId) {
		return Map.of("type", type, "videoId", String.valueOf(videoId), "cardId",
				cardId == null ? "" : String.valueOf(cardId));
	}

	// 내 프로필 캐릭터 그림을 받음·만들지 못함(characters.md 푸시). 나머지 키는 없다.
	public static Map<String, String> profileCharacterReady() {
		return Map.of("type", "PROFILE_CHARACTER_READY");
	}

	public static Map<String, String> profileCharacterFailed() {
		return Map.of("type", "PROFILE_CHARACTER_FAILED");
	}

	// 내 목소리 미리 듣기를 받음·만들지 못함(characters.md 푸시, 이름은 정함 11-14). 나머지 키는 없다.
	public static Map<String, String> voiceCloneReady() {
		return Map.of("type", "VOICE_CLONE_READY");
	}

	public static Map<String, String> voiceCloneFailed() {
		return Map.of("type", "VOICE_CLONE_FAILED");
	}

	// 친구 요청을 받음(friends.md 3장). 문구는 앱이 만들고, 문구에 넣을 보낸 사람 닉네임을 같이 보낸다.
	public static Map<String, String> friendRequest(long requestId, String nickname) {
		return Map.of("type", "FRIEND_REQUEST", "requestId", String.valueOf(requestId), "nickname", nickname);
	}

	// 방에 초대받음(rooms.md 3장 푸시, 초안). 방을 만들 때 보낸 초대도 같다.
	public static Map<String, String> roomInvite(long inviteId, long roomId, String roomName) {
		return Map.of("type", "ROOM_INVITE", "inviteId", String.valueOf(inviteId), "roomId", String.valueOf(roomId),
				"roomName", roomName);
	}

	// 내가 올린 만화에 다른 사람이 댓글을 담(rooms.md 3장 푸시, 초안). 문구에 댓글 내용은 넣지 않는다.
	public static Map<String, String> roomComment(long roomId, long postId, String nickname) {
		return Map.of("type", "ROOM_COMMENT", "roomId", String.valueOf(roomId), "postId", String.valueOf(postId),
				"nickname", nickname);
	}

	// 방장이 방을 해산함(rooms.md 3장 푸시, 초안). 방장 말고 멤버에게 보낸다.
	public static Map<String, String> roomDisbanded(String roomName) {
		return Map.of("type", "ROOM_DISBANDED", "roomName", roomName);
	}

	// 카드를 지웠으면 cardId는 빈 문자열이다.
	private static Map<String, String> comic(String type, long comicId, Long cardId) {
		return Map.of("type", type, "comicId", String.valueOf(comicId), "cardId",
				cardId == null ? "" : String.valueOf(cardId));
	}

	// 트랜잭션 안에서 부르면 커밋된 뒤에 보낸다. 되돌려지면 보내지 않는다(알림 목록 줄도 함께 되돌려진다).
	public void notify(long userId, Map<String, String> data) {
		record(userId, data);
		if (!sender.enabled()) {
			return;
		}
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					executor.execute(() -> deliver(userId, data));
				}
			});
		}
		else {
			executor.execute(() -> deliver(userId, data));
		}
	}

	// 알림 목록에 남긴다. data는 type을 뺀 나머지 값이다.
	private void record(long userId, Map<String, String> data) {
		Map<String, String> rest = new LinkedHashMap<>(data);
		String type = rest.remove("type");
		jdbc.update("insert into notifications (user_id, type, data, created_at) values (?, ?, ?::jsonb, clock_timestamp())",
				userId, type, objectMapper.writeValueAsString(rest));
	}

	// 토큰 값은 로그에 남기지 않는다(TR-12).
	void deliver(long userId, Map<String, String> data) {
		String type = data.get("type");
		try {
			List<String> targets = tokens.tokensOf(userId);
			if (targets.isEmpty()) {
				return;
			}
			List<String> invalid = sender.send(targets, data);
			if (!invalid.isEmpty()) {
				tokens.removeInvalid(invalid);
			}
			log.info("푸시 {}: 폰 {}대, 무효 토큰 {}개 지움", type, targets.size(), invalid.size());
		}
		catch (Exception e) {
			log.warn("푸시 {}를 보내지 못했습니다.", type, SafeLog.withoutMessages(e));
		}
	}

}
