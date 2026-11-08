package com.lolog.api.common;

// 로그에 대화 글이 남지 않게 예외 메시지를 뺀다(TR-12). DB·Redis 오류 메시지에는 저장하려던 값이 들어갈 수 있다.
// 예외 종류와 스택(어디서 났는지)만 남긴다.
public final class SafeLog {

	private static final int MAX_CAUSE_DEPTH = 10;

	private SafeLog() {
	}

	public static RuntimeException withoutMessages(Throwable e) {
		return copy(e, 0);
	}

	private static RuntimeException copy(Throwable e, int depth) {
		Throwable cause = e.getCause();
		RuntimeException copy = new RuntimeException(e.getClass().getName(),
				cause != null && depth < MAX_CAUSE_DEPTH ? copy(cause, depth + 1) : null);
		copy.setStackTrace(e.getStackTrace());
		return copy;
	}

}
