package com.lolog.api.admin;

import com.lolog.api.admin.AdminDtos.AdminUser;
import com.lolog.api.admin.AdminDtos.FeedbackItem;
import com.lolog.api.admin.AdminDtos.FeedbackPage;
import com.lolog.api.admin.AdminDtos.FeedbackRequest;
import com.lolog.api.admin.AdminDtos.Notice;
import com.lolog.api.common.ApiException;
import com.lolog.api.common.PageRequestParams;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
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

	private final JdbcTemplate jdbc;

	public AdminService(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	// ---- 공지사항 ----

	@Transactional
	public Notice createNotice(String title, String body) {
		String t = title(title);
		String b = body(body);
		long id = jdbc.queryForObject("insert into notices (title, body) values (?, ?) returning id", Long.class, t, b);
		return notice(id);
	}

	// 바꾼 것만 보낸다. 둘 다 없으면 그대로 돌려준다.
	@Transactional
	public Notice updateNotice(long id, String title, String body) {
		notice(id);
		if (title != null) {
			jdbc.update("update notices set title = ?, updated_at = current_timestamp where id = ?", title(title), id);
		}
		if (body != null) {
			jdbc.update("update notices set body = ?, updated_at = current_timestamp where id = ?", body(body), id);
		}
		return notice(id);
	}

	// 읽음 기록은 외래 키로 함께 지워진다.
	@Transactional
	public void deleteNotice(long id) {
		if (jdbc.update("delete from notices where id = ?", id) == 0) {
			throw noticeNotFound();
		}
	}

	// ---- 회원 ----

	// 닉네임과 친구 코드는 메인 백엔드에 있다. 프로필이 없거나 닉네임을 정하기 전이면 null이다.
	@Transactional(readOnly = true)
	public AdminUser user(long userId) {
		return jdbc.query("select nickname, friend_code from user_profiles where user_id = ? and nickname is not null",
				(rs, n) -> new AdminUser(rs.getString("nickname"), rs.getString("friend_code")), userId)
			.stream()
			.findFirst()
			.orElse(new AdminUser(null, null));
	}

	// 인증 서비스가 정지할 때 부른다. 다시 부르면 기한을 바꾼다.
	@Transactional
	public void suspend(long userId, OffsetDateTime until) {
		jdbc.update("""
				insert into user_suspensions (user_id, suspended_until) values (?, ?)
				on conflict (user_id) do update set suspended_until = excluded.suspended_until
				""", userId, until);
	}

	// 관리자가 먼저 풀었을 때. 기한이 지나 풀릴 때는 부르지 않는다(기한으로 판단).
	@Transactional
	public void unsuspend(long userId) {
		jdbc.update("delete from user_suspensions where user_id = ?", userId);
	}

	// ---- 오류 신고·의견 ----

	// 한 사람이 24시간 안에 10번까지 보낸다. 동시에 보내도 넘지 않게 회원마다 잠근다.
	@Transactional
	public FeedbackItem send(long me, FeedbackRequest request) {
		String category = request.category();
		if (!Set.of("BUG", "OPINION").contains(category == null ? "" : category)) {
			throw ApiException.invalid("category", "category는 BUG 또는 OPINION이에요.");
		}
		String text = trimmed(request.text(), "text", FEEDBACK_MAX, "내용은 1~1000자예요.");
		String appVersion = trimmed(request.appVersion(), "appVersion", 30, "appVersion이 필요해요(30자까지).");
		String deviceModel = trimmed(request.deviceModel(), "deviceModel", 100, "deviceModel이 필요해요(100자까지).");
		jdbc.queryForList("select pg_advisory_xact_lock(?, ?)", 7002, (int) (me ^ (me >>> 32)));
		Long today = jdbc.queryForObject(
				"select count(*) from feedback where user_id = ? and created_at > current_timestamp - interval '1 day'",
				Long.class, me);
		if (today != null && today >= FEEDBACK_PER_DAY) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "의견은 하루에 10번까지 보낼 수 있어요.");
		}
		long id = jdbc.queryForObject("""
				insert into feedback (user_id, category, text, app_version, device_model) values (?, ?, ?, ?, ?) returning id
				""", Long.class, me, category, text, appVersion, deviceModel);
		return feedback(id);
	}

	// 최근 것이 위. status가 없으면 전부.
	@Transactional(readOnly = true)
	public FeedbackPage feedbackList(String status, String cursor) {
		if (status != null && !Set.of("OPEN", "DONE").contains(status)) {
			throw ApiException.invalid("status", "status는 OPEN 또는 DONE이에요.");
		}
		PageRequestParams page = PageRequestParams.of(cursor, PAGE_SIZE);
		List<FeedbackItem> items = new ArrayList<>(jdbc.query("""
				select * from feedback where (?::varchar is null or status = ?) and (?::bigint is null or id < ?)
				order by id desc limit ?
				""", (rs, n) -> feedback(rs), status, status, page.before(), page.before(), PAGE_SIZE + 1));
		String next = null;
		if (items.size() > PAGE_SIZE) {
			items = new ArrayList<>(items.subList(0, PAGE_SIZE));
			next = String.valueOf(items.getLast().id());
		}
		return new FeedbackPage(items, next);
	}

	@Transactional
	public FeedbackItem markFeedback(long id, String status) {
		if (!Set.of("OPEN", "DONE").contains(status == null ? "" : status)) {
			throw ApiException.invalid("status", "status는 OPEN 또는 DONE이에요.");
		}
		if (jdbc.update("update feedback set status = ?, updated_at = current_timestamp where id = ?", status, id) == 0) {
			throw new ApiException(HttpStatus.NOT_FOUND, "FEEDBACK_NOT_FOUND", "의견을 찾을 수 없어요.");
		}
		return feedback(id);
	}

	// ---- 안에서 쓰는 것 ----

	private Notice notice(long id) {
		return jdbc.query("select * from notices where id = ?",
				(rs, n) -> new Notice(rs.getLong("id"), rs.getString("title"), rs.getString("body"),
						rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class)),
				id)
			.stream()
			.findFirst()
			.orElseThrow(AdminService::noticeNotFound);
	}

	private FeedbackItem feedback(long id) {
		return jdbc.queryForObject("select * from feedback where id = ?", (rs, n) -> feedback(rs), id);
	}

	private static FeedbackItem feedback(ResultSet rs) throws SQLException {
		return new FeedbackItem(rs.getLong("id"), (Long) rs.getObject("user_id"), rs.getString("category"),
				rs.getString("text"), rs.getString("app_version"), rs.getString("device_model"), rs.getString("status"),
				rs.getObject("created_at", OffsetDateTime.class));
	}

	private static String title(String title) {
		return trimmed(title, "title", TITLE_MAX, "제목은 1~100자예요.");
	}

	// 본문은 길이 제한이 없고 비어 있으면 안 된다(초안).
	private static String body(String body) {
		return trimmed(body, "body", Integer.MAX_VALUE, "본문을 적어 주세요.");
	}

	private static String trimmed(String value, String field, int max, String message) {
		String s = value == null ? "" : value.strip();
		int length = s.codePointCount(0, s.length());
		if (length < 1 || length > max) {
			throw ApiException.invalid(field, message);
		}
		return s;
	}

	private static ApiException noticeNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "NOTICE_NOT_FOUND", "공지사항을 찾을 수 없어요.");
	}

}
