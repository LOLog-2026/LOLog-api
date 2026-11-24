package com.lolog.api.global.error;

import org.springframework.http.HttpStatus;

// 명세의 오류 코드(api-dto.md ErrorResponse). 앱은 code로 갈라 보고, message는 개발자용 설명이다.
// 코드, HTTP 상태, 응답 문구는 여기에서만 정한다. HTTP 상태 순으로, 같은 상태 안에서는 이름순으로 둔다.
public enum ErrorCode {

	// ---- 400 BAD_REQUEST ----
	CANNOT_FRIEND_SELF(HttpStatus.BAD_REQUEST, "나에게는 할 수 없어요."),
	CATEGORY_NOT_SELECTABLE(HttpStatus.BAD_REQUEST, "이 블로그는 카테고리를 고를 수 없어요."),
	DUPLICATE_CHARACTER(HttpStatus.BAD_REQUEST, "한 카드에서 같은 그림을 두 화자에 붙일 수 없어요."),
	INVALID_BLOG(HttpStatus.BAD_REQUEST, "블로그를 읽지 못했습니다."),
	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다."),
	TEXT_TOO_LONG(HttpStatus.BAD_REQUEST, "글이 너무 깁니다."),
	UNKNOWN_CHARACTER(HttpStatus.BAD_REQUEST, "쓸 수 없는 캐릭터예요."),
	UNKNOWN_SPEAKER(HttpStatus.BAD_REQUEST, "카드에 없는 화자입니다."),

