package com.lolog.api.blog;

import java.net.URI;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

// 따로 맞추지 않은 블로그(브런치·Blogger 등). 넣은 주소 그대로 + <link rel="alternate"> 찾기로 읽고 <category>를 그대로 본다.
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class OtherPlatform implements BlogPlatform {

	@Override
	public Platform platform() {
		return Platform.OTHER;
	}

	@Override
	public boolean supports(URI siteUrl) {
		return true;
	}

}
