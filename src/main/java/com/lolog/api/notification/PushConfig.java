package com.lolog.api.notification;

import com.google.auth.oauth2.GoogleCredentials;
import com.google.firebase.FirebaseApp;
import com.google.firebase.FirebaseOptions;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

// FCM 서비스 계정 키 파일(FCM_CREDENTIALS_PATH)을 읽어 푸시를 켠다.
// 경로가 비어 있으면 푸시만 끄고 서버는 뜬다(서버에 키를 넣기 전과 테스트). 경로가 있는데 읽지 못하면 서버가 뜨지 않는다.
@Configuration
public class PushConfig {

	private static final Logger log = LoggerFactory.getLogger(PushConfig.class);

	// 같은 JVM에서 컨텍스트가 여러 번 떠도(테스트) 이름이 겹치지 않게 FcmPushSender가 끝날 때 지운다.
	private static final String APP_NAME = "lolog-api";

	@Bean
	PushSender pushSender(@Value("${lolog.fcm.credentials-path:}") String credentialsPath) throws IOException {
		if (!StringUtils.hasText(credentialsPath)) {
			log.warn("FCM_CREDENTIALS_PATH가 없어 푸시를 보내지 않습니다.");
			return PushSender.DISABLED;
		}
		try (InputStream in = Files.newInputStream(Path.of(credentialsPath))) {
			FirebaseOptions options = FirebaseOptions.builder().setCredentials(GoogleCredentials.fromStream(in)).build();
			log.info("FCM 푸시를 켰습니다.");
			return new FcmPushSender(FirebaseApp.initializeApp(options, APP_NAME));
		}
	}

}
