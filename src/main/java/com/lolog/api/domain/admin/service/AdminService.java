package com.lolog.api.domain.admin.service;

import com.lolog.api.domain.admin.dto.AdminDtos.AdminUser;
import com.lolog.api.domain.admin.dto.AdminDtos.FeedbackItem;
import com.lolog.api.domain.admin.dto.AdminDtos.FeedbackPage;
import com.lolog.api.domain.admin.dto.AdminDtos.FeedbackRequest;
import com.lolog.api.domain.admin.dto.AdminDtos.Notice;
import com.lolog.api.domain.admin.entity.Feedback;
import com.lolog.api.domain.admin.repository.FeedbackRepository;
import com.lolog.api.domain.inbox.repository.NoticeRepository;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.domain.user.repository.UserSuspensionRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.web.PageRequestParams;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 공지사항 관리, 오류 신고·의견, 회원 조회, 정지 표시(LOLog-docs admin.md, 초안).
// 관리자인지는 컨트롤러가 토큰의 role로 확인한다. 정지·탈퇴시키기와 계정 목록은 인증 서비스에 있다.
@Service
public class AdminService {

	static final int TITLE_MAX = 100;
	static final int FEEDBACK_MAX = 1000;
	static final int FEEDBACK_PER_DAY = 10;
	static final int PAGE_SIZE = 20;

	private final NoticeRepository notices;
	private final FeedbackRepository feedback;
	private final UserProfileRepository profiles;
	private final UserSuspensionRepository suspensions;

	public AdminService(NoticeRepository notices, FeedbackRepository feedback, UserProfileRepository profiles,
			UserSuspensionRepository suspensions) {
		this.notices = notices;
		this.feedback = feedback;
		this.profiles = profiles;
		this.suspensions = suspensions;
	}

	// ---- 공지사항 ----

	// 공지사항을 만든다.
	@Transactional
	public Notice createNotice(String title, String body) {
		String t = title(title);
		String b = body(body);
		return notice(notices.save(new com.lolog.api.domain.inbox.entity.Notice(t, b)));
	}

	// 바꾼 것만 보낸다. 둘 다 없으면 그대로 돌려준다.
	@Transactional
	public Notice updateNotice(long id, String title, String body) {
		com.lolog.api.domain.inbox.entity.Notice notice = findNotice(id);
		String t = title == null ? notice.getTitle() : title(title);
		String b = body == null ? notice.getBody() : body(body);
		// 제목·본문을 하나라도 보냈을 때만 고친다(둘 다 빼면 그대로).
		if (title != null || body != null) {
			notice.edit(t, b);
		}
		// 바뀐 수정 일시를 응답에 담으려고 먼저 반영한다.
		notices.flush();
		return notice(notice);
	}

	// 읽음 기록은 외래 키로 함께 지워진다.
	@Transactional
	public void deleteNotice(long id) {
		if (notices.deleteNotice(id) == 0) {
			throw noticeNotFound();
		}
	}

	// ---- 회원 ----

	// 닉네임과 친구 코드는 메인 백엔드에 있다. 프로필이 없거나 닉네임을 정하기 전이면 null이다.
	@Transactional(readOnly = true)
	public AdminUser user(long userId) {
		return profiles.findById(userId)
			.filter(p -> p.getNickname() != null)
			.map(p -> new AdminUser(p.getNickname(), p.getFriendCode()))
			.orElse(new AdminUser(null, null));
	}

	// 인증 서비스가 정지할 때 부른다. 다시 부르면 기한을 바꾼다.
	@Transactional
	public void suspend(long userId, OffsetDateTime until) {
		suspensions.upsert(userId, until);
	}

	// 관리자가 먼저 풀었을 때. 기한이 지나 풀릴 때는 부르지 않는다(기한으로 판단).
	@Transactional
	public void unsuspend(long userId) {
		suspensions.deleteAllByUserId(userId);
	}

	// ---- 오류 신고·의견 ----

