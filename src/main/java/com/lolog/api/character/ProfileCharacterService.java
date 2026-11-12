package com.lolog.api.character;

import com.lolog.api.comic.ComicPublisher;
import com.lolog.api.common.ApiException;
import com.lolog.api.common.SafeLog;
import com.lolog.api.friend.ProfileService;
import com.lolog.api.job.Job;
import com.lolog.api.job.JobRepository;
import com.lolog.api.notification.PushService;
import com.lolog.api.storage.ImageStorage;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 프로필 캐릭터: 회원이 사진 한 장으로 만든 본인 그림(characters.md 3장). 상태와 그림 키는 user_profiles.character_*에 있다.
// 사진 → PROCESSING + AVATAR 작업(lolog:comic) → 워커가 그림을 보내면 READY, 실패하면 FAILED. 원본 사진은 그때 바로 지운다.
// 다시 만드는 동안과 실패했을 때는 예전 그림(character_key)을 그대로 쓴다. 상태는 가장 최근에 올린 사진의 것이다.
@Service
public class ProfileCharacterService {

	private static final Logger log = LoggerFactory.getLogger(ProfileCharacterService.class);

	static final long MAX_PHOTO_BYTES = 10L * 1024 * 1024;
	static final int DAILY_LIMIT = 3;
	static final int AVATAR_SIZE = 768;
	static final int THUMB_SIZE = 256;
	static final int MAX_APPEARANCE = 200;

	private final JdbcTemplate jdbc;
	private final ImageStorage storage;
	private final ProfileService profiles;
	private final JobRepository jobs;
	private final ComicPublisher publisher;
	private final PushService push;
	private final CharacterDetacher detacher;
	private final CharacterViews views;

	public ProfileCharacterService(JdbcTemplate jdbc, ImageStorage storage, ProfileService profiles, JobRepository jobs,
			ComicPublisher publisher, PushService push, CharacterDetacher detacher, CharacterViews views) {
		this.jdbc = jdbc;
		this.storage = storage;
		this.profiles = profiles;
		this.jobs = jobs;
		this.publisher = publisher;
		this.push = push;
		this.detacher = detacher;
		this.views = views;
	}

	// PUT의 응답이자 ProfileResponse의 character. 만든 적이 없거나 지웠으면 null.
	// voice는 고른 목소리(기본 화자 id), 안 골랐으면 null(FR-CHAR-04).
	public record ProfileCharacterResponse(String status, String thumbnailUrl, OffsetDateTime updatedAt, String voice) {
	}

	private record State(String status, String thumbKey, OffsetDateTime updatedAt) {
	}

