package com.lolog.api.text;

import com.lolog.api.card.Card;
import com.lolog.api.card.CardRepository;
import com.lolog.api.common.ApiException;
import com.lolog.api.job.Job;
import com.lolog.api.job.JobRepository;
import com.lolog.api.notification.PushService;
import com.lolog.api.text.TextDtos.TextAccepted;
import com.lolog.api.text.TextDtos.TextCard;
import com.lolog.api.text.TextDtos.TextCreateRequest;
import com.lolog.api.text.TextDtos.TextLine;
import com.lolog.api.text.TextDtos.TextResponse;
import com.lolog.api.text.TextDtos.TextResult;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 글로 카드 만들기(texts.md, FR-TEXT-01). 글을 저장해 큐에 넣고, 워커가 보낸 카드 한 장을 받는다.
// 글 상태는 칸으로 두지 않고 카드·작업으로 채운다(texts.md 2장 "상태 채우기").
@Service
public class TextService {

	// 글 길이 상한. AI 담당이 숫자를 정하기 전까지 백엔드 임시 2,000자(texts.md 2장, 임시 정함 11-12)
	public static final int MAX_BODY = 2000;
	static final int MAX_TITLE = 300;
	// 글의 장소는 그대로 카드 장소가 되므로 카드 고치기의 장소 한도(20자)와 같다.
	static final int MAX_PLACE = 20;
	static final int MAX_SITUATION = 500;
	static final String WRITTEN = "WRITTEN";
	static final String BLOG = "BLOG";

	// 글 상태: 그 글로 만든 카드가 있으면 DONE, TEXT 작업이 실패면 FAILED, 그 밖에는 QUEUED
	static final String STATUS_SQL = """
			case when exists (select 1 from cards c where c.text_id = t.id) then 'DONE'
			     when exists (select 1 from jobs j where j.text_id = t.id and j.status = 'FAILED') then 'FAILED'
			     else 'QUEUED' end""";

	private final JdbcTemplate jdbc;
	private final JobRepository jobs;
	private final CardRepository cards;
	private final TextPublisher publisher;
	private final PushService push;

	public TextService(JdbcTemplate jdbc, JobRepository jobs, CardRepository cards, TextPublisher publisher,
			PushService push) {
		this.jdbc = jdbc;
		this.jobs = jobs;
		this.cards = cards;
		this.publisher = publisher;
		this.push = push;
	}

