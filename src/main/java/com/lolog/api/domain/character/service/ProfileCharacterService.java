package com.lolog.api.domain.character.service;

import com.lolog.api.domain.comic.service.ComicPublisher;
import com.lolog.api.domain.friend.service.ProfileService;
import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.storage.StorageDeletions;
import com.lolog.api.global.transaction.AfterCommit;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 프로필 캐릭터: 회원이 사진 한 장으로 만든 본인 그림(characters.md 3장). 상태와 그림 키는 user_profiles.character_*에 있다.
// 사진 → PROCESSING + AVATAR 작업(lolog:comic) → 워커가 그림을 보내면 READY, 실패하면 FAILED. 원본 사진은 그때 바로 지운다.
// 다시 만드는 동안과 실패했을 때는 예전 그림(character_key)을 그대로 쓴다. 상태는 가장 최근에 올린 사진의 것이다.
@Service
public class ProfileCharacterService {

	// Nginx 본문 한도(5MB, deployment.md 2장)에 맞춘다. 넘으면 Nginx가 먼저 HTML 413을 줘서 앱이 오류를 읽지 못한다(characters.md 4장, 정함 11-14).
	public static final long MAX_PHOTO_BYTES = 5L * 1024 * 1024;
	static final int DAILY_LIMIT = 3;
	static final int AVATAR_SIZE = 768;
	static final int THUMB_SIZE = 256;
	static final int MAX_APPEARANCE = 200;

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	private final UserProfileRepository userProfiles;
	private final ImageStorage storage;
	private final StorageDeletions deletions;
	private final ProfileService profiles;
	private final JobRepository jobs;
	private final ComicPublisher publisher;
	private final PushService push;
	private final CharacterDetacher detacher;
	private final CharacterViews views;
	private final VoiceCloneService voiceClones;
	private final DefaultCharacterService defaultCharacters;

	public ProfileCharacterService(UserProfileRepository userProfiles, ImageStorage storage, StorageDeletions deletions,
			ProfileService profiles, JobRepository jobs, ComicPublisher publisher, PushService push, CharacterDetacher detacher,
			CharacterViews views, VoiceCloneService voiceClones, DefaultCharacterService defaultCharacters) {
		this.voiceClones = voiceClones;
		this.defaultCharacters = defaultCharacters;
		this.userProfiles = userProfiles;
		this.storage = storage;
		this.deletions = deletions;
		this.profiles = profiles;
		this.jobs = jobs;
		this.publisher = publisher;
		this.push = push;
		this.detacher = detacher;
		this.views = views;
	}

	// PUT의 응답이자 ProfileResponse의 character. 만든 적이 없거나 지웠으면 null.
	// voice는 고른 목소리(기본 화자 id 또는 내 목소리 "self"), 안 골랐으면 null(FR-CHAR-04·05).
	// voiceClone은 내 목소리 상태이고 만든 적이 없으면 null(FR-CHAR-05).
	public record ProfileCharacterResponse(String status, String thumbnailUrl, OffsetDateTime updatedAt, String voice,
			VoiceCloneService.VoiceCloneView voiceClone) {
	}

	// 사진을 올려 만들기·다시 만들기. 닉네임은 없어도 된다.
	@Transactional
	public ProfileCharacterResponse upload(long userId, byte[] photo) {
		if (photo == null || photo.length == 0) {
			throw ApiException.invalid("photo");
		}
		if (photo.length > MAX_PHOTO_BYTES) {
			throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
		}
		String contentType = photoType(photo);
		if (contentType == null) {
			throw ApiException.invalid("photo");
		}
		profiles.ensure(userId);
		UserProfile profile = lock(userId);
		if (UserProfile.CHARACTER_PROCESSING.equals(profile.getCharacterStatus())) {
			throw new ApiException(ErrorCode.CHARACTER_IN_PROGRESS);
		}
		// 한국 시간 오늘 0시부터 받은 것(202). 실패한 작업은 세지 않는다. 만드는 중에 사용자가 지워서 멈춘 작업은
		// 실패가 아니라 받은 것이라 센다(characters.md "202를 받은 것을 세고").
		OffsetDateTime midnight = ZonedDateTime.now(SEOUL).truncatedTo(ChronoUnit.DAYS).toOffsetDateTime();
		if (jobs.countAvatarJobsSince(userId, midnight) >= DAILY_LIMIT) {
			throw new ApiException(ErrorCode.CHARACTER_LIMIT);
		}

		String photoKey = ImageStorage.profilePhotoKey(userId);
		// 예전 사진을 아직 못 지웠으면 그 지우기를 빼고 올린다(새 사진이 지워지지 않게).
		deletions.cancel(photoKey);
		storage.put(photoKey, photo, contentType);
		profile.startCharacter();
		long jobId = jobs.save(Job.avatar(userId)).getId();
		AfterCommit.run(() -> publisher.publishQuietly(jobId));
		return response(userId);
	}

