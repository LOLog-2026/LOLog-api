package com.lolog.api.storage;

import java.net.URI;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

// S3 호환 저장소 클라이언트(배포는 교육기관 MinIO, 로컬은 SeaweedFS). 업로드·삭제는 저장소 주소,
// 미리 서명한 주소는 공개 주소로 만든다(deployment.md 5장). 공개 주소는 우리 Nginx가 Host를 바꾸지 않고 저장소로 넘긴다.
// 둘 다 버킷을 주소 경로에 넣는 방식(path-style)을 쓴다.
@Configuration
@EnableConfigurationProperties(StorageProperties.class)
public class StorageConfig {

	@Bean(destroyMethod = "close")
	S3Client s3Client(StorageProperties props) {
		return S3Client.builder()
			.endpointOverride(URI.create(props.endpoint()))
			.region(Region.of(props.region()))
			.credentialsProvider(credentials(props))
			.forcePathStyle(true)
			.build();
	}

	@Bean(destroyMethod = "close")
	S3Presigner s3Presigner(StorageProperties props) {
		return S3Presigner.builder()
			.endpointOverride(URI.create(props.publicEndpoint()))
			.region(Region.of(props.region()))
			.credentialsProvider(credentials(props))
			.serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
			.build();
	}

	private static StaticCredentialsProvider credentials(StorageProperties props) {
		return StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()));
	}

}
