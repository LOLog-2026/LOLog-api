package com.lolog.api.support;

import com.lolog.api.global.auth.InternalSecurityConfig;
import com.lolog.api.global.auth.JwtAuthConfig;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.AliasFor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

// /internal 컨트롤러 테스트의 공통 설정. 컨트롤러 하나만 띄우고 실제 보안 설정(InternalSecurityConfig, JwtAuthConfig)을 붙인다.
// JwtAuthConfig가 쓰는 Redis는 목으로 둔다. 서비스는 각 테스트가 @MockitoBean으로 둔다.
// 내부 토큰 값은 src/test/resources/application.properties의 INTERNAL_TOKEN(TOKEN 상수)이다.
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@WebMvcTest
@Import({ InternalSecurityConfig.class, JwtAuthConfig.class })
@MockitoBean(types = StringRedisTemplate.class)
public @interface InternalWebMvcTest {

	// 요청에 붙일 내부 토큰 헤더 이름과 값
	String HEADER = "X-Internal-Token";
	String TOKEN = "internal-test-token";

	// 띄울 컨트롤러
	@AliasFor(annotation = WebMvcTest.class, attribute = "controllers")
	Class<?>[] value() default {};

}