	// 한 사람이 24시간 안에 10번까지 보낸다. 동시에 보내도 넘지 않게 회원마다 잠근다.
	@Transactional
	public FeedbackItem send(long me, FeedbackRequest request) {
		String category = request.category();
		if (!Set.of("BUG", "OPINION").contains(category == null ? "" : category)) {
			throw ApiException.invalid("category");
		}
		String text = trimmed(request.text(), "text", FEEDBACK_MAX, "내용은 1~1000자예요.");
		String appVersion = trimmed(request.appVersion(), "appVersion", 30, "appVersion이 필요해요(30자까지).");
		String deviceModel = trimmed(request.deviceModel(), "deviceModel", 100, "deviceModel이 필요해요(100자까지).");
		feedback.lockSender(7002, (int) (me ^ (me >>> 32)));
		long today = feedback.countSentSince(me, OffsetDateTime.now().minusDays(1));
		if (today >= FEEDBACK_PER_DAY) {
			throw new ApiException(ErrorCode.TOO_MANY_REQUESTS);
		}
		return item(feedback.save(new Feedback(me, category, text, appVersion, deviceModel)));
	}

	// 최근 것이 위. status가 없으면 전부.
	@Transactional(readOnly = true)
	public FeedbackPage feedbackList(String status, String cursor) {
		if (status != null && !Set.of("OPEN", "DONE").contains(status)) {
			throw ApiException.invalid("status");
		}
		PageRequestParams page = PageRequestParams.of(cursor, PAGE_SIZE);
		Limit limit = Limit.of(PAGE_SIZE + 1);
		List<Feedback> rows = status == null ? feedback.findPage(page.before(), limit)
				: feedback.findPageByStatus(status, page.before(), limit);
		List<FeedbackItem> items = new ArrayList<>(rows.stream().map(AdminService::item).toList());
		String next = null;
		if (items.size() > PAGE_SIZE) {
			items = new ArrayList<>(items.subList(0, PAGE_SIZE));
			next = String.valueOf(items.getLast().id());
		}
		return new FeedbackPage(items, next);
	}

	// 오류 신고·의견의 처리 상태를 바꾼다.
	@Transactional
	public FeedbackItem markFeedback(long id, String status) {
		if (!Set.of("OPEN", "DONE").contains(status == null ? "" : status)) {
			throw ApiException.invalid("status");
		}
		Feedback found = feedback.findById(id).orElseThrow(() -> new ApiException(ErrorCode.FEEDBACK_NOT_FOUND));
		found.mark(status);
		return item(found);
	}

	// ---- 안에서 쓰는 것 ----

	// 공지사항 하나를 찾는다. 없으면 NOTICE_NOT_FOUND.
	private com.lolog.api.domain.inbox.entity.Notice findNotice(long id) {
		return notices.findById(id).orElseThrow(AdminService::noticeNotFound);
	}

	// 공지사항을 응답 모양으로 바꾼다.
	private static Notice notice(com.lolog.api.domain.inbox.entity.Notice n) {
		return new Notice(n.getId(), n.getTitle(), n.getBody(), n.getCreatedAt(), n.getUpdatedAt());
	}

	// 오류 신고·의견을 응답 모양으로 바꾼다.
	private static FeedbackItem item(Feedback f) {
		return new FeedbackItem(f.getId(), f.getUserId(), f.getCategory(), f.getText(), f.getAppVersion(),
				f.getDeviceModel(), f.getStatus(), f.getCreatedAt());
	}

	// 제목을 다듬고 길이를 확인한다.
	private static String title(String title) {
		return trimmed(title, "title", TITLE_MAX, "제목은 1~100자예요.");
	}

	// 본문은 길이 제한이 없고 비어 있으면 안 된다(초안).
	private static String body(String body) {
		return trimmed(body, "body", Integer.MAX_VALUE, "본문을 적어 주세요.");
	}

	// 앞뒤 공백을 빼고 길이가 1~max자인지 확인한다.
	private static String trimmed(String value, String field, int max, String message) {
		String s = value == null ? "" : value.strip();
		int length = s.codePointCount(0, s.length());
		if (length < 1 || length > max) {
			throw ApiException.invalid(field);
		}
		return s;
	}

	// NOTICE_NOT_FOUND 예외를 만든다.
	private static ApiException noticeNotFound() {
		return new ApiException(ErrorCode.NOTICE_NOT_FOUND);
	}

}