	// 지우기. 없어도 204. 내 카드와 친구들 카드의 화자에서 이 캐릭터를 뺀다(이미 만든 만화는 그대로).
	// 내 목소리도 함께 지운다(characters.md "내 목소리" 보관과 삭제).
	@Transactional
	public void delete(long userId) {
		voiceClones.delete(userId);
		UserProfile profile = userProfiles.findByIdForUpdate(userId).orElse(null);
		if (profile == null || profile.getCharacterStatus() == null) {
			return;
		}
		profile.removeCharacter();
		// 만드는 중이던 작업은 실패로 둔다. 워커가 나중에 사진을 찾으면 404라 그 메시지를 버린다.
		jobs.failQueued(userId, "AVATAR", "캐릭터를 지움");
		detacher.detachEverywhere(userId);
		deletions.schedule(ImageStorage.profileCharacterKey(userId));
		deletions.schedule(ImageStorage.profileCharacterThumbKey(userId));
		deletions.schedule(ImageStorage.profilePhotoKey(userId));
	}

	// 워커가 원본 사진을 받는다. 만드는 중이 아니면(이미 끝남, 지움, 회원 없음) 404 CHARACTER_NOT_FOUND.
	@Transactional(readOnly = true)
	public ImageStorage.StoredObject photo(long userId) {
		String status = userProfiles.findById(userId).map(UserProfile::getCharacterStatus).orElse(null);
		if (!UserProfile.CHARACTER_PROCESSING.equals(status)) {
			throw notFound();
		}
		try {
			return storage.getObject(ImageStorage.profilePhotoKey(userId));
		}
		catch (software.amazon.awssdk.services.s3.model.NoSuchKeyException e) {
			throw notFound();
		}
	}

	// 워커가 그림(768 PNG)과 외모 글을 보낸다. READY로 바꾸고 256 그림을 만들고 원본 사진을 지운 뒤 본인에게 알린다.
	@Transactional
	public void avatar(long userId, byte[] png, String appearance) {
		BufferedImage image = readPng(png);
		if (image == null || image.getWidth() != AVATAR_SIZE || image.getHeight() != AVATAR_SIZE) {
			throw ApiException.invalid("file");
		}
		String text = appearance == null ? "" : appearance.strip();
		if (text.isEmpty() || text.length() > MAX_APPEARANCE) {
			throw ApiException.invalid("appearance");
		}
		UserProfile profile = userProfiles.findByIdForUpdate(userId)
			.filter(p -> UserProfile.CHARACTER_PROCESSING.equals(p.getCharacterStatus()))
			.orElseThrow(ProfileCharacterService::notFound);
		String key = ImageStorage.profileCharacterKey(userId);
		String thumbKey = ImageStorage.profileCharacterThumbKey(userId);
		// 지운 뒤 다시 만든 그림이면 예전 지우기를 빼고 올린다.
		deletions.cancel(key);
		deletions.cancel(thumbKey);
		storage.putPng(key, png);
		storage.putPng(thumbKey, thumbnail(image));
		profile.characterReady(key, thumbKey, text);
		jobs.completeQueued(userId, "AVATAR");
		deletions.schedule(ImageStorage.profilePhotoKey(userId));
		push.notify(userId, PushService.profileCharacterReady());
	}

