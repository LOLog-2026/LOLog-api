package com.lolog.api.domain.notification.service;

import com.lolog.api.domain.notification.repository.NotificationRepository;
import com.lolog.api.global.push.PushSender;
import com.lolog.api.global.transaction.AfterCommit;
import com.lolog.api.global.util.SafeLog;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
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
	private final NotificationRepository notifications;
	private final ObjectMapper objectMapper;
	// 워커·앱 요청의 응답을 붙잡지 않게 따로 보낸다. 빈으로 두지 않는다(Spring 기본 작업 실행기를 대신하지 않게).
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	/** 푸시 보내기와 알림 목록 저장에 필요한 도구를 받는다. */
	public PushService(DeviceTokenService tokens, PushSender sender, NotificationRepository notifications,
			ObjectMapper objectMapper) {
		this.tokens = tokens;
		this.sender = sender;
		this.notifications = notifications;
		this.objectMapper = objectMapper;
	}

	// 서버가 꺼질 때 보내기용 스레드를 정리한다.
	@Override
	public void destroy() {
		executor.close();
	}

	/**
	 * 녹음에서 카드가 생김.
	 *
	 * @param recordingId 카드가 생긴 녹음 ID
	 * @param cardCount 새로 생긴 카드 수
	 * @return 푸시 값(type 포함, 값은 모두 문자열)
	 */
	public static Map<String, String> cardsCreated(long recordingId, int cardCount) {
		return Map.of("type", "CARDS_CREATED", "recordingId", String.valueOf(recordingId), "cardCount",
				String.valueOf(cardCount));
	}

	/**
	 * 직접 쓴 글의 카드가 생김(texts.md 6장 푸시). 녹음과 같은 type이고 recordingId 대신 textId가 온다.
	 *
	 * @param textId 카드가 생긴 글 ID
	 * @return 푸시 값(type 포함, 값은 모두 문자열)
	 */
	public static Map<String, String> textCardsCreated(long textId) {
		return Map.of("type", "CARDS_CREATED", "textId", String.valueOf(textId), "cardCount", "1");
	}

	/**
	 * 만화가 완성됨.
	 *
	 * @param comicId 완성된 만화 ID
	 * @param cardId 만화의 카드 ID(카드를 지웠으면 null)
	 * @return 푸시 값(type 포함, 값은 모두 문자열)
	 */
	public static Map<String, String> comicDone(long comicId, Long cardId) {
		return comic("COMIC_DONE", comicId, cardId);
	}

	/**
	 * 만화를 만들지 못함.
	 *
	 * @param comicId 실패한 만화 ID
	 * @param cardId 만화의 카드 ID(카드를 지웠으면 null)
	 * @return 푸시 값(type 포함, 값은 모두 문자열)
	 */
	public static Map<String, String> comicFailed(long comicId, Long cardId) {
		return comic("COMIC_FAILED", comicId, cardId);
	}

	/**
	 * 카드 영상이 완성됨·실패함(video.md 2장). 카드를 지웠으면 cardId는 빈 문자열이다.
	 *
	 * @param videoId 완성된 영상 ID
	 * @param cardId 영상의 카드 ID(카드를 지웠으면 null)
	 * @return 푸시 값(type 포함, 값은 모두 문자열)
	 */
	public static Map<String, String> videoDone(long videoId, Long cardId) {
		return video("VIDEO_DONE", videoId, cardId);
	}

	/**
	 * 카드 영상을 만들지 못함.
	 *
	 * @param videoId 실패한 영상 ID
	 * @param cardId 영상의 카드 ID(카드를 지웠으면 null)
	 * @return 푸시 값(type 포함, 값은 모두 문자열)
	 */
	public static Map<String, String> videoFailed(long videoId, Long cardId) {
		return video("VIDEO_FAILED", videoId, cardId);
	}

	/**
	 * 영상 푸시 값을 만든다.
	 *
	 * @param type 푸시 종류(VIDEO_DONE 또는 VIDEO_FAILED)
	 * @param videoId 영상 ID
	 * @param cardId 영상의 카드 ID(null이면 빈 문자열로 보낸다)
	 * @return 푸시 값(type 포함, 값은 모두 문자열)
	 */
	private static Map<String, String> video(String type, long videoId, Long cardId) {
		return Map.of("type", type, "videoId", String.valueOf(videoId), "cardId",
				cardId == null ? "" : String.valueOf(cardId));
	}

	// 내 프로필 캐릭터 그림을 받음·만들지 못함(characters.md 푸시). 나머지 키는 없다.
	public static Map<String, String> profileCharacterReady() {
		return Map.of("type", "PROFILE_CHARACTER_READY");
	}

	// 프로필 캐릭터를 만들지 못함.
	public static Map<String, String> profileCharacterFailed() {
		return Map.of("type", "PROFILE_CHARACTER_FAILED");
	}

	// 내 목소리 미리 듣기를 받음·만들지 못함(characters.md 푸시, 이름은 정함 11-14). 나머지 키는 없다.
	public static Map<String, String> voiceCloneReady() {
		return Map.of("type", "VOICE_CLONE_READY");
	}

	// 내 목소리를 만들지 못함.
	public static Map<String, String> voiceCloneFailed() {
		return Map.of("type", "VOICE_CLONE_FAILED");
	}

	/**
	 * 친구 요청을 받음(friends.md 3장). 문구는 앱이 만들고, 문구에 넣을 보낸 사람 닉네임을 같이 보낸다.
	 *
	 * @param requestId 친구 요청 ID
	 * @param nickname 요청을 보낸 사람 닉네임
	 * @return 푸시 값(type 포함, 값은 모두 문자열)
	 */
	public static Map<String, String> friendRequest(long requestId, String nickname) {
		return Map.of("type", "FRIEND_REQUEST", "requestId", String.valueOf(requestId), "nickname", nickname);
	}

	/**
	 * 방에 초대받음(rooms.md 3장 푸시, 초안). 방을 만들 때 보낸 초대도 같다.
	 *
	 * @param inviteId 초대 ID
	 * @param roomId 초대받은 방 ID
	 * @param roomName 초대받은 방 이름
	 * @return 푸시 값(type 포함, 값은 모두 문자열)
	 */
	public static Map<String, String> roomInvite(long inviteId, long roomId, String roomName) {
		return Map.of("type", "ROOM_INVITE", "inviteId", String.valueOf(inviteId), "roomId", String.valueOf(roomId),
				"roomName", roomName);
	}

	/**
	 * 내가 올린 만화에 다른 사람이 댓글을 담(rooms.md 3장 푸시, 초안). 문구에 댓글 내용은 넣지 않는다.
	 *
	 * @param roomId 댓글이 달린 방 ID
	 * @param postId 댓글이 달린 게시물 ID
	 * @param nickname 댓글을 단 사람 닉네임
	 * @return 푸시 값(type 포함, 값은 모두 문자열)
	 */
	public static Map<String, String> roomComment(long roomId, long postId, String nickname) {
		return Map.of("type", "ROOM_COMMENT", "roomId", String.valueOf(roomId), "postId", String.valueOf(postId),
				"nickname", nickname);
	}

	/**
	 * 방장이 방을 해산함(rooms.md 3장 푸시, 초안). 방장 말고 멤버에게 보낸다.
	 *
	 * @param roomName 해산한 방 이름
	 * @return 푸시 값(type 포함, 값은 모두 문자열)
	 */
	public static Map<String, String> roomDisbanded(String roomName) {
		return Map.of("type", "ROOM_DISBANDED", "roomName", roomName);
	}

	/**
	 * 카드를 지웠으면 cardId는 빈 문자열이다.
	 *
	 * @param type 푸시 종류(COMIC_DONE 또는 COMIC_FAILED)
	 * @param comicId 만화 ID
	 * @param cardId 만화의 카드 ID(null이면 빈 문자열로 보낸다)
	 * @return 푸시 값(type 포함, 값은 모두 문자열)
	 */
	private static Map<String, String> comic(String type, long comicId, Long cardId) {
		return Map.of("type", type, "comicId", String.valueOf(comicId), "cardId",
				cardId == null ? "" : String.valueOf(cardId));
	}

	/**
	 * 트랜잭션 안에서 부르면 커밋된 뒤에 보낸다. 되돌려지면 보내지 않는다(알림 목록 줄도 함께 되돌려진다).
	 *
	 * @param userId 알림을 받을 회원 ID
	 * @param data 보낼 푸시 값(이 클래스의 정적 메서드로 만든 것)
	 */
	public void notify(long userId, Map<String, String> data) {
		record(userId, data);
		if (!sender.enabled()) {
			return;
		}
		AfterCommit.run(() -> executor.execute(() -> deliver(userId, data)));
	}

	/**
	 * 알림 목록에 남긴다. data는 type을 뺀 나머지 값이다.
	 *
	 * @param userId 알림을 받을 회원 ID
	 * @param data 푸시 값(type 포함)
	 */
	private void record(long userId, Map<String, String> data) {
		Map<String, String> rest = new LinkedHashMap<>(data);
		String type = rest.remove("type");
		notifications.insert(userId, type, objectMapper.writeValueAsString(rest));
	}

	/**
	 * 토큰 값은 로그에 남기지 않는다(TR-12).
	 *
	 * @param userId 푸시를 받을 회원 ID
	 * @param data 보낼 푸시 값
	 */
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
