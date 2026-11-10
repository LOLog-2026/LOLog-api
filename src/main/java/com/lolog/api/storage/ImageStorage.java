package com.lolog.api.storage;

import java.net.URL;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

// 만화 이미지와 녹음 음성을 S3 호환 저장소(배포는 교육기관 MinIO, 로컬은 SeaweedFS) 버킷에 두고 꺼낸다. 버킷은 비공개이고, 앱에는 잠깐 열리는 서명 주소만 준다.
// 음성도 같은 버킷의 users/{userId}/ 아래에 둔다(audio.md 저장 위치). 탈퇴하면 한 번에 지워진다.
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

	// 녹음 전체 음성 파일(10분 단위, seq는 1부터)
	public static String recordingAudioKey(long userId, long recordingId, int seq) {
		return recordingAudioPrefix(userId, recordingId) + seq + ".aac";
	}

	public static String recordingAudioPrefix(long userId, long recordingId) {
		return "users/" + userId + "/recordings/" + recordingId + "/audio/";
	}

	// 카드 구간 음성
	public static String cardAudioKey(long userId, long cardId) {
		return "users/" + userId + "/cards/" + cardId + "/audio.aac";
	}

	public void putPng(String key, byte[] png) {
		put(key, png, "image/png");
	}

	public void putAac(String key, byte[] aac) {
		put(key, aac, "audio/aac");
	}

	// 같은 키면 덮어쓴다. 버킷이 아직 없으면(처음 띄운 로컬 SeaweedFS. 배포 MinIO는 미리 만들어 둠) 만들고 한 번 더 올린다.
	private void put(String key, byte[] bytes, String contentType) {
		try {
			upload(key, bytes, contentType);
		}
		catch (NoSuchBucketException e) {
			s3.createBucket(b -> b.bucket(bucket));
			upload(key, bytes, contentType);
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

	// 사용자 한 명의 이미지와 음성을 모두 지운다(탈퇴). 실패하면 예외를 던진다.
	public void deleteUser(long userId) {
		deletePrefix("users/" + userId + "/");
	}

	// prefix 아래를 1,000개씩 나눠 지운다. 실패하면 예외를 던진다.
	public void deletePrefix(String prefix) {
		try {
			String token = null;
			do {
				String continuation = token;
				ListObjectsV2Response page = s3.listObjectsV2(b -> b.bucket(bucket).prefix(prefix).continuationToken(continuation));
				List<ObjectIdentifier> keys = page.contents().stream()
					.map(o -> ObjectIdentifier.builder().key(o.key()).build())
					.toList();
				if (!keys.isEmpty()) {
					DeleteObjectsResponse deleted = s3.deleteObjects(b -> b.bucket(bucket).delete(d -> d.objects(keys).quiet(true)));
					if (!deleted.errors().isEmpty()) {
						throw new IllegalStateException("파일 " + deleted.errors().size() + "개를 지우지 못했습니다.");
					}
				}
				token = page.isTruncated() ? page.nextContinuationToken() : null;
			}
			while (token != null);
		}
		catch (NoSuchBucketException e) {
			// 버킷이 아직 없으면 지울 것도 없다.
		}
	}

	public URL presignedUrl(String key) {
		return presigner.presignGetObject(p -> p.signatureDuration(URL_TTL).getObjectRequest(g -> g.bucket(bucket).key(key)))
			.url();
	}

	private void upload(String key, byte[] bytes, String contentType) {
		s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
	}

}
