package com.lolog.api.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

// 실제 SeaweedFS(compose.yaml과 같은 이미지)에 올리고, 꺼내고, 서명 주소로 열고, 지운다. Docker가 켜져 있어야 한다.
@SpringBootTest
@Testcontainers
class ImageStorageTests {

	private static final String S3_CONFIG = """
			{"identities":[{"name":"lolog-api","credentials":[{"accessKey":"test-access-key","secretKey":"test-secret-key"}],"actions":["Admin","Read","Write","List","Tagging"]}]}
			""";

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:latest");

	@Container
	@SuppressWarnings("resource")
	static GenericContainer<?> seaweedfs = new GenericContainer<>("chrislusf/seaweedfs:4.48")
		.withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data")
		.withCopyToContainer(Transferable.of(S3_CONFIG), "/etc/seaweedfs/s3.json")
		.withExposedPorts(8333)
		.waitingFor(Wait.forListeningPort());

	@DynamicPropertySource
	static void storage(DynamicPropertyRegistry registry) {
		String url = "http://" + seaweedfs.getHost() + ":" + seaweedfs.getMappedPort(8333);
		registry.add("lolog.storage.endpoint", () -> url);
		registry.add("lolog.storage.public-endpoint", () -> url);
	}

	@Autowired
	ImageStorage storage;

	@Test
	void 올리고_꺼내고_서명_주소로_열고_지운다() throws Exception {
		String key = ImageStorage.comicKey(7, 35);
		byte[] png = "가짜 PNG".getBytes(StandardCharsets.UTF_8);
		assertThat(key).isEqualTo("users/7/comics/35.png");

		// 버킷이 없는 처음 상태에서도 올라간다.
		storage.putPng(key, png);
		assertThat(storage.get(key)).isEqualTo(png);

		URL url = storage.presignedUrl(key);
		assertThat(url.getQuery()).contains("X-Amz-Expires=600");
		HttpResponse<byte[]> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(url.toURI()).build(), HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).isEqualTo(png);

		// 서명이 없거나 틀리면 열리지 않는다(버킷은 비공개).
		URI unsigned = URI.create(url.toString().substring(0, url.toString().indexOf('?')));
		assertThat(HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(unsigned).build(), HttpResponse.BodyHandlers.discarding())
			.statusCode()).isEqualTo(403);

		storage.delete(key);
		assertThatThrownBy(() -> storage.get(key)).isInstanceOf(NoSuchKeyException.class);
		storage.delete(key);
	}

}
