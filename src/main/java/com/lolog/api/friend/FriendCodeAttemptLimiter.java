package com.lolog.api.friend;

import com.lolog.api.common.ApiException;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

// 친구 코드 입력을 1분에 10번까지 받는다(코드 맞히기 막기, friends.md 오류 코드 TOO_MANY_REQUESTS).
// 첫 입력부터 1분 동안 Redis에서 센다. 서버가 여러 대여도 같은 수를 본다.
@Component
public class FriendCodeAttemptLimiter {

	static final int LIMIT = 10;
	static final Duration WINDOW = Duration.ofMinutes(1);
	private static final String KEY = "friend-code-attempts:";

	private final StringRedisTemplate redis;

	public FriendCodeAttemptLimiter(StringRedisTemplate redis) {
		this.redis = redis;
	}

	// 한 번 센다. 넘었으면 429. Redis에 못 붙으면 예외가 그대로 나가 500이 된다(다른 /api 요청과 같다).
	public void check(long userId) {
		String key = KEY + userId;
		Long count = redis.opsForValue().increment(key);
		if (count != null && count == 1) {
			redis.expire(key, WINDOW);
		}
		if (count != null && count > LIMIT) {
			// 첫 입력 때 만료를 걸지 못했으면(그 사이 Redis 오류) 여기서 건다. 안 걸면 계속 막힌다.
			Long ttl = redis.getExpire(key);
			if (ttl != null && ttl < 0) {
				redis.expire(key, WINDOW);
			}
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_REQUESTS", "잠시 뒤에 다시 시도해 주세요.");
		}
	}

}
