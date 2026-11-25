package com.lolog.api.domain.text.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.blog.entity.BlogPost;
import com.lolog.api.domain.blog.repository.BlogPostRepository;
import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.text.dto.TextDtos.TextCard;
import com.lolog.api.domain.text.dto.TextDtos.TextLine;
import com.lolog.api.domain.text.dto.TextDtos.TextResponse;
import com.lolog.api.domain.text.dto.TextDtos.TextResult;
import com.lolog.api.domain.text.entity.Text;
import com.lolog.api.domain.text.entity.TextSource;
import com.lolog.api.domain.text.repository.TextRepository;
import com.lolog.api.domain.text.repository.TextView;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("TextService")
class TextServiceMockTest {

	private static final long USER_ID = 1L;
	private static final long TEXT_ID = 20L;
	private static final long JOB_ID = 200L;

	@Mock
	private TextRepository texts;

	@Mock
	private BlogPostRepository blogPosts;

	@Mock
	private JobRepository jobs;

	@Mock
	private CardRepository cards;

	@Mock
	private TextPublisher publisher;

	@Mock
	private PushService push;

	private TextService service;

	@BeforeEach
	void setUp() {
		service = new TextService(texts, blogPosts, jobs, cards, publisher, push);
	}

	// ---- receive ----

	@Test
	@DisplayName("글이 없으면 TEXT_NOT_FOUND다")
	void 글이_없으면_TEXT_NOT_FOUND() {
		// given
		given(texts.findByIdForUpdate(TEXT_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.receive(TEXT_ID, result(card())));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEXT_NOT_FOUND);
		verifyNoInteractions(cards);
	}

	@Test
	@DisplayName("글 작업이 아니면 field가 jobId인 INVALID_REQUEST다")
	void 글_작업이_아니면_400() {
		// given
		given(texts.findByIdForUpdate(TEXT_ID)).willReturn(Optional.of(written()));
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(withId(Job.comic(5L, USER_ID), JOB_ID)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.receive(TEXT_ID, result(card())));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("jobId");
		verifyNoInteractions(cards);
	}

	@Test
	@DisplayName("다른 글의 작업이면 field가 jobId인 INVALID_REQUEST다")
	void 다른_글의_작업이면_400() {
		// given
		given(texts.findByIdForUpdate(TEXT_ID)).willReturn(Optional.of(written()));
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(withId(Job.text(TEXT_ID + 1, USER_ID), JOB_ID)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.receive(TEXT_ID, result(card())));

		// then
		assertThat(e.getField()).isEqualTo("jobId");
		verifyNoInteractions(cards);
	}

	@Test
	@DisplayName("같은 jobId가 다시 오면 카드를 저장하지 않고 푸시도 보내지 않는다")
	void 끝난_작업이면_저장하지_않는다() {
		// given
		Job job = textJob();
		job.complete();
		given(texts.findByIdForUpdate(TEXT_ID)).willReturn(Optional.of(written()));
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(job));

		// when
		service.receive(TEXT_ID, result(card()));

