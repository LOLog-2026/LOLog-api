package com.lolog.api.storage;

import java.net.URL;
import java.time.Duration;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

// 만화 이미지를 SeaweedFS 버킷에 두고 꺼낸다. 버킷은 비공개이고, 앱에는 잠깐 열리는 서명 주소만 준다.
@Component
public class ImageStorage {

	// imageUrl은 10분 동안 열린다(API 명세).
	public static final Duration URL_TTL = Duration.ofMinutes(10);

	private final S3Client s3;
	private final S3Presigner presigner;
	private final String bucket;

	public ImageStorage(S3Client s3, S3Presigner presigner, StorageProperties props) {
		this.s3 = s3;
		this.presigner = presigner;
		this.bucket = props.bucket();
	}

	// 객체 키 예: users/{userId}/comics/{comicId}.png (deployment.md 5장)
	public static String comicKey(long userId, long comicId) {
		return "users/" + userId + "/comics/" + comicId + ".png";
	}

	// 같은 키면 덮어쓴다. 버킷이 아직 없으면(처음 띄운 로컬 SeaweedFS) 만들고 한 번 더 올린다.
	public void putPng(String key, byte[] png) {
		try {
			put(key, png);
		}
		catch (NoSuchBucketException e) {
			s3.createBucket(b -> b.bucket(bucket));
			put(key, png);
		}
	}

	public byte[] get(String key) {
		return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
	}

	// 없는 키를 지워도 오류가 아니다.
	public void delete(String key) {
		try {
			s3.deleteObject(b -> b.bucket(bucket).key(key));
		}
		catch (NoSuchKeyException | NoSuchBucketException e) {
			// 지울 것이 없다.
		}
	}

	public URL presignedUrl(String key) {
		return presigner.presignGetObject(p -> p.signatureDuration(URL_TTL).getObjectRequest(g -> g.bucket(bucket).key(key)))
			.url();
	}

	private void put(String key, byte[] png) {
		s3.putObject(b -> b.bucket(bucket).key(key).contentType("image/png"), RequestBody.fromBytes(png));
	}

}
