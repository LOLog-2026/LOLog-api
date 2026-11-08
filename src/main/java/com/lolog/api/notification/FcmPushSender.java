package com.lolog.api.notification;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.BatchResponse;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.MulticastMessage;
import com.google.firebase.messaging.SendResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.DisposableBean;

// FCM으로 data 메시지를 보낸다(API DTO 명세 3장). notification 블록 없이 보내고 알림은 앱이 직접 띄운다.
// Android 우선순위는 high다. normal이면 절전(Doze) 중에 늦게 받을 수 있다.
class FcmPushSender implements PushSender, DisposableBean {

	// 한 번에 보낼 수 있는 토큰 수(FCM 제한)
	private static final int BATCH = 500;

	// 이 답이 오면 그 토큰으로는 다시 보낼 수 없으므로 지운다(기능 명세 FR-NOTI 예외).
	private static final Set<MessagingErrorCode> INVALID = Set.of(MessagingErrorCode.UNREGISTERED,
			MessagingErrorCode.INVALID_ARGUMENT, MessagingErrorCode.SENDER_ID_MISMATCH);

	private static final AndroidConfig ANDROID = AndroidConfig.builder()
		.setPriority(AndroidConfig.Priority.HIGH)
		.build();

	private final FirebaseApp app;
	private final FirebaseMessaging messaging;

	FcmPushSender(FirebaseApp app) {
		this.app = app;
		this.messaging = FirebaseMessaging.getInstance(app);
	}

	@Override
	public List<String> send(List<String> tokens, Map<String, String> data) throws Exception {
		List<String> invalid = new ArrayList<>();
		for (int from = 0; from < tokens.size(); from += BATCH) {
			List<String> batch = tokens.subList(from, Math.min(from + BATCH, tokens.size()));
			MulticastMessage message = MulticastMessage.builder()
				.addAllTokens(batch)
				.putAllData(data)
				.setAndroidConfig(ANDROID)
				.build();
			BatchResponse response = messaging.sendEachForMulticast(message);
			List<SendResponse> results = response.getResponses();
			for (int i = 0; i < results.size(); i++) {
				SendResponse result = results.get(i);
				if (!result.isSuccessful() && result.getException() != null
						&& INVALID.contains(result.getException().getMessagingErrorCode())) {
					invalid.add(batch.get(i));
				}
			}
		}
		return invalid;
	}

	@Override
	public void destroy() {
		app.delete();
	}

}