	// avatar 작업 fail(POST /internal/jobs/{id}/fail). FAILED로 바꾸고(예전 그림은 그대로) 원본 사진을 지우고 알린다.
	// 이미 결과나 fail을 받은 작업이면 무시한다.
	@Transactional
	public void fail(long jobId, String reason) {
		Job job = jobs.findByIdForUpdate(jobId).orElseThrow(() -> ApiException.invalid(null));
		if (job.isFinished()) {
			return;
		}
		job.fail(reason);
		long userId = job.getUserId();
		UserProfile profile = userProfiles.findByIdForUpdate(userId).orElse(null);
		if (profile == null || !UserProfile.CHARACTER_PROCESSING.equals(profile.getCharacterStatus())) {
			return;
		}
		profile.characterFailed();
		deletions.schedule(ImageStorage.profilePhotoKey(userId));
		push.notify(userId, PushService.profileCharacterFailed());
	}

	// 프로필 캐릭터 목소리 고르기(초안). 기본 화자 목소리 중 하나나 내 목소리("self", 정함 11-14)이고 null이면 지운다.
	// 프로필 캐릭터가 없으면 404 CHARACTER_NOT_FOUND, 없거나 목소리가 없는 기본 화자 id면 400 UNKNOWN_CHARACTER.
	// "self"는 내 목소리를 다 만들었을 때(READY)만 고를 수 있고, 아니면 409 VOICE_CLONE_NOT_READY(초안).
	@Transactional
	public void chooseVoice(long userId, String voice) {
		// "프로필 캐릭터가 있을 때"는 쓸 수 있는 그림이 있을 때다. 다시 만드는 중·실패여도 예전 그림이 있으면 고를 수 있다.
		UserProfile profile = userProfiles.findByIdForUpdate(userId)
			.filter(p -> p.getCharacterKey() != null)
			.orElseThrow(() -> new ApiException(ErrorCode.CHARACTER_NOT_FOUND));
		if (UserProfile.SELF_VOICE.equals(voice)) {
			if (!UserProfile.CHARACTER_READY.equals(profile.getVoiceCloneStatus())) {
				throw new ApiException(ErrorCode.VOICE_CLONE_NOT_READY, "voice");
			}
		}
		else if (voice != null) {
			if (!defaultCharacters.hasVoice(voice)) {
				throw new ApiException(ErrorCode.UNKNOWN_CHARACTER, "voice");
			}
		}
		profile.chooseVoice(voice);
	}

	// ProfileResponse.character와 같은 값
	public ProfileCharacterResponse response(long userId) {
		return views.profileResponse(userId);
	}

	// 프로필 행을 잠그고 읽는다.
	private UserProfile lock(long userId) {
		return userProfiles.findByIdForUpdate(userId).orElseThrow();
	}

	// 첫 바이트로 JPEG·PNG를 가린다. 둘 다 아니면 null.
	static String photoType(byte[] bytes) {
		if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
			return "image/jpeg";
		}
		if (bytes.length >= 8 && (bytes[0] & 0xFF) == 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G') {
			return "image/png";
		}
		return null;
	}

	// PNG면 그림으로 읽는다. 아니거나 못 읽으면 null.
	private static BufferedImage readPng(byte[] bytes) {
		if (bytes == null || !"image/png".equals(photoType(bytes))) {
			return null;
		}
		try {
			return ImageIO.read(new ByteArrayInputStream(bytes));
		}
		catch (IOException e) {
			return null;
		}
	}

	// 768 그림을 256 PNG로 줄인다(characters.md 3장).
	static byte[] thumbnail(BufferedImage image) {
		BufferedImage small = new BufferedImage(THUMB_SIZE, THUMB_SIZE, BufferedImage.TYPE_INT_ARGB);
		var g = small.createGraphics();
		try {
			g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(image.getScaledInstance(THUMB_SIZE, THUMB_SIZE, Image.SCALE_SMOOTH), 0, 0, null);
		}
		finally {
			g.dispose();
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try {
			ImageIO.write(small, "png", out);
		}
		catch (IOException e) {
			throw new IllegalStateException("썸네일을 만들지 못했습니다.", e);
		}
		return out.toByteArray();
	}

	// CHARACTER_NOT_FOUND 예외를 만든다.
	private static ApiException notFound() {
		return new ApiException(ErrorCode.CHARACTER_NOT_FOUND);
	}

}