		// then
		verifyNoInteractions(cards, push);
	}

	@Test
	@DisplayName("두 번째 화자가 화자2가 아니면 그 칸의 INVALID_REQUEST이고 저장하지 않는다")
	void 화자_이름이_틀리면_400() {
		// given
		Job job = textJob();
		given(texts.findByIdForUpdate(TEXT_ID)).willReturn(Optional.of(written()));
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(job));
		TextCard wrong = new TextCard("웃김", "요약", null, null, List.of("화자1", "화자3"),
				List.of(new TextLine(1, "화자1", "안녕", null, null)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.receive(TEXT_ID, result(wrong)));

		// then
		assertThat(e.getField()).isEqualTo("cards[0].speakers[1]");
		assertThat(job.isFinished()).isFalse();
		verifyNoInteractions(cards);
	}

	@Test
	@DisplayName("직접 쓴 글이면 글의 장소로 시간 없는 카드를 저장하고 작업을 끝낸 뒤 푸시한다")
	void 직접_쓴_글이면_카드를_저장하고_푸시한다() {
		// given
		Job job = textJob();
		Text text = written();
		given(texts.findByIdForUpdate(TEXT_ID)).willReturn(Optional.of(text));
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(job));

		// when
		service.receive(TEXT_ID, result(card()));

		// then
		ArgumentCaptor<Card> saved = ArgumentCaptor.forClass(Card.class);
		verify(cards).save(saved.capture());
		assertThat(saved.getValue().getTextId()).isEqualTo(TEXT_ID);
		assertThat(saved.getValue().getPlace()).isEqualTo("집");
		assertThat(saved.getValue().getLines()).allSatisfy(l -> assertThat(l.getStartMs()).isNull());
		assertThat(job.getStatus()).isEqualTo("DONE");
		assertThat(text.getBody()).isEqualTo("본문");
		verify(push).notify(USER_ID, PushService.textCardsCreated(TEXT_ID));
		verifyNoInteractions(blogPosts);
	}

	@Test
	@DisplayName("블로그 글이면 쓴 시각을 카드 날짜로 쓰고 본문을 지우며 푸시하지 않는다")
	void 블로그_글이면_본문을_지우고_푸시하지_않는다() {
		// given
		OffsetDateTime writtenAt = OffsetDateTime.parse("2026-11-01T10:00:00+09:00");
		Text text = withId(Text.blog(USER_ID, 3L, "제목", "본문", false), TEXT_ID);
		BlogPost post = mock(BlogPost.class);
		given(post.writtenAt()).willReturn(writtenAt);
		given(texts.findByIdForUpdate(TEXT_ID)).willReturn(Optional.of(text));
		given(jobs.findById(JOB_ID)).willReturn(Optional.of(textJob()));
		given(blogPosts.findById(3L)).willReturn(Optional.of(post));

		// when
		service.receive(TEXT_ID, result(card()));

		// then
		ArgumentCaptor<Card> saved = ArgumentCaptor.forClass(Card.class);
		verify(cards).save(saved.capture());
		assertThat(saved.getValue().getRecordedAt()).isEqualTo(writtenAt);
		assertThat(text.getBody()).isNull();
		verifyNoInteractions(push);
	}

	// ---- fail ----

	@Test
	@DisplayName("없는 작업이면 field 없는 INVALID_REQUEST다")
	void 없는_작업이면_400() {
		// given
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.fail(JOB_ID, "이유"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isNull();
	}

	@Test
	@DisplayName("실패하면 작업을 FAILED로 바꾸고 이유를 남긴다")
	void 실패하면_FAILED가_된다() {
		// given
		Job job = textJob();
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));

		// when
		service.fail(JOB_ID, "모델 실패");

		// then
		assertThat(job.getStatus()).isEqualTo("FAILED");
		assertThat(job.getFailReason()).isEqualTo("모델 실패");
	}

	@Test
	@DisplayName("이미 끝난 작업이면 상태와 이유를 바꾸지 않는다")
	void 끝난_작업의_실패는_무시한다() {
		// given
		Job job = textJob();
		job.complete();
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));

		// when
		service.fail(JOB_ID, "늦은 실패");

		// then
		assertThat(job.getStatus()).isEqualTo("DONE");
		assertThat(job.getFailReason()).isNull();
	}

	// ---- get ----

	@Test
	@DisplayName("직접 쓴 글은 본문을 주고 링크·블로그 이름은 주지 않는다")
	void 직접_쓴_글은_본문을_준다() {
		// given
		given(texts.findView(TEXT_ID, USER_ID)).willReturn(Optional.of(view(TextSource.WRITTEN)));

		// when
		TextResponse response = service.get(USER_ID, TEXT_ID);

		// then
		assertThat(response.body()).isEqualTo("본문");
		assertThat(response.link()).isNull();
		assertThat(response.blogTitle()).isNull();
		assertThat(response.source()).isEqualTo("WRITTEN");
	}

	@Test
	@DisplayName("블로그 글은 본문 없이 원문 링크와 블로그 이름을 준다")
	void 블로그_글은_링크를_준다() {
		// given
		given(texts.findView(TEXT_ID, USER_ID)).willReturn(Optional.of(view(TextSource.BLOG)));

		// when
		TextResponse response = service.get(USER_ID, TEXT_ID);

		// then
		assertThat(response.body()).isNull();
		assertThat(response.link()).isEqualTo("https://blog.test/1");
		assertThat(response.blogTitle()).isEqualTo("내 블로그");
	}

	@Test
	@DisplayName("내 글이 아니면 TEXT_NOT_FOUND다")
	void 내_글이_아니면_TEXT_NOT_FOUND() {
		// given
		given(texts.findView(TEXT_ID, USER_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.get(USER_ID, TEXT_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEXT_NOT_FOUND);
	}

	private static TextView view(TextSource source) {
		return new TextView(TEXT_ID, source, "제목", "본문", null, null, false, OffsetDateTime.now(), null, "QUEUED",
				"https://blog.test/1", "내 블로그");
	}

	private static Text written() {
		return withId(Text.written(USER_ID, "제목", "본문", "집", null), TEXT_ID);
	}

	private static Job textJob() {
		return withId(Job.text(TEXT_ID, USER_ID), JOB_ID);
	}

	private static TextCard card() {
		return new TextCard("웃김", "요약", null, null, List.of("화자1", "화자2"),
				List.of(new TextLine(1, "화자1", "안녕", null, null), new TextLine(2, "화자2", "응", null, null)));
	}

	private static TextResult result(TextCard card) {
		return new TextResult(JOB_ID, List.of(card));
	}

	private static <T> T withId(T entity, long id) {
		ReflectionTestUtils.setField(entity, "id", id);
		return entity;
	}

}
