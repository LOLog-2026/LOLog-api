package com.lolog.api.global.storage;

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

	/** 쓰는 저장소 클라이언트와 설정을 받는다. */
	public ImageStorage(S3Client s3, S3Presigner presigner, StorageProperties props) {
		this.s3 = s3;
		this.presigner = presigner;
		this.bucket = props.bucket();
	}

	/**
	 * 객체 키 예: users/{userId}/comics/{comicId}.png (deployment.md 5장)
	 *
	 * @param userId 만화 주인 회원 ID
	 * @param comicId 만화 ID
	 * @return 만화 이미지 객체 키
	 */
	public static String comicKey(long userId, long comicId) {
		return "users/" + userId + "/comics/" + comicId + ".png";
	}

	/**
	 * 카드 영상(video.md 7장). 탈퇴하면 users/{userId}/ 아래와 같이 지워진다.
	 *
	 * @param userId 영상 주인 회원 ID
	 * @param videoId 영상 ID
	 * @return 영상 파일들이 모인 폴더 키(끝이 /)
	 */
	public static String videoPrefix(long userId, long videoId) {
		return "users/" + userId + "/videos/" + videoId + "/";
	}

	/**
	 * 영상 파일 키
	 *
	 * @param userId 영상 주인 회원 ID
	 * @param videoId 영상 ID
	 * @return 영상 파일 키(video.mp4)
	 */
	public static String videoKey(long userId, long videoId) {
		return videoPrefix(userId, videoId) + "video.mp4";
	}

	/**
	 * 영상 썸네일 키
	 *
	 * @param userId 영상 주인 회원 ID
	 * @param videoId 영상 ID
	 * @return 썸네일 키(thumb.jpg)
	 */
	public static String videoThumbKey(long userId, long videoId) {
		return videoPrefix(userId, videoId) + "thumb.jpg";
	}

	/**
	 * 영상 대사 음성 키
	 *
	 * @param userId 영상 주인 회원 ID
	 * @param videoId 영상 ID
	 * @return 대사 음성 키(audio.wav)
	 */
	public static String videoAudioKey(long userId, long videoId) {
		return videoPrefix(userId, videoId) + "audio.wav";
	}

	/**
	 * 녹음 전체 음성 파일(10분 단위, seq는 1부터)
	 *
	 * @param userId 녹음 주인 회원 ID
	 * @param recordingId 녹음 ID
	 * @param seq 10분 단위 파일 순번(1부터)
	 * @return 음성 파일 키({seq}.aac)
	 */
	public static String recordingAudioKey(long userId, long recordingId, int seq) {
		return recordingAudioPrefix(userId, recordingId) + seq + ".aac";
	}

	/**
	 * 녹음 음성 파일들이 모인 폴더
	 *
	 * @param userId 녹음 주인 회원 ID
	 * @param recordingId 녹음 ID
	 * @return 녹음 음성 폴더 키(끝이 /)
	 */
	public static String recordingAudioPrefix(long userId, long recordingId) {
		return "users/" + userId + "/recordings/" + recordingId + "/audio/";
	}

	/**
	 * 카드 구간 음성
	 *
	 * @param userId 카드 주인 회원 ID
	 * @param cardId 카드 ID
	 * @return 카드 구간 음성 키(audio.aac)
	 */
	public static String cardAudioKey(long userId, long cardId) {
		return "users/" + userId + "/cards/" + cardId + "/audio.aac";
	}

	/**
	 * 기본 화자 그림(768 PNG)과 썸네일(256 WebP). characters.md 2장
	 *
	 * @param id 기본 화자 ID(예: young-f-02)
	 * @return 기본 화자 그림 키(PNG)
	 */
	public static String defaultCharacterKey(String id) {
		return "defaults/characters/" + id + ".png";
	}

	/**
	 * 기본 화자 썸네일 키
	 *
	 * @param id 기본 화자 ID
	 * @return 썸네일 키(WebP)
	 */
	public static String defaultCharacterThumbKey(String id) {
		return "defaults/characters/" + id + "_thumb.webp";
	}

	/**
	 * 기본 화자 목소리(characters.md 기본 화자 목소리). 받은 wav 그대로다.
	 *
	 * @param id 기본 화자 ID
	 * @return 목소리 키(wav)
	 */
	public static String defaultCharacterVoiceKey(String id) {
		return "defaults/characters/" + id + "_voice.wav";
	}

	/**
	 * 쇼츠 효과음·배경음(sounds.md 5장 저장). 서비스가 모아 둔 공용 파일이다.
	 *
	 * @param id 소리 ID(sounds의 id, freesoundId가 아님)
	 * @return 소리 파일 키(ogg)
	 */
	public static String soundKey(long id) {
		return "defaults/sounds/" + id + ".ogg";
	}

	/**
	 * 프로필 캐릭터 그림(768 PNG), 썸네일(256 PNG), 원본 사진(그림을 받거나 실패하면 바로 지움). characters.md 3장
	 *
	 * @param userId 캐릭터 주인 회원 ID
	 * @return 프로필 캐릭터 그림 키(PNG)
	 */
	public static String profileCharacterKey(long userId) {
		return "users/" + userId + "/character/avatar.png";
	}

	/**
	 * 프로필 캐릭터 썸네일 키
	 *
	 * @param userId 캐릭터 주인 회원 ID
	 * @return 썸네일 키(PNG)
	 */
	public static String profileCharacterThumbKey(long userId) {
		return "users/" + userId + "/character/avatar_thumb.png";
	}

	/**
	 * 프로필 원본 사진 키
	 *
	 * @param userId 사진 주인 회원 ID
	 * @return 원본 사진 키(확장자 없음)
	 */
	public static String profilePhotoKey(long userId) {
		return "users/" + userId + "/character/photo";
	}

	/**
	 * 내 목소리 원본 녹음(보관)과 워커가 만든 미리 듣기(characters.md "내 목소리"). ext는 wav 또는 m4a
	 *
	 * @param userId 목소리 주인 회원 ID
	 * @param ext 파일 확장자(wav 또는 m4a)
	 * @return 원본 녹음 키
	 */
	public static String voiceCloneSampleKey(long userId, String ext) {
		return "users/" + userId + "/voice/sample." + ext;
	}

	/**
	 * 내 목소리 미리 듣기 키
	 *
	 * @param userId 목소리 주인 회원 ID
	 * @return 미리 듣기 키(wav)
	 */
	public static String voiceClonePreviewKey(long userId) {
		return "users/" + userId + "/voice/preview.wav";
	}

	/**
	 * 파일 내용과 올릴 때 적은 Content-Type
	 *
	 * @param bytes 파일 내용
	 * @param contentType 올릴 때 적은 Content-Type
	 */
	public record StoredObject(byte[] bytes, String contentType) {
	}

	/**
	 * 파일 내용과 Content-Type을 함께 꺼낸다.
	 *
	 * @param key 꺼낼 객체 키
	 * @return 파일 내용과 Content-Type
	 */
	public StoredObject getObject(String key) {
		var response = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key));
		return new StoredObject(response.asByteArray(), response.response().contentType());
	}

	/**
	 * PNG 이미지를 올린다.
	 *
	 * @param key 올릴 객체 키
	 * @param png PNG 파일 내용
	 */
	public void putPng(String key, byte[] png) {
		put(key, png, "image/png");
	}

	/**
	 * AAC 음성을 올린다.
	 *
	 * @param key 올릴 객체 키
	 * @param aac AAC 파일 내용
	 */
	public void putAac(String key, byte[] aac) {
		put(key, aac, "audio/aac");
	}

	/**
	 * 같은 키면 덮어쓴다. 버킷이 아직 없으면(처음 띄운 로컬 SeaweedFS. 배포 MinIO는 미리 만들어 둠) 만들고 한 번 더 올린다.
	 *
	 * @param key 올릴 객체 키
	 * @param bytes 파일 내용
	 * @param contentType 저장할 Content-Type
	 */
	public void put(String key, byte[] bytes, String contentType) {
		try {
			upload(key, bytes, contentType);
		}
		catch (NoSuchBucketException e) {
			s3.createBucket(b -> b.bucket(bucket));
			upload(key, bytes, contentType);
		}
	}

	/**
	 * 파일 내용만 꺼낸다.
	 *
	 * @param key 꺼낼 객체 키
	 * @return 파일 내용
	 */
	public byte[] get(String key) {
		return s3.getObjectAsBytes(b -> b.bucket(bucket).key(key)).asByteArray();
	}

	/**
	 * 없는 키를 지워도 오류가 아니다.
	 *
	 * @param key 지울 객체 키
	 */
	public void delete(String key) {
		try {
			s3.deleteObject(b -> b.bucket(bucket).key(key));
		}
		catch (NoSuchKeyException | NoSuchBucketException e) {
			// 지울 것이 없다.
		}
	}

	/**
	 * 사용자 한 명의 이미지와 음성을 모두 지운다(탈퇴). 실패하면 예외를 던진다.
	 *
	 * @param userId 탈퇴한 회원 ID
	 */
	public void deleteUser(long userId) {
		deletePrefix("users/" + userId + "/");
	}

	/**
	 * prefix 아래를 1,000개씩 나눠 지운다. 실패하면 예외를 던진다.
	 *
	 * @param prefix 지울 키 접두어(예: users/42/)
	 */
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

	/**
	 * 10분 동안 열리는 서명 주소를 만든다.
	 *
	 * @param key 서명할 객체 키
	 * @return 10분 동안 열리는 GET 주소
	 */
	public URL presignedUrl(String key) {
		return presigner.presignGetObject(p -> p.signatureDuration(URL_TTL).getObjectRequest(g -> g.bucket(bucket).key(key)))
			.url();
	}

	/**
	 * 저장소에 파일 한 개를 올린다.
	 *
	 * @param key 올릴 객체 키
	 * @param bytes 파일 내용
	 * @param contentType 저장할 Content-Type
	 */
	private void upload(String key, byte[] bytes, String contentType) {
		s3.putObject(b -> b.bucket(bucket).key(key).contentType(contentType), RequestBody.fromBytes(bytes));
	}

}