	// 직접 쓴 글 올리기. 제목·본문은 필수, 장소·상황은 선택이다. 본문이 상한을 넘으면 400 TEXT_TOO_LONG.
	@Transactional
	public TextAccepted create(long userId, TextCreateRequest request) {
		String title = required(request.title(), MAX_TITLE, "title");
		String body = request.body() == null ? null : request.body().strip();
		if (body == null || body.isEmpty()) {
			throw ApiException.invalid("body", "본문을 써 주세요.");
		}
		if (length(body) > MAX_BODY) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "TEXT_TOO_LONG", "글은 " + MAX_BODY + "자까지 쓸 수 있어요.", "body");
		}
		String place = optional(request.place(), MAX_PLACE, "place");
		String situation = optional(request.situation(), MAX_SITUATION, "situation");
		long textId = jdbc.queryForObject("""
				insert into texts (user_id, source, title, body, place, situation) values (?, 'WRITTEN', ?, ?, ?, ?)
				returning id
				""", Long.class, userId, title, body, place, situation);
		queue(textId, userId);
		return new TextAccepted(textId, TextStatus.QUEUED);
	}

	// 작업을 만들고 커밋 뒤에 큐에 넣는다. 블로그 글을 가져올 때도 쓴다.
	public void queue(long textId, long userId) {
		long jobId = jobs.save(Job.text(textId, userId)).getId();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				publisher.publishQuietly(jobId);
			}
		});
	}

	@Transactional(readOnly = true)
	public TextResponse get(long userId, long id) {
		List<TextResponse> rows = jdbc.query("""
				select t.id, t.source, t.title, t.body, t.place, t.situation, t.truncated, t.created_at,
				       (select c.id from cards c where c.text_id = t.id order by c.id limit 1) as card_id,
				       %s as status, %s
				from texts t %s
				where t.id = ? and t.user_id = ?
				""".formatted(STATUS_SQL, blogColumns(), blogJoin()), (rs, n) -> {
			boolean blog = BLOG.equals(rs.getString("source"));
			Long cardId = rs.getObject("card_id", Long.class);
			return new TextResponse(rs.getLong("id"), rs.getString("source"), TextStatus.valueOf(rs.getString("status")),
					cardId, rs.getString("title"), blog ? null : rs.getString("body"), rs.getString("place"),
					rs.getString("situation"), blog ? rs.getString("link") : null, blog ? rs.getString("blog_title") : null,
					rs.getBoolean("truncated"), rs.getObject("created_at", OffsetDateTime.class));
		}, id, userId);
		if (rows.isEmpty()) {
			throw notFound();
		}
		return rows.getFirst();
	}

	// 블로그 표가 생기기 전(V15)에는 블로그 칸이 없다. 블로그 기능(#72)에서 blog_posts를 붙인다.
	String blogColumns() {
		return "null as link, null as blog_title";
	}

	String blogJoin() {
		return "";
	}

	// 워커가 보낸 카드 한 장을 저장한다. 같은 jobId가 다시 오면(워커 재시도) 저장하지 않는다.
	@Transactional
	public void receive(long textId, TextResult result) {
		List<TextRow> found = jdbc.query("""
				select id, user_id, source, place, created_at from texts where id = ? for update
				""", (rs, n) -> new TextRow(rs.getLong("id"), rs.getLong("user_id"), rs.getString("source"),
				rs.getString("place"), rs.getObject("created_at", OffsetDateTime.class)), textId);
		if (found.isEmpty()) {
			throw notFound();
		}
		TextRow text = found.getFirst();
		Job job = jobs.findById(result.jobId())
			.filter(j -> "TEXT".equals(j.getJobType()))
			.orElseThrow(() -> ApiException.invalid("jobId", "글 작업을 찾을 수 없습니다."));
		if (!job.getTextId().equals(textId)) {
			throw ApiException.invalid("jobId", "이 글의 작업이 아닙니다.");
		}
		if (job.isFinished()) {
			return;
		}
		TextCard detected = result.cards().getFirst();
		validate(detected);
		// 장소는 글에 적은 장소다. 비어 있으면 녹음 카드처럼 비워 둔다.
		Card card = Card.fromText(textId, text.userId(), detected.kind(), detected.summary(), text.place(),
				recordedAt(text));
		detected.speakers().forEach(card::addSpeaker);
		// 글 카드의 대사는 시간 값이 없다(texts.md 5장 결과).
		detected.lines().forEach(l -> card.addLine(l.lineNo(), l.speaker(), l.text(), null, null));
		cards.save(card);
		job.complete();
		if (WRITTEN.equals(text.source())) {
			// 직접 쓴 글은 카드가 생기면 푸시한다. 블로그 글은 보내지 않고 보관함 새 소식 점만 켠다(texts.md 2장).
			push.notify(text.userId(), PushService.textCardsCreated(textId));
		}
		else {
			// 블로그 글은 카드가 생기면 본문을 지우고 링크만 남긴다(texts.md 2장 "글 본문 남기기").
			jdbc.update("update texts set body = null where id = ?", textId);
		}
	}

	// 글 카드의 날짜: 직접 쓴 글은 올린 시각이다. 블로그 글은 #72에서 쓴 시각(없으면 가져온 시각)으로 채운다.
	OffsetDateTime recordedAt(TextRow text) {
		return text.createdAt();
	}

	@Transactional
	public void fail(long jobId, String reason) {
		Job job = jobs.findByIdForUpdate(jobId).orElseThrow(() -> ApiException.invalid(null, "작업을 찾을 수 없습니다."));
		if (job.isFinished()) {
			return;
		}
		job.fail(reason);
	}

	// 녹음 카드(DetectResultService)와 같은 검사. 틀리면 400 INVALID_REQUEST와 틀린 칸의 경로.
	private static void validate(TextCard card) {
		String at = "cards[0]";
		for (int k = 0; k < card.speakers().size(); k++) {
			if (!card.speakers().get(k).equals("화자" + (k + 1))) {
				throw ApiException.invalid(at + ".speakers[" + k + "]", "화자는 화자1부터 빠짐없이 써야 합니다.");
			}
		}
		for (int j = 0; j < card.lines().size(); j++) {
			TextLine line = card.lines().get(j);
			if (line.lineNo() != j + 1) {
				throw ApiException.invalid(at + ".lines[" + j + "].lineNo", "대사 번호는 1부터 차례로 써야 합니다.");
			}
			if (!card.speakers().contains(line.speaker())) {
				throw ApiException.invalid(at + ".lines[" + j + "].speaker", "speakers에 없는 화자입니다.");
			}
		}
	}

	private static String required(String value, int max, String field) {
		String trimmed = value == null ? "" : value.strip();
		if (trimmed.isEmpty()) {
			throw ApiException.invalid(field, field + "을 써 주세요.");
		}
		if (length(trimmed) > max) {
			throw ApiException.invalid(field, max + "자까지 쓸 수 있어요.");
		}
		return trimmed;
	}

	// 비었거나 공백만 있으면 null(메시지에서 빠짐)
	private static String optional(String value, int max, String field) {
		String trimmed = value == null ? "" : value.strip();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (length(trimmed) > max) {
			throw ApiException.invalid(field, max + "자까지 쓸 수 있어요.");
		}
		return trimmed;
	}

	static int length(String value) {
		return value.codePointCount(0, value.length());
	}

	static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "TEXT_NOT_FOUND", "글을 찾을 수 없어요.");
	}

	record TextRow(long id, long userId, String source, String place, OffsetDateTime createdAt) {
	}

}
