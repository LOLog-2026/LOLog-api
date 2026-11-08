package com.lolog.api.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

// lolog.storage.* 설정. 값은 환경 변수 S3_*에서 온다(application.yml).
@ConfigurationProperties("lolog.storage")
public record StorageProperties(String endpoint, String publicEndpoint, String bucket, String region,
		String accessKey, String secretKey) {
}