	// ---- 401 UNAUTHORIZED ----
	TOKEN_EXPIRED(HttpStatus.UNAUTHORIZED, "토큰이 만료됐습니다."),
	UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "인증이 필요합니다."),

	// ---- 403 FORBIDDEN ----
	FORBIDDEN(HttpStatus.FORBIDDEN, "권한이 없습니다."),
	ROOM_OWNER_ONLY(HttpStatus.FORBIDDEN, "방장만 할 수 있어요."),
	ROOM_POST_NOT_MINE(HttpStatus.FORBIDDEN, "올린 사람만 내릴 수 있어요."),

	// ---- 404 NOT_FOUND ----
	BLOG_NOT_FOUND(HttpStatus.NOT_FOUND, "블로그를 찾을 수 없어요."),
	CARD_NOT_FOUND(HttpStatus.NOT_FOUND, "카드를 찾을 수 없습니다."),
	CHARACTER_NOT_FOUND(HttpStatus.NOT_FOUND, "프로필 캐릭터가 없어요."),
	COMIC_NOT_FOUND(HttpStatus.NOT_FOUND, "만화를 찾을 수 없습니다."),
	FEEDBACK_NOT_FOUND(HttpStatus.NOT_FOUND, "의견을 찾을 수 없어요."),
	FRIEND_CODE_NOT_FOUND(HttpStatus.NOT_FOUND, "친구 코드를 찾을 수 없어요."),
	FRIEND_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "친구 요청을 찾을 수 없어요."),
	NOTICE_NOT_FOUND(HttpStatus.NOT_FOUND, "공지사항을 찾을 수 없어요."),
	NOTIFICATION_NOT_FOUND(HttpStatus.NOT_FOUND, "알림을 찾을 수 없어요."),
	NOT_FOUND(HttpStatus.NOT_FOUND, "없는 주소입니다."),
	RECORDING_NOT_FOUND(HttpStatus.NOT_FOUND, "녹음을 찾을 수 없습니다."),
	ROOM_INVITE_NOT_FOUND(HttpStatus.NOT_FOUND, "초대를 찾을 수 없어요."),
	ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "방을 찾을 수 없어요."),
	ROOM_POST_NOT_FOUND(HttpStatus.NOT_FOUND, "만화를 찾을 수 없어요."),
	TEXT_NOT_FOUND(HttpStatus.NOT_FOUND, "글을 찾을 수 없어요."),
	USER_NOT_FOUND(HttpStatus.NOT_FOUND, "회원을 찾을 수 없어요."),
	VIDEO_NOT_FOUND(HttpStatus.NOT_FOUND, "영상을 찾을 수 없습니다."),
	VOICE_CLONE_NOT_FOUND(HttpStatus.NOT_FOUND, "내 목소리가 없어요."),

	// ---- 405 METHOD_NOT_ALLOWED ----
	METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "이 주소에서 쓸 수 없는 메서드입니다."),

	// ---- 406 NOT_ACCEPTABLE ----
	NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "JSON으로만 답할 수 있습니다."),

	// ---- 409 CONFLICT ----
	ALREADY_FRIENDS(HttpStatus.CONFLICT, "이미 친구예요."),
	BLOG_EXISTS(HttpStatus.CONFLICT, "이미 연결한 블로그예요."),
	CARD_LOCKED(HttpStatus.CONFLICT, "만드는 중인 카드입니다."),
	CHARACTER_IN_PROGRESS(HttpStatus.CONFLICT, "캐릭터를 만드는 중이에요."),
	CHARACTER_LIMIT(HttpStatus.CONFLICT, "오늘은 캐릭터를 더 만들 수 없어요."),
	CHARACTER_NOT_READY(HttpStatus.CONFLICT, "쓸 수 있는 그림이 없는 캐릭터예요."),
	COMIC_IN_PROGRESS(HttpStatus.CONFLICT, "이 카드로 만드는 중인 만화가 있습니다."),
	COMIC_LIMIT(HttpStatus.CONFLICT, "이 카드로는 더 만들 수 없습니다."),
	FRIEND_LIMIT(HttpStatus.CONFLICT, "친구는 100명까지예요."),
	FRIEND_REQUEST_EXISTS(HttpStatus.CONFLICT, "이미 요청을 보냈어요."),
	NEW_OWNER_REQUIRED(HttpStatus.CONFLICT, "나가기 전에 새 방장을 골라 주세요."),
	PART_OUT_OF_ORDER(HttpStatus.CONFLICT, "묶음 번호가 순서에 맞지 않습니다."),
	PROFILE_REQUIRED(HttpStatus.CONFLICT, "닉네임을 먼저 정해 주세요."),
	RECORDING_CLOSED(HttpStatus.CONFLICT, "이미 끝난 녹음입니다."),
	RECORDING_IN_PROGRESS(HttpStatus.CONFLICT, "대화를 녹음하는 중에는 만들 수 없어요."),
	ROOM_LIMIT(HttpStatus.CONFLICT, "방은 20개까지 들어갈 수 있어요."),
	ROOM_MEMBER_LIMIT(HttpStatus.CONFLICT, "나를 포함해 6명까지예요."),
	ROOM_POST_EXISTS(HttpStatus.CONFLICT, "이미 이 방에 올린 만화예요."),
	SPEAKER_HAS_LINES(HttpStatus.CONFLICT, "대사가 남아 있는 화자는 뺄 수 없어요."),
	SPEAKER_LIMIT(HttpStatus.CONFLICT, "화자는 4명까지예요."),
	USER_BLOCKED(HttpStatus.CONFLICT, "차단한 사람이에요. 차단을 풀고 요청해 주세요."),
	VIDEO_IN_PROGRESS(HttpStatus.CONFLICT, "이 카드로 만드는 중인 영상이 있습니다."),
	VIDEO_LIMIT(HttpStatus.CONFLICT, "이 카드로는 영상을 더 만들 수 없습니다."),
	VOICE_CLONE_IN_PROGRESS(HttpStatus.CONFLICT, "내 목소리를 만드는 중이에요."),
	VOICE_CLONE_NOT_READY(HttpStatus.CONFLICT, "아직 고를 수 있는 내 목소리가 없어요."),

	// ---- 413 PAYLOAD_TOO_LARGE ----
	PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "파일이 너무 큽니다."),

	// ---- 415 UNSUPPORTED_MEDIA_TYPE ----
	UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "이 주소가 받지 않는 Content-Type입니다."),

	// ---- 429 TOO_MANY_REQUESTS ----
	TOO_MANY_REQUESTS(HttpStatus.TOO_MANY_REQUESTS, "잠시 뒤에 다시 시도해 주세요."),

	// ---- 500 INTERNAL_SERVER_ERROR ----
	INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다."),

	// ---- 503 SERVICE_UNAVAILABLE ----
	EMBEDDING_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "소리 검색 모델을 쓸 수 없어요."),
	VOICE_CLONE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "아직 내 목소리를 만들 수 없어요.");

	private final HttpStatus status;

	private final String message;

	ErrorCode(HttpStatus status, String message) {
		this.status = status;
		this.message = message;
	}

	public HttpStatus status() {
		return status;
	}

	// 응답의 message
	public String message() {
		return message;
	}

}
