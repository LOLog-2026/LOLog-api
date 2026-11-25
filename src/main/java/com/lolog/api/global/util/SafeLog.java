package com.lolog.api.global.util;

// 로그에 대화 글이 남지 않게 예외 메시지를 뺀다(TR-12). DB·Redis 오류 메시지에는 저장하려던 값이 들어갈 수 있다.
// 예외 종류와 스택(어디서 났는지)만 남긴다.
public final class SafeLog {

	private static final int MAX_CAUSE_DEPTH = 10;

	private SafeLog() {
	}

	/**
	 * 메시지를 뺀 예외 복사본을 돌려준다.
	 *
	 * @param e 메시지를 뺄 예외(원인 예외까지)
	 * @return 예외 종류 이름만 메시지로 가진 복사본(스택은 그대로)
	 */
	public static RuntimeException withoutMessages(Throwable e) {
		return copy(e, 0);
	}

	/**
	 * 원인 예외까지 메시지 없이 하나씩 복사한다.
	 *
	 * @param e 복사할 예외
	 * @param depth 지금까지 따라간 원인 단계(10단계까지만 복사)
	 * @return 메시지를 뺀 복사본
	 */
	private static RuntimeException copy(Throwable e, int depth) {
		Throwable cause = e.getCause();
		RuntimeException copy = new RuntimeException(e.getClass().getName(),
				cause != null && depth < MAX_CAUSE_DEPTH ? copy(cause, depth + 1) : null);
		copy.setStackTrace(e.getStackTrace());
		return copy;
	}

}
