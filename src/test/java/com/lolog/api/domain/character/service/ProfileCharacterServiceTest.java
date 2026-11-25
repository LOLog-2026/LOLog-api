package com.lolog.api.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProfileCharacterService")
class ProfileCharacterServiceTest {

	private static final long USER_ID = 1L;
	private static final long JOB_ID = 500L;

	@Mock
	private UserProfileRepository userProfiles;

	@Mock
	private ImageStorage storage;

	@Mock
	private StorageDeletions deletions;

	@Mock
	private ProfileService profiles;

	@Mock
	private JobRepository jobs;

	@Mock
	private ComicPublisher publisher;

	@Mock
	private PushService push;

	@Mock
	private CharacterDetacher detacher;

	@Mock
	private CharacterViews views;

	@Mock
	private VoiceCloneService voiceClones;

	@Mock
	private DefaultCharacterService defaultCharacters;

	@InjectMocks
	private ProfileCharacterService service;

	// ---- upload ----

	@Test
	@DisplayName("빈 사진이면 photo 칸 INVALID_REQUEST이고 프로필을 건드리지 않는다")
	void 빈_사진이면_INVALID_REQUEST() {
		// given
		byte[] photo = new byte[0];

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, photo));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("photo");
		verifyNoInteractions(profiles, userProfiles, storage);
	}

	@Test
	@DisplayName("null 사진이면 photo 칸 INVALID_REQUEST다")
	void null_사진이면_INVALID_REQUEST() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("photo");
	}

	@Test
	@DisplayName("5MB를 넘는 사진은 PAYLOAD_TOO_LARGE다")
	void 큰_사진은_PAYLOAD_TOO_LARGE() {
		// given
		byte[] photo = jpeg((int) ProfileCharacterService.MAX_PHOTO_BYTES + 1);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, photo));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
		verifyNoInteractions(profiles, storage);
	}

	@Test
	@DisplayName("JPEG도 PNG도 아니면 photo 칸 INVALID_REQUEST다")
	void JPEG_PNG가_아니면_INVALID_REQUEST() {
		// given
		byte[] photo = "GIF89a-not-allowed".getBytes();

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, photo));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("photo");
		verifyNoInteractions(profiles, storage);
	}

	@Test
	@DisplayName("만드는 중이면 CHARACTER_IN_PROGRESS이고 사진을 올리지 않는다")
	void 만드는_중이면_CHARACTER_IN_PROGRESS() {
		// given
		UserProfile profile = profile(UserProfile.CHARACTER_PROCESSING, null);
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, jpeg(10)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHARACTER_IN_PROGRESS);
		verify(profiles).ensure(USER_ID);
		verify(jobs, never()).countAvatarJobsSince(anyLong(), any());
		verifyNoInteractions(storage, publisher);
	}

	@Test
	@DisplayName("오늘 3번 받았으면 CHARACTER_LIMIT이고 작업을 만들지 않는다")
	void 오늘_3번이면_CHARACTER_LIMIT() {
		// given
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile(null, null)));
		given(jobs.countAvatarJobsSince(eq(USER_ID), any())).willReturn(3L);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, jpeg(10)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHARACTER_LIMIT);
		verify(jobs, never()).save(any());
		verifyNoInteractions(storage, publisher);
	}

	@Test
	@DisplayName("오늘 2번이면 받고, 지우기를 먼저 뺀 뒤 사진을 올리고 AVATAR 작업을 큐에 넣는다")
	void 사진을_받으면_PROCESSING과_AVATAR_작업() {
		// given
		UserProfile profile = profile(UserProfile.CHARACTER_FAILED, "users/1/character.png");
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));
		given(jobs.countAvatarJobsSince(eq(USER_ID), any())).willReturn(2L);
		given(jobs.save(any(Job.class))).willAnswer(inv -> {
			Job job = inv.getArgument(0);
			ReflectionTestUtils.setField(job, "id", JOB_ID);
			return job;
		});
		ProfileCharacterService.ProfileCharacterResponse expected = new ProfileCharacterService.ProfileCharacterResponse(
				UserProfile.CHARACTER_PROCESSING, null, OffsetDateTime.now(), null, null);
		given(views.profileResponse(USER_ID)).willReturn(expected);
		byte[] photo = png(16, 16);
		String photoKey = ImageStorage.profilePhotoKey(USER_ID);

		// when
		ProfileCharacterService.ProfileCharacterResponse response = service.upload(USER_ID, photo);

		// then
		assertThat(response).isSameAs(expected);
		InOrder order = inOrder(deletions, storage);
		order.verify(deletions).cancel(photoKey);
		order.verify(storage).put(photoKey, photo, "image/png");
		assertThat(profile.getCharacterStatus()).isEqualTo(UserProfile.CHARACTER_PROCESSING);
		// 예전 그림은 그대로 둔다.
		assertThat(profile.getCharacterKey()).isEqualTo("users/1/character.png");
		ArgumentCaptor<Job> job = ArgumentCaptor.forClass(Job.class);
		verify(jobs).save(job.capture());
		assertThat(job.getValue().getJobType()).isEqualTo("AVATAR");
		assertThat(job.getValue().getUserId()).isEqualTo(USER_ID);
		// 트랜잭션 밖이라 AfterCommit이 바로 실행한다.
		verify(publisher).publishQuietly(JOB_ID);
	}

	@Test
	@DisplayName("오늘 0시 기준 시각(한국 시간)으로 센다")
	void 한국_시간_0시부터_센다() {
		// given
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile(null, null)));
		given(jobs.countAvatarJobsSince(eq(USER_ID), any())).willReturn(3L);

		// when
		catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, jpeg(10)));

		// then
		ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
		verify(jobs).countAvatarJobsSince(eq(USER_ID), since.capture());
		assertThat(since.getValue().getOffset().getTotalSeconds()).isEqualTo(9 * 3600);
		assertThat(since.getValue().getHour()).isZero();
		assertThat(since.getValue().getMinute()).isZero();
	}

	// ---- delete ----

	@Test
	@DisplayName("프로필이 없어도 내 목소리 지우기는 부르고 나머지는 하지 않는다")
	void 프로필이_없으면_아무것도_안_한다() {
		// given
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.empty());

		// when
		service.delete(USER_ID);

		// then
		verify(voiceClones).delete(USER_ID);
		verify(jobs, never()).failQueued(anyLong(), anyString(), anyString());
		verifyNoInteractions(detacher, deletions);
	}

	@Test
	@DisplayName("캐릭터를 만든 적이 없으면 아무것도 지우지 않는다")
	void 캐릭터가_없으면_지우지_않는다() {
		// given
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile(null, null)));

		// when
		service.delete(USER_ID);

		// then
		verify(voiceClones).delete(USER_ID);
		verifyNoInteractions(detacher, deletions);
	}

	@Test
	@DisplayName("지우면 캐릭터를 비우고 작업을 실패로 두고 카드에서 떼고 그림·사진 지우기를 맡긴다")
	void 지우면_모두_정리한다() {
		// given
		UserProfile profile = profile(UserProfile.CHARACTER_READY, "users/1/character.png");
		ReflectionTestUtils.setField(profile, "characterVoice", "young-f-01");
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		service.delete(USER_ID);

		// then
		assertThat(profile.getCharacterStatus()).isNull();
		assertThat(profile.getCharacterKey()).isNull();
		assertThat(profile.getCharacterVoice()).isNull();
		verify(jobs).failQueued(eq(USER_ID), eq("AVATAR"), anyString());
		verify(detacher).detachEverywhere(USER_ID);
		verify(deletions).schedule(ImageStorage.profileCharacterKey(USER_ID));
		verify(deletions).schedule(ImageStorage.profileCharacterThumbKey(USER_ID));
		verify(deletions).schedule(ImageStorage.profilePhotoKey(USER_ID));
	}

	// ---- photo ----

	@Test
	@DisplayName("만드는 중이 아니면 원본 사진을 주지 않고 CHARACTER_NOT_FOUND다")
	void 만드는_중이_아니면_사진은_CHARACTER_NOT_FOUND() {
		// given
		given(userProfiles.findById(USER_ID)).willReturn(Optional.of(profile(UserProfile.CHARACTER_READY, "k")));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.photo(USER_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHARACTER_NOT_FOUND);
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("만드는 중인데 저장소에 사진이 없으면 CHARACTER_NOT_FOUND다")
	void 저장소에_사진이_없으면_CHARACTER_NOT_FOUND() {
		// given
		given(userProfiles.findById(USER_ID)).willReturn(Optional.of(profile(UserProfile.CHARACTER_PROCESSING, null)));
		given(storage.getObject(ImageStorage.profilePhotoKey(USER_ID))).willThrow(NoSuchKeyException.builder().build());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.photo(USER_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHARACTER_NOT_FOUND);
	}

	@Test
	@DisplayName("만드는 중이면 저장소의 원본 사진을 준다")
	void 만드는_중이면_사진을_준다() {
		// given
		given(userProfiles.findById(USER_ID)).willReturn(Optional.of(profile(UserProfile.CHARACTER_PROCESSING, null)));
		ImageStorage.StoredObject stored = new ImageStorage.StoredObject(new byte[] { 1 }, "image/jpeg");
		given(storage.getObject(ImageStorage.profilePhotoKey(USER_ID))).willReturn(stored);

		// when
		ImageStorage.StoredObject result = service.photo(USER_ID);

		// then
		assertThat(result).isSameAs(stored);
	}

	// ---- avatar ----

	@Test
	@DisplayName("PNG가 아니면 file 칸 INVALID_REQUEST다")
	void PNG가_아니면_file_INVALID_REQUEST() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.avatar(USER_ID, jpeg(100), "웃는 얼굴"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("file");
		verifyNoInteractions(userProfiles, storage);
	}

	@Test
	@DisplayName("768×768이 아닌 PNG는 file 칸 INVALID_REQUEST다")
	void 크기가_틀린_PNG는_INVALID_REQUEST() {
		// given
		byte[] png = png(256, 256);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.avatar(USER_ID, png, "웃는 얼굴"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("file");
	}

	@Test
	@DisplayName("외모 글이 공백뿐이면 appearance 칸 INVALID_REQUEST다")
	void 외모_글이_비면_INVALID_REQUEST() {
		// given
		byte[] png = png(768, 768);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.avatar(USER_ID, png, "   "));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("appearance");
		verifyNoInteractions(userProfiles);
	}

	@Test
	@DisplayName("외모 글이 201자면 appearance 칸 INVALID_REQUEST다")
	void 외모_글이_201자면_INVALID_REQUEST() {
		// given
		byte[] png = png(768, 768);

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.avatar(USER_ID, png, "a".repeat(ProfileCharacterService.MAX_APPEARANCE + 1)));

		// then
		assertThat(e.getField()).isEqualTo("appearance");
	}

	@Test
	@DisplayName("만드는 중이 아니면 그림을 받지 않고 CHARACTER_NOT_FOUND다")
	void 만드는_중이_아니면_그림은_CHARACTER_NOT_FOUND() {
		// given
		byte[] png = png(768, 768);
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile(UserProfile.CHARACTER_FAILED, null)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.avatar(USER_ID, png, "웃는 얼굴"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHARACTER_NOT_FOUND);
		verifyNoInteractions(storage, push);
	}

	@Test
	@DisplayName("그림을 받으면 원본과 256 그림을 올리고 READY로 바꾸고 사진 지우기를 맡기고 알린다")
	void 그림을_받으면_READY() throws IOException {
		// given
		byte[] png = png(768, 768);
		UserProfile profile = profile(UserProfile.CHARACTER_PROCESSING, null);
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));
		String key = ImageStorage.profileCharacterKey(USER_ID);
		String thumbKey = ImageStorage.profileCharacterThumbKey(USER_ID);
		String appearance = "a".repeat(ProfileCharacterService.MAX_APPEARANCE);

		// when
		service.avatar(USER_ID, png, "  " + appearance + "  ");

		// then
		InOrder order = inOrder(deletions, storage);
		order.verify(deletions).cancel(key);
		order.verify(deletions).cancel(thumbKey);
		order.verify(storage).putPng(key, png);
		ArgumentCaptor<byte[]> thumb = ArgumentCaptor.forClass(byte[].class);
		verify(storage).putPng(eq(thumbKey), thumb.capture());
		BufferedImage small = ImageIO.read(new ByteArrayInputStream(thumb.getValue()));
		assertThat(small.getWidth()).isEqualTo(ProfileCharacterService.THUMB_SIZE);
		assertThat(small.getHeight()).isEqualTo(ProfileCharacterService.THUMB_SIZE);
		assertThat(profile.getCharacterStatus()).isEqualTo(UserProfile.CHARACTER_READY);
		assertThat(profile.getCharacterKey()).isEqualTo(key);
		assertThat(profile.getCharacterThumbKey()).isEqualTo(thumbKey);
		assertThat(profile.getCharacterAppearance()).isEqualTo(appearance);
		verify(jobs).completeQueued(USER_ID, "AVATAR");
		verify(deletions).schedule(ImageStorage.profilePhotoKey(USER_ID));
		verify(push).notify(USER_ID, PushService.profileCharacterReady());
	}

	// ---- fail ----

	@Test
	@DisplayName("없는 작업이면 field 없는 INVALID_REQUEST다")
	void 없는_작업이면_INVALID_REQUEST() {
		// given
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.fail(JOB_ID, "오류"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isNull();
	}

	@Test
	@DisplayName("이미 끝난 작업이면 무시한다")
	void 끝난_작업이면_무시한다() {
		// given
		Job job = Job.avatar(USER_ID);
		job.complete();
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));

		// when
		service.fail(JOB_ID, "오류");

		// then
		assertThat(job.getStatus()).isEqualTo("DONE");
		verifyNoInteractions(userProfiles, deletions, push);
	}

	@Test
	@DisplayName("만드는 중이 아니면 작업만 실패로 두고 프로필과 알림은 건드리지 않는다")
	void 만드는_중이_아니면_작업만_실패() {
		// given
		Job job = Job.avatar(USER_ID);
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));
		UserProfile profile = profile(UserProfile.CHARACTER_READY, "k");
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		service.fail(JOB_ID, "오류");

		// then
		assertThat(job.getStatus()).isEqualTo("FAILED");
		assertThat(profile.getCharacterStatus()).isEqualTo(UserProfile.CHARACTER_READY);
		verifyNoInteractions(deletions, push);
	}

	@Test
	@DisplayName("만드는 중이면 FAILED로 바꾸고 예전 그림은 두고 사진 지우기를 맡기고 알린다")
	void 만드는_중이면_FAILED() {
		// given
		Job job = Job.avatar(USER_ID);
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));
		UserProfile profile = profile(UserProfile.CHARACTER_PROCESSING, "users/1/character.png");
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		service.fail(JOB_ID, "얼굴을 찾지 못함");

		// then
		assertThat(job.getStatus()).isEqualTo("FAILED");
		assertThat(job.getFailReason()).isEqualTo("얼굴을 찾지 못함");
		assertThat(profile.getCharacterStatus()).isEqualTo(UserProfile.CHARACTER_FAILED);
		assertThat(profile.getCharacterKey()).isEqualTo("users/1/character.png");
		verify(deletions).schedule(ImageStorage.profilePhotoKey(USER_ID));
		verify(push).notify(USER_ID, PushService.profileCharacterFailed());
	}

	// ---- chooseVoice ----

	@Test
	@DisplayName("쓸 수 있는 그림이 없으면 목소리를 고를 수 없고 CHARACTER_NOT_FOUND다")
	void 그림이_없으면_CHARACTER_NOT_FOUND() {
		// given
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile(UserProfile.CHARACTER_PROCESSING, null)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.chooseVoice(USER_ID, "young-f-01"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHARACTER_NOT_FOUND);
		verifyNoInteractions(defaultCharacters);
	}

	@Test
	@DisplayName("내 목소리가 READY가 아니면 self는 voice 칸 VOICE_CLONE_NOT_READY다")
	void self인데_READY가_아니면_VOICE_CLONE_NOT_READY() {
		// given
		UserProfile profile = profile(UserProfile.CHARACTER_READY, "k");
		ReflectionTestUtils.setField(profile, "voiceCloneStatus", UserProfile.CHARACTER_PROCESSING);
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.chooseVoice(USER_ID, UserProfile.SELF_VOICE));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOICE_CLONE_NOT_READY);
		assertThat(e.getField()).isEqualTo("voice");
		assertThat(profile.getCharacterVoice()).isNull();
	}

	@Test
	@DisplayName("내 목소리가 READY면 self를 고른다")
	void self를_고른다() {
		// given
		UserProfile profile = profile(UserProfile.CHARACTER_FAILED, "k");
		ReflectionTestUtils.setField(profile, "voiceCloneStatus", UserProfile.CHARACTER_READY);
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		service.chooseVoice(USER_ID, UserProfile.SELF_VOICE);

		// then
		assertThat(profile.getCharacterVoice()).isEqualTo(UserProfile.SELF_VOICE);
		verifyNoInteractions(defaultCharacters);
	}

	@Test
	@DisplayName("목소리가 없는 기본 화자면 voice 칸 UNKNOWN_CHARACTER다")
	void 목소리_없는_기본_화자면_UNKNOWN_CHARACTER() {
		// given
		UserProfile profile = profile(UserProfile.CHARACTER_READY, "k");
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));
		given(defaultCharacters.hasVoice("young-f-09")).willReturn(false);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.chooseVoice(USER_ID, "young-f-09"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.UNKNOWN_CHARACTER);
		assertThat(e.getField()).isEqualTo("voice");
		assertThat(profile.getCharacterVoice()).isNull();
	}

	@Test
	@DisplayName("목소리가 있는 기본 화자를 고른다")
	void 기본_화자_목소리를_고른다() {
		// given
		UserProfile profile = profile(UserProfile.CHARACTER_READY, "k");
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));
		given(defaultCharacters.hasVoice("young-f-01")).willReturn(true);

		// when
		service.chooseVoice(USER_ID, "young-f-01");

		// then
		assertThat(profile.getCharacterVoice()).isEqualTo("young-f-01");
	}

	@Test
	@DisplayName("null이면 고른 목소리를 지우고 기본 화자는 보지 않는다")
	void null이면_목소리를_지운다() {
		// given
		UserProfile profile = profile(UserProfile.CHARACTER_READY, "k");
		ReflectionTestUtils.setField(profile, "characterVoice", "young-f-01");
		given(userProfiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		service.chooseVoice(USER_ID, null);

		// then
		assertThat(profile.getCharacterVoice()).isNull();
		verifyNoInteractions(defaultCharacters);
	}

	// ---- photoType ----

	@Test
	@DisplayName("첫 바이트로 JPEG·PNG를 가리고 나머지는 null이다")
	void 첫_바이트로_종류를_가린다() {
		// when & then
		assertThat(ProfileCharacterService.photoType(jpeg(3))).isEqualTo("image/jpeg");
		assertThat(ProfileCharacterService.photoType(png(1, 1))).isEqualTo("image/png");
		assertThat(ProfileCharacterService.photoType(new byte[] { (byte) 0xFF, (byte) 0xD8 })).isNull();
		assertThat(ProfileCharacterService.photoType("hello world".getBytes())).isNull();
	}

	// ---- 도움 ----

	// 프로필 캐릭터 상태와 그림 키를 정한 프로필
	private static UserProfile profile(String status, String characterKey) {
		UserProfile profile = new UserProfile();
		ReflectionTestUtils.setField(profile, "userId", USER_ID);
		ReflectionTestUtils.setField(profile, "characterStatus", status);
		ReflectionTestUtils.setField(profile, "characterKey", characterKey);
		return profile;
	}

	// JPEG 머리말로 시작하는 length 바이트
	private static byte[] jpeg(int length) {
		byte[] bytes = new byte[length];
		bytes[0] = (byte) 0xFF;
		bytes[1] = (byte) 0xD8;
		bytes[2] = (byte) 0xFF;
		return bytes;
	}

	// 가로·세로를 정한 진짜 PNG
	private static byte[] png(int width, int height) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB), "png", out);
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}

}
