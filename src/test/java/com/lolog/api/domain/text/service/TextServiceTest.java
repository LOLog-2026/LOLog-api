package com.lolog.api.domain.text.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lolog.api.domain.blog.repository.BlogPostRepository;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.text.dto.TextDtos.TextCreateRequest;
import com.lolog.api.domain.text.entity.Text;
import com.lolog.api.domain.text.repository.TextRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("TextService")
class TextServiceTest {

	// 서로게이트 쌍 하나(String 길이 2, 코드포인트 1)
	private static final String EMOJI = "😂";

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

	@Test
	@DisplayName("이모지는 한 글자로 센다")
	void 이모지는_한_글자로_센다() {
		// when & then
		assertThat(TextService.length(EMOJI)).isEqualTo(1);
		assertThat(TextService.length(EMOJI.repeat(2000))).isEqualTo(2000);
	}

	@Test
	@DisplayName("이모지 2000자 본문은 통과한다")
	void 이모지_2000자_본문은_통과한다() {
		// given
		when(texts.save(any(Text.class))).thenAnswer(inv -> {
			Text text = inv.getArgument(0);
			ReflectionTestUtils.setField(text, "id", 10L);
			return text;
		});
		when(jobs.save(any(Job.class))).thenAnswer(inv -> {
			Job job = inv.getArgument(0);
			ReflectionTestUtils.setField(job, "id", 20L);
			return job;
		});

		// when & then
		// String 길이로는 4000이지만 코드포인트로는 2000이다.
		assertThat(service.create(1L, new TextCreateRequest("제목", EMOJI.repeat(2000), null, null)).textId())
			.isEqualTo(10L);
	}

	@Test
	@DisplayName("이모지 2001자 본문은 TEXT TOO LONG이다")
	void 이모지_2001자_본문은_TEXT_TOO_LONG이다() {
		// when & then
		assertThatThrownBy(() -> service.create(1L, new TextCreateRequest("제목", EMOJI.repeat(2001), null, null)))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TEXT_TOO_LONG);
				assertThat(e.getField()).isEqualTo("body");
			});
		verify(texts, never()).save(any());
	}

	@Test
	@DisplayName("본문 앞뒤 공백은 길이에 세지 않는다")
	void 본문_앞뒤_공백은_길이에_세지_않는다() {
		// given
		when(texts.save(any(Text.class))).thenAnswer(inv -> {
			Text text = inv.getArgument(0);
			ReflectionTestUtils.setField(text, "id", 10L);
			return text;
		});
		when(jobs.save(any(Job.class))).thenAnswer(inv -> {
			Job job = inv.getArgument(0);
			ReflectionTestUtils.setField(job, "id", 20L);
			return job;
		});

		// when & then
		assertThat(service.create(1L, new TextCreateRequest("제목", "  " + EMOJI.repeat(2000) + "\n", null, null))
			.textId()).isEqualTo(10L);
	}

	@Test
	@DisplayName("제목이 이모지 301자면 400이다")
	void 제목이_이모지_301자면_400이다() {
		// when & then
		assertThatThrownBy(() -> service.create(1L, new TextCreateRequest(EMOJI.repeat(301), "본문", null, null)))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
				assertThat(e.getField()).isEqualTo("title");
			});
	}

	@Test
	@DisplayName("장소는 이모지 21자면 400이다")
	void 장소는_이모지_21자면_400이다() {
		// when & then
		assertThatThrownBy(() -> service.create(1L, new TextCreateRequest("제목", "본문", EMOJI.repeat(21), null)))
			.isInstanceOfSatisfying(ApiException.class, e -> {
				assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
				assertThat(e.getField()).isEqualTo("place");
			});
	}

}
