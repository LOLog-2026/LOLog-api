package com.lolog.api.notification;

import java.util.List;
import java.util.Map;

// 폰에 푸시를 보내는 쪽. 서버는 FCM으로 보내고(FcmPushSender), 키가 없으면 보내지 않는다(DISABLED).
public interface PushSender {

	// 키가 없어 꺼 둔 상태. 아무것도 보내지 않는다.
	PushSender DISABLED = new PushSender() {
		@Override
		public boolean enabled() {
			return false;
		}

		@Override
		public List<String> send(List<String> tokens, Map<String, String> data) {
			return List.of();
		}
	};

	default boolean enabled() {
		return true;
	}

	// tokens에 data 메시지를 보내고, FCM이 무효라고 답한 토큰을 돌려준다.
	// 요청 전체가 실패하면(네트워크, FCM 오류) 예외를 던진다.
	List<String> send(List<String> tokens, Map<String, String> data) throws Exception;

}
