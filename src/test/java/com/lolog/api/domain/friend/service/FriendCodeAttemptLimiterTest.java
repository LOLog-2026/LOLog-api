package com.lolog.api.domain.friend.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@DisplayName("FriendCodeAttemptLimiter")
@ExtendWith(MockitoExtension.class)
class FriendCodeAttemptLimiterTest {

	private static final String KEY = "friend-code-attempts:7";

	@Mock
	StringRedisTemplate redis;
	@Mock
	ValueOperations<String, String> values;

	@InjectMocks
	FriendCodeAttemptLimiter limiter;

	@BeforeEach
	void setUp() {
		when(redis.opsForValue()).thenReturn(values);
	}

	@Test
	@DisplayName("첫 입력이면 1분 만료를 건다")
	void 첫_입력이면_만료를_건다() {
		// given
		when(values.increment(KEY)).thenReturn(1L);

		// when
		limiter.check(7L);

		// then
		verify(redis).expire(KEY, Duration.ofMinutes(1));
	}

	@Test
	@DisplayName("10번째 입력까지는 통과하고 만료를 다시 걸지 않는다")
	void 열_번째까지는_통과한다() {
		// given
		when(values.increment(KEY)).thenReturn(10L);

		// when & then
		assertThatCode(() -> limiter.check(7L)).doesNotThrowAnyException();
		verify(redis, never()).expire(anyString(), any(Duration.class));
	}

	@Test
	@DisplayName("11번째 입력은 TOO_MANY_REQUESTS이고 만료가 걸려 있으면 그대로 둔다")
	void 열한_번째는_TOO_MANY_REQUESTS다() {
		// given
		when(values.increment(KEY)).thenReturn(11L);
		when(redis.getExpire(KEY)).thenReturn(30L);

		// when & then
		assertThatThrownBy(() -> limiter.check(7L)).isInstanceOfSatisfying(ApiException.class,
				e -> org.assertj.core.api.Assertions.assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS));
		verify(redis, never()).expire(anyString(), any(Duration.class));
	}

	@Test
	@DisplayName("막힌 키에 만료가 없으면 만료를 다시 걸어 계속 막히지 않게 한다")
	void 만료가_없으면_다시_건다() {
		// given
		when(values.increment(KEY)).thenReturn(11L);
		when(redis.getExpire(KEY)).thenReturn(-1L);

		// when & then
		assertThatThrownBy(() -> limiter.check(7L)).isInstanceOf(ApiException.class);
		verify(redis).expire(KEY, Duration.ofMinutes(1));
	}

	@Test
	@DisplayName("Redis가 null을 돌려주면 막지 않는다")
	void 개수가_null이면_통과한다() {
		// given
		when(values.increment(KEY)).thenReturn(null);

		// when & then
		assertThatCode(() -> limiter.check(7L)).doesNotThrowAnyException();
		verify(redis, never()).expire(anyString(), any(Duration.class));
	}

}
