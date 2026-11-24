package com.lolog.api.domain.text.service;

import com.lolog.api.domain.blog.entity.BlogPost;
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
import com.lolog.api.domain.text.repository.TextRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

	private final TextRepository texts;
	private final BlogPostRepository blogPosts;
	private final JobRepository jobs;
	private final CardRepository cards;
	private final TextPublisher publisher;
	private final PushService push;

	public TextService(TextRepository texts, BlogPostRepository blogPosts, JobRepository jobs, CardRepository cards,
			TextPublisher publisher, PushService push) {
		this.texts = texts;
		this.blogPosts = blogPosts;
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

	// 가져온 블로그 글로 글을 만들고 카드 작업을 큐에 넣는다. 부르는 쪽의 트랜잭션 안에서 부른다.
	public long createFromBlog(long userId, long blogPostId, String title, String body, boolean truncated) {
		long textId = texts.save(Text.blog(userId, blogPostId, title, body, truncated)).getId();
		queue(textId, userId);
		return textId;
	}

	// 작업을 만들고 커밋 뒤에 큐에 넣는다.
	private void queue(long textId, long userId) {
		long jobId = jobs.save(Job.text(textId, userId)).getId();
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				publisher.publishQuietly(jobId);
			}
		});
	}

	// 내 글 하나를 상태와 함께 돌려준다.
	@Transactional(readOnly = true)
	public TextResponse get(long userId, long id) {
		Text text = texts.findByIdAndUserId(id, userId).orElseThrow(TextService::notFound);
		TextProgress progress = statuses(List.of(text.getId())).get(text.getId());
		// 블로그 글이면 원문 링크와 가져올 때 복사해 둔 블로그 이름(연결을 끊어도 남음)을 붙이고 본문은 보내지 않는다.
		BlogPost post = text.isBlog() ? blogPosts.findById(text.getBlogPostId()).orElse(null) : null;
		return new TextResponse(text.getId(), text.getSource().name(), progress.status(), progress.cardId(),
				text.getTitle(), text.isBlog() ? null : text.getBody(), text.getPlace(), text.getSituation(),
				post == null ? null : post.getLink(), post == null ? null : post.getBlogTitle(), text.isTruncated(),
				text.getCreatedAt());
	}

	// 글의 첫 카드와 상태.
	public record TextProgress(Long cardId, TextStatus status) {
	}

	// 가져온 블로그 글마다 그 글로 만든 글의 첫 카드와 상태. 글을 만들지 않은 블로그 글은 빠진다.
	@Transactional(readOnly = true)
	public Map<Long, TextProgress> progressByBlogPosts(Collection<Long> blogPostIds) {
		Map<Long, TextProgress> result = new HashMap<>();
		if (blogPostIds.isEmpty()) {
			return result;
		}
		List<Text> found = texts.findByBlogPostIdIn(blogPostIds);
		Map<Long, TextProgress> byText = statuses(found.stream().map(Text::getId).toList());
		found.forEach(t -> result.put(t.getBlogPostId(), byText.get(t.getId())));
		return result;
	}

	// 글마다 상태를 한 번에 정한다. 그 글로 만든 카드가 있으면 DONE, TEXT 작업이 실패면 FAILED, 그 밖에는 QUEUED
	// (texts.md 2장 "상태 채우기"). 블로그 글 목록도 이것으로 채운다.
	@Transactional(readOnly = true)
	public Map<Long, TextProgress> statuses(Collection<Long> textIds) {
		Map<Long, TextProgress> result = new HashMap<>();
		if (textIds.isEmpty()) {
			return result;
		}
		Map<Long, Long> firstCards = new HashMap<>();
		for (Object[] row : cards.findFirstCardIdsByTextIds(textIds)) {
			firstCards.put((Long) row[0], (Long) row[1]);
		}
		Set<Long> failed = new HashSet<>(jobs.findFailedTextIds(textIds));
		for (Long textId : textIds) {
			Long cardId = firstCards.get(textId);
			TextStatus status = cardId != null ? TextStatus.DONE
					: failed.contains(textId) ? TextStatus.FAILED : TextStatus.QUEUED;
			result.put(textId, new TextProgress(cardId, status));
		}
		return result;
	}

	// 워커가 보낸 카드 한 장을 저장한다. 같은 jobId가 다시 오면(워커 재시도) 저장하지 않는다.
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

	// 글 카드의 날짜: 직접 쓴 글은 올린 시각, 블로그 글은 쓴 시각(pubDate, 없으면 가져온 시각)이다(texts.md 2장).
	private OffsetDateTime recordedAt(Text text) {
		if (!text.isBlog()) {
			return text.getCreatedAt();
		}
		return blogPosts.findById(text.getBlogPostId()).orElseThrow().writtenAt();
	}

	// 워커가 알린 글 작업 실패를 기록한다.
	@Transactional
	public void fail(long jobId, String reason) {
		Job job = jobs.findByIdForUpdate(jobId).orElseThrow(() -> ApiException.invalid(null));
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

	// 필수 칸의 앞뒤 공백을 빼고 길이를 확인한다.
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

	// 비었거나 공백만 있으면 null(메시지에서 빠짐)
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

	// 글자 수를 센다(코드 포인트 기준).
	static int length(String value) {
		return value.codePointCount(0, value.length());
	}

	// 글 없음 오류를 만든다.
	static ApiException notFound() {
		return new ApiException(ErrorCode.TEXT_NOT_FOUND);
	}

}
