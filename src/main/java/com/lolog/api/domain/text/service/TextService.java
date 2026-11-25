package com.lolog.api.domain.text.service;

import com.lolog.api.domain.blog.repository.BlogPostRepository;
import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.text.dto.TextDtos.TextAccepted;
import com.lolog.api.domain.text.dto.TextDtos.TextCard;
import com.lolog.api.domain.text.dto.TextDtos.TextCreateRequest;
import com.lolog.api.domain.text.dto.TextDtos.TextLine;
import com.lolog.api.domain.text.dto.TextDtos.TextResponse;
import com.lolog.api.domain.text.dto.TextDtos.TextResult;
import com.lolog.api.domain.text.dto.TextStatus;
import com.lolog.api.domain.text.entity.Text;
import com.lolog.api.domain.text.entity.TextSource;
import com.lolog.api.domain.text.repository.TextRepository;
import com.lolog.api.domain.text.repository.TextView;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.transaction.AfterCommit;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

	private final TextRepository texts;
	private final BlogPostRepository blogPosts;
	private final JobRepository jobs;
	private final CardRepository cards;
	private final TextPublisher publisher;
	private final PushService push;

	/** 쓰는 저장소와 도구를 받는다. */
	public TextService(TextRepository texts, BlogPostRepository blogPosts, JobRepository jobs, CardRepository cards,
			TextPublisher publisher, PushService push) {
		this.texts = texts;
		this.blogPosts = blogPosts;
		this.jobs = jobs;
		this.cards = cards;
		this.publisher = publisher;
		this.push = push;
	}

	/**
	 * 직접 쓴 글 올리기. 제목·본문은 필수, 장소·상황은 선택이다. 본문이 상한을 넘으면 400 TEXT_TOO_LONG.
	 *
	 * @param userId 글을 올린 회원 ID(토큰의 sub)
	 * @param request 올릴 글
	 * @return 저장한 글 ID와 상태 QUEUED
	 */
	@Transactional
	public TextAccepted create(long userId, TextCreateRequest request) {
		String title = required(request.title(), MAX_TITLE, "title");
		String body = request.body() == null ? null : request.body().strip();
		if (body == null || body.isEmpty()) {
			throw ApiException.invalid("body");
		}
		if (length(body) > MAX_BODY) {
			throw new ApiException(ErrorCode.TEXT_TOO_LONG, "body");
		}
		String place = optional(request.place(), MAX_PLACE, "place");
		String situation = optional(request.situation(), MAX_SITUATION, "situation");
		long textId = texts.save(Text.written(userId, title, body, place, situation)).getId();
		queue(textId, userId);
		return new TextAccepted(textId, TextStatus.QUEUED);
	}

	/**
	 * 가져온 블로그 글로 글을 만들고 카드 작업을 큐에 넣는다. 부르는 쪽의 트랜잭션 안에서 부른다.
	 *
	 * @param userId 블로그 글을 가져온 회원 ID
	 * @param blogPostId 가져온 블로그 글 ID
	 * @param title 블로그 글 제목
	 * @param body 블로그 글 본문
	 * @param truncated 본문을 앞부분만 넣었는지
	 * @return 만든 글 ID
	 */
	public long createFromBlog(long userId, long blogPostId, String title, String body, boolean truncated) {
		long textId = texts.save(Text.blog(userId, blogPostId, title, body, truncated)).getId();
		queue(textId, userId);
		return textId;
	}

	/**
	 * 작업을 만들고 커밋 뒤에 큐에 넣는다.
	 *
	 * @param textId 카드로 만들 글 ID
	 * @param userId 글 주인 회원 ID
	 */
	private void queue(long textId, long userId) {
		long jobId = jobs.save(Job.text(textId, userId)).getId();
		AfterCommit.run(() -> publisher.publishQuietly(jobId));
	}

	/**
	 * 내 글 하나를 상태와 함께 돌려준다.
	 *
	 * @param userId 요청한 회원 ID(토큰의 sub)
	 * @param id 글 ID
	 * @return 글 내용과 상태
	 */
	@Transactional(readOnly = true)
	public TextResponse get(long userId, long id) {
		TextView text = texts.findView(id, userId).orElseThrow(TextService::notFound);
		// 블로그 글이면 원문 링크와 가져올 때 복사해 둔 블로그 이름(연결을 끊어도 남음)을 붙이고 본문은 보내지 않는다.
		boolean blog = text.source() == TextSource.BLOG;
		return new TextResponse(text.id(), text.source().name(), TextStatus.valueOf(text.status()), text.cardId(),
				text.title(), blog ? null : text.body(), text.place(), text.situation(), blog ? text.link() : null,
				blog ? text.blogTitle() : null, text.truncated(), text.createdAt());
	}

	/**
	 * 글 카드의 출처와 잘림. 녹음 카드인지 글 카드인지는 text_id로, 직접 쓴 글인지 블로그 글인지는 texts.source로 안다(texts.md 7장).
	 *
	 * @param source 글 출처 이름(WRITTEN 또는 BLOG)
	 * @param truncated 블로그 본문을 앞부분만 넣었는지
	 */
	public record TextOrigin(String source, boolean truncated) {
	}

	/**
	 * 글 ID마다 출처와 잘림을 읽는다.
	 *
	 * @param textIds 읽을 글 ID들
	 * @return 글 ID별 출처와 잘림. 없는 글은 빠진다
	 */
	public Map<Long, TextOrigin> origins(Collection<Long> textIds) {
		Map<Long, TextOrigin> result = new HashMap<>();
		if (textIds.isEmpty()) {
			return result;
		}
		texts.findAllById(textIds)
			.forEach(t -> result.put(t.getId(), new TextOrigin(t.getSource().name(), t.isTruncated())));
		return result;
	}

	/**
	 * 글 하나를 지운다(글 카드를 지울 때, texts.md 2장). 이미 없으면 그대로 둔다. 부르는 쪽의 트랜잭션 안에서 부른다.
	 *
	 * @param textId 지울 글 ID
	 */
	public void delete(long textId) {
		texts.deleteById(textId);
	}

	/**
	 * 워커가 보낸 카드 한 장을 저장한다. 같은 jobId가 다시 오면(워커 재시도) 저장하지 않는다.
	 *
	 * @param textId 카드를 만든 글 ID(경로)
	 * @param result 워커가 보낸 작업 ID와 카드
	 */
	@Transactional
	public void receive(long textId, TextResult result) {
		Text text = texts.findByIdForUpdate(textId).orElseThrow(TextService::notFound);
		Job job = jobs.findById(result.jobId())
			.filter(j -> "TEXT".equals(j.getJobType()))
			.orElseThrow(() -> ApiException.invalid("jobId"));
		if (!job.getTextId().equals(textId)) {
			throw ApiException.invalid("jobId");
		}
		if (job.isFinished()) {
			return;
		}
		TextCard detected = result.cards().getFirst();
		validate(detected);
		// 장소는 글에 적은 장소다. 비어 있으면 녹음 카드처럼 비워 둔다.
		Card card = Card.fromText(textId, text.getUserId(), detected.kind(), detected.summary(), text.getPlace(),
				recordedAt(text));
		detected.speakers().forEach(card::addSpeaker);
		// 글 카드의 대사는 시간 값이 없다(texts.md 5장 결과).
		detected.lines().forEach(l -> card.addLine(l.lineNo(), l.speaker(), l.text(), null, null));
		cards.save(card);
		job.complete();
		if (!text.isBlog()) {
			// 직접 쓴 글은 카드가 생기면 푸시한다. 블로그 글은 보내지 않고 보관함 새 소식 점만 켠다(texts.md 2장).
			push.notify(text.getUserId(), PushService.textCardsCreated(textId));
		}
		else {
			// 블로그 글은 카드가 생기면 본문을 지우고 링크만 남긴다(texts.md 2장 "글 본문 남기기").
			text.clearBody();
		}
	}

	/**
	 * 글 카드의 날짜: 직접 쓴 글은 올린 시각, 블로그 글은 쓴 시각(pubDate, 없으면 가져온 시각)이다(texts.md 2장).
	 *
	 * @param text 카드를 만든 글
	 * @return 카드에 적을 날짜
	 */
	private OffsetDateTime recordedAt(Text text) {
		if (!text.isBlog()) {
			return text.getCreatedAt();
		}
		return blogPosts.findById(text.getBlogPostId()).orElseThrow().writtenAt();
	}

	/**
	 * 워커가 알린 글 작업 실패를 기록한다.
	 *
	 * @param jobId 실패한 글 작업 ID
	 * @param reason 워커가 보낸 실패 이유
	 */
	@Transactional
	public void fail(long jobId, String reason) {
		Job job = jobs.findByIdForUpdate(jobId).orElseThrow(() -> ApiException.invalid(null));
		if (job.isFinished()) {
			return;
		}
		job.fail(reason);
	}

	/**
	 * 녹음 카드(DetectResultService)와 같은 검사. 틀리면 400 INVALID_REQUEST와 틀린 칸의 경로.
	 *
	 * @param card 워커가 만든 카드
	 */
	private static void validate(TextCard card) {
		String at = "cards[0]";
		for (int k = 0; k < card.speakers().size(); k++) {
			if (!card.speakers().get(k).equals("화자" + (k + 1))) {
				throw ApiException.invalid(at + ".speakers[" + k + "]");
			}
		}
		for (int j = 0; j < card.lines().size(); j++) {
			TextLine line = card.lines().get(j);
			if (line.lineNo() != j + 1) {
				throw ApiException.invalid(at + ".lines[" + j + "].lineNo");
			}
			if (!card.speakers().contains(line.speaker())) {
				throw ApiException.invalid(at + ".lines[" + j + "].speaker");
			}
		}
	}

	/**
	 * 필수 칸의 앞뒤 공백을 빼고 길이를 확인한다.
	 *
	 * @param value 받은 값(null 가능)
	 * @param max 최대 글자 수
	 * @param field 틀렸을 때 오류에 적을 칸 이름
	 * @return 앞뒤 공백을 뺀 값
	 */
	private static String required(String value, int max, String field) {
		String trimmed = value == null ? "" : value.strip();
		if (trimmed.isEmpty()) {
			throw ApiException.invalid(field);
		}
		if (length(trimmed) > max) {
			throw ApiException.invalid(field);
		}
		return trimmed;
	}

	/**
	 * 비었거나 공백만 있으면 null(메시지에서 빠짐)
	 *
	 * @param value 받은 값(null 가능)
	 * @param max 최대 글자 수
	 * @param field 틀렸을 때 오류에 적을 칸 이름
	 * @return 앞뒤 공백을 뺀 값. 비었으면 null
	 */
	private static String optional(String value, int max, String field) {
		String trimmed = value == null ? "" : value.strip();
		if (trimmed.isEmpty()) {
			return null;
		}
		if (length(trimmed) > max) {
			throw ApiException.invalid(field);
		}
		return trimmed;
	}

	/**
	 * 글자 수를 센다(코드 포인트 기준).
	 *
	 * @param value 셀 글
	 * @return 코드 포인트 수
	 */
	static int length(String value) {
		return value.codePointCount(0, value.length());
	}

	// 글 없음 오류를 만든다.
	static ApiException notFound() {
		return new ApiException(ErrorCode.TEXT_NOT_FOUND);
	}

}
