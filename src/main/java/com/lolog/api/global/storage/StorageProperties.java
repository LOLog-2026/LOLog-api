package com.lolog.api.global.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * lolog.storage.* 설정. 값은 환경 변수 S3_*에서 온다(application.yml).
 *
 * @param endpoint 업로드·삭제에 쓰는 저장소 주소(S3_ENDPOINT)
 * @param publicEndpoint 서명 주소에 쓰는 공개 주소(S3_PUBLIC_ENDPOINT)
 * @param bucket 버킷 이름(S3_BUCKET)
 * @param region 리전(S3_REGION, 기본 us-east-1)
 * @param accessKey 접근 키(S3_ACCESS_KEY)
 * @param secretKey 비밀 키(S3_SECRET_KEY)
 */
@ConfigurationProperties("lolog.storage")
public record StorageProperties(String endpoint, String publicEndpoint, String bucket, String region,
		String accessKey, String secretKey) {
}
