package com.lolog.api.notification;

import com.lolog.api.common.SafeLog;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 카드·만화 결과를 사용자의 모든 폰에 알린다(FR-NOTI, API DTO 명세 3장).
// 값은 모두 문자열이고 대사는 넣지 않는다(FR-NOTI-03). 알림 문구는 앱이 만든다.
// 저장이 확정된 뒤(커밋 뒤)에 따로 보내고, 실패하면 다시 보내지 않고 로그만 남긴다. 앱은 푸시가 없어도 조회로 상태를 안다.
@Service
public class PushService implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(PushService.class);

	private final DeviceTokenService tokens;
	private final PushSender sender;
	// 워커·앱 요청의 응답을 붙잡지 않게 따로 보낸다. 빈으로 두지 않는다(Spring 기본 작업 실행기를 대신하지 않게).
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public PushService(DeviceTokenService tokens, PushSender sender) {
		this.tokens = tokens;
		this.sender = sender;
	}

	@Override
	public void destroy() {
		executor.close();
	}

	public static Map<String, String> cardsCreated(long recordingId, int cardCount) {
		return Map.of("type", "CARDS_CREATED", "recordingId", String.valueOf(recordingId), "cardCount",
				String.valueOf(cardCount));
	}

	public static Map<String, String> comicDone(long comicId, Long cardId) {
		return comic("COMIC_DONE", comicId, cardId);
	}

	public static Map<String, String> comicFailed(long comicId, Long cardId) {
		return comic("COMIC_FAILED", comicId, cardId);
	}

	// 카드를 지웠으면 cardId는 빈 문자열이다.
	private static Map<String, String> comic(String type, long comicId, Long cardId) {
		return Map.of("type", type, "comicId", String.valueOf(comicId), "cardId",
				cardId == null ? "" : String.valueOf(cardId));
	}

	// 트랜잭션 안에서 부르면 커밋된 뒤에 보낸다. 되돌려지면 보내지 않는다.
	public void notify(long userId, Map<String, String> data) {
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