	// 사진을 올려 만들기·다시 만들기. 닉네임은 없어도 된다.
	@Transactional
	public ProfileCharacterResponse upload(long userId, byte[] photo) {
		if (photo == null || photo.length == 0) {
			throw ApiException.invalid("photo", "사진이 없어요.");
		}
		if (photo.length > MAX_PHOTO_BYTES) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "사진이 너무 큽니다.");
		}
		String contentType = photoType(photo);
		if (contentType == null) {
			throw ApiException.invalid("photo", "JPEG나 PNG 사진만 받아요.");
		}
		profiles.ensure(userId);
		State state = lock(userId);
		if ("PROCESSING".equals(state.status())) {
			throw new ApiException(HttpStatus.CONFLICT, "CHARACTER_IN_PROGRESS", "캐릭터를 만드는 중이에요.");
		}
		// 한국 시간 오늘 0시부터 받은 것(202). 실패한 작업은 세지 않는다. 만드는 중에 사용자가 지워서 멈춘 작업은
		// 실패가 아니라 받은 것이라 센다(characters.md "202를 받은 것을 세고").
		Long today = jdbc.queryForObject("""
				select count(*) from jobs where user_id = ? and job_type = 'AVATAR'
				  and (status <> 'FAILED' or fail_reason = '캐릭터를 지움')
				  and created_at >= (date_trunc('day', now() at time zone 'Asia/Seoul') at time zone 'Asia/Seoul')
				""", Long.class, userId);
		if (today != null && today >= DAILY_LIMIT) {
			throw new ApiException(HttpStatus.CONFLICT, "CHARACTER_LIMIT", "오늘은 캐릭터를 더 만들 수 없어요.");
		}

		storage.put(ImageStorage.profilePhotoKey(userId), photo, contentType);
		jdbc.update("""
				update user_profiles set character_status = 'PROCESSING', character_updated_at = current_timestamp,
				    updated_at = current_timestamp where user_id = ?
				""", userId);
		long jobId = jobs.save(Job.avatar(userId)).getId();
		afterCommit(() -> publisher.publishQuietly(jobId));
		return response(userId);
	}

	// 지우기. 없어도 204. 내 카드와 친구들 카드의 화자에서 이 캐릭터를 뺀다(이미 만든 만화는 그대로).
	@Transactional
	public void delete(long userId) {
		List<State> found = jdbc.query("select character_status, character_thumb_key, character_updated_at "
				+ "from user_profiles where user_id = ? for update", (rs, n) -> state(rs), userId);
		if (found.isEmpty() || found.getFirst().status() == null) {
			return;
		}
		jdbc.update("""
				update user_profiles set character_status = null, character_key = null, character_thumb_key = null,
				    character_appearance = null, character_voice = null, character_updated_at = current_timestamp,
				    updated_at = current_timestamp
				where user_id = ?
				""", userId);
		// 만드는 중이던 작업은 실패로 둔다. 워커가 나중에 사진을 찾으면 404라 그 메시지를 버린다.
		jdbc.update("update jobs set status = 'FAILED', fail_reason = '캐릭터를 지움', updated_at = current_timestamp "
				+ "where user_id = ? and job_type = 'AVATAR' and status = 'QUEUED'", userId);
		detacher.detachEverywhere(userId);
		afterCommit(() -> {
			deleteQuietly(ImageStorage.profileCharacterKey(userId));
			deleteQuietly(ImageStorage.profileCharacterThumbKey(userId));
			deleteQuietly(ImageStorage.profilePhotoKey(userId));
		});
	}

	// 워커가 원본 사진을 받는다. 만드는 중이 아니면(이미 끝남, 지움, 회원 없음) 404 CHARACTER_NOT_FOUND.
	@Transactional(readOnly = true)
	public ImageStorage.StoredObject photo(long userId) {
		List<String> status = jdbc.queryForList("select character_status from user_profiles where user_id = ?",
				String.class, userId);
		if (status.isEmpty() || !"PROCESSING".equals(status.getFirst())) {
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
			throw ApiException.invalid("file", "768×768 PNG여야 해요.");
		}
		String text = appearance == null ? "" : appearance.strip();
		if (text.isEmpty() || text.length() > MAX_APPEARANCE) {
			throw ApiException.invalid("appearance", "appearance는 1~200자예요.");
		}
		List<State> found = jdbc.query("select character_status, character_thumb_key, character_updated_at "
				+ "from user_profiles where user_id = ? for update", (rs, n) -> state(rs), userId);
		if (found.isEmpty() || !"PROCESSING".equals(found.getFirst().status())) {
			throw notFound();
		}
		String key = ImageStorage.profileCharacterKey(userId);
		String thumbKey = ImageStorage.profileCharacterThumbKey(userId);
		storage.putPng(key, png);
		storage.putPng(thumbKey, thumbnail(image));
		jdbc.update("""
				update user_profiles set character_status = 'READY', character_key = ?, character_thumb_key = ?,
				    character_appearance = ?, character_updated_at = current_timestamp, updated_at = current_timestamp
				where user_id = ?
				""", key, thumbKey, text, userId);
		jdbc.update("update jobs set status = 'DONE', updated_at = current_timestamp "
				+ "where user_id = ? and job_type = 'AVATAR' and status = 'QUEUED'", userId);
		afterCommit(() -> deleteQuietly(ImageStorage.profilePhotoKey(userId)));
		push.notify(userId, PushService.profileCharacterReady());
	}

	// avatar 작업 fail(POST /internal/jobs/{id}/fail). FAILED로 바꾸고(예전 그림은 그대로) 원본 사진을 지우고 알린다.
	// 이미 결과나 fail을 받은 작업이면 무시한다.
	@Transactional
	public void fail(long jobId, String reason) {
		Job job = jobs.findByIdForUpdate(jobId).orElseThrow(() -> ApiException.invalid(null, "작업을 찾을 수 없습니다."));
		if (job.isFinished()) {
			return;
		}
		job.fail(reason);
		long userId = job.getUserId();
		List<State> found = jdbc.query("select character_status, character_thumb_key, character_updated_at "
				+ "from user_profiles where user_id = ? for update", (rs, n) -> state(rs), userId);
		if (found.isEmpty() || !"PROCESSING".equals(found.getFirst().status())) {
			return;
		}
		jdbc.update("""
				update user_profiles set character_status = 'FAILED', character_updated_at = current_timestamp,
				    updated_at = current_timestamp where user_id = ?
				""", userId);
		afterCommit(() -> deleteQuietly(ImageStorage.profilePhotoKey(userId)));
		push.notify(userId, PushService.profileCharacterFailed());
	}

	// 프로필 캐릭터 목소리 고르기(초안). 기본 화자 목소리 중 하나이고 null이면 지운다.
	// 프로필 캐릭터가 없으면 404 CHARACTER_NOT_FOUND, 없거나 목소리가 없는 기본 화자 id면 400 UNKNOWN_CHARACTER.
	@Transactional
	public void chooseVoice(long userId, String voice) {
		// "프로필 캐릭터가 있을 때"는 쓸 수 있는 그림이 있을 때다. 다시 만드는 중·실패여도 예전 그림이 있으면 고를 수 있다.
		List<String> key = jdbc.queryForList("select character_key from user_profiles where user_id = ? for update",
				String.class, userId);
		if (key.isEmpty() || key.getFirst() == null) {
			throw new ApiException(HttpStatus.NOT_FOUND, "CHARACTER_NOT_FOUND", "프로필 캐릭터가 없어요.");
		}
		if (voice != null) {
			Boolean exists = jdbc.queryForObject(
					"select exists (select 1 from default_characters where id = ? and voice_key is not null)", Boolean.class,
					voice);
			if (!Boolean.TRUE.equals(exists)) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_CHARACTER", "고를 수 없는 목소리예요.", "voice");
			}
		}
		jdbc.update("update user_profiles set character_voice = ?, updated_at = current_timestamp where user_id = ?", voice,
				userId);
	}

	// ProfileResponse.character와 같은 값
	public ProfileCharacterResponse response(long userId) {
		return views.profileResponse(userId);
	}

	private State lock(long userId) {
		return jdbc.queryForObject("select character_status, character_thumb_key, character_updated_at "
				+ "from user_profiles where user_id = ? for update", (rs, n) -> state(rs), userId);
	}

	private static State state(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new State(rs.getString("character_status"), rs.getString("character_thumb_key"),
				rs.getObject("character_updated_at", OffsetDateTime.class));
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

	private void deleteQuietly(String key) {
		try {
			storage.delete(key);
		}
		catch (Exception e) {
			log.warn("캐릭터 파일을 지우지 못했습니다.", SafeLog.withoutMessages(e));
		}
	}

	private static void afterCommit(Runnable task) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					task.run();
				}
			});
		}
		else {
			task.run();
		}
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "CHARACTER_NOT_FOUND", "받을 사진이 없습니다.");
	}

}
