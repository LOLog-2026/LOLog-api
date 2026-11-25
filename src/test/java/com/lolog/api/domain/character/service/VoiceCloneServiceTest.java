package com.lolog.api.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.domain.user.entity.UserProfile;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.storage.StorageDeletions;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
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
@DisplayName("VoiceCloneService")
class VoiceCloneServiceTest {

	private static final long USER_ID = 1L;
	private static final long JOB_ID = 700L;

	@Mock
	private UserProfileRepository profiles;

	@Mock
	private RecordingRepository recordings;

	@Mock
	private ImageStorage storage;

	@Mock
	private StorageDeletions deletions;

	@Mock
	private JobRepository jobs;

	@Mock
	private VoiceClonePublisher publisher;

	@Mock
	private PushService push;

	@Mock
	private CharacterViews views;

	@InjectMocks
	private VoiceCloneService service;

	// ---- upload ----

	@Test
	@DisplayName("큐가 꺼져 있으면 다른 것을 보기 전에 VOICE_CLONE_UNAVAILABLE이다")
	void 큐가_꺼져_있으면_VOICE_CLONE_UNAVAILABLE() {
		// given
		given(publisher.enabled()).willReturn(false);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, null, null));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOICE_CLONE_UNAVAILABLE);
		verifyNoInteractions(profiles, recordings, storage);
	}

	@Test
	@DisplayName("동의가 true가 아니면 consent 칸 INVALID_REQUEST다")
	void 동의가_없으면_INVALID_REQUEST() {
		// given
		given(publisher.enabled()).willReturn(true);

		// when
		ApiException nullConsent = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, wav(20), null));
		ApiException falseConsent = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, wav(20), false));

		// then
		assertThat(nullConsent.getField()).isEqualTo("consent");
		assertThat(falseConsent.getField()).isEqualTo("consent");
		assertThat(falseConsent.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		verifyNoInteractions(profiles, storage);
	}

	@Test
	@DisplayName("빈 녹음이면 audio 칸 INVALID_REQUEST다")
	void 빈_녹음이면_INVALID_REQUEST() {
		// given
		given(publisher.enabled()).willReturn(true);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, new byte[0], true));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("audio");
	}

	@Test
	@DisplayName("4MB를 넘는 녹음은 PAYLOAD_TOO_LARGE다")
	void 큰_녹음은_PAYLOAD_TOO_LARGE() {
		// given
		given(publisher.enabled()).willReturn(true);
		byte[] audio = wav((int) VoiceCloneService.MAX_AUDIO_BYTES + 1);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, audio, true));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
		verifyNoInteractions(profiles, storage);
	}

	@Test
	@DisplayName("wav도 m4a도 아니면 audio 칸 INVALID_REQUEST다")
	void wav_m4a가_아니면_INVALID_REQUEST() {
		// given
		given(publisher.enabled()).willReturn(true);

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.upload(USER_ID, "ID3-mp3-file-bytes".getBytes(), true));

		// then
		assertThat(e.getField()).isEqualTo("audio");
		verifyNoInteractions(recordings, profiles);
	}

	@Test
	@DisplayName("대화를 녹음하는 중이면 RECORDING_IN_PROGRESS이고 프로필을 잠그지 않는다")
	void 녹음_중이면_RECORDING_IN_PROGRESS() {
		// given
		given(publisher.enabled()).willReturn(true);
		given(recordings.existsUnfinishedSince(eq(USER_ID), any())).willReturn(true);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, wav(20), true));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECORDING_IN_PROGRESS);
		verifyNoInteractions(profiles, storage);
	}

	@Test
	@DisplayName("녹음 중인지는 3시간 전부터 본다")
	void 녹음_중인지는_3시간_전부터_본다() {
		// given
		given(recordings.existsUnfinishedSince(eq(USER_ID), any())).willReturn(false);

		// when
		boolean recording = service.recording(USER_ID);

		// then
		assertThat(recording).isFalse();
		ArgumentCaptor<OffsetDateTime> since = ArgumentCaptor.forClass(OffsetDateTime.class);
		verify(recordings).existsUnfinishedSince(eq(USER_ID), since.capture());
		assertThat(since.getValue()).isCloseTo(OffsetDateTime.now().minusHours(3), within(5, ChronoUnit.SECONDS));
	}

	@Test
	@DisplayName("쓸 수 있는 프로필 그림이 없으면 CHARACTER_NOT_FOUND다")
	void 그림이_없으면_CHARACTER_NOT_FOUND() {
		// given
		given(publisher.enabled()).willReturn(true);
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile(null, null, null, null)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, wav(20), true));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CHARACTER_NOT_FOUND);
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("내 목소리를 만드는 중이면 VOICE_CLONE_IN_PROGRESS다")
	void 만드는_중이면_VOICE_CLONE_IN_PROGRESS() {
		// given
		given(publisher.enabled()).willReturn(true);
		given(profiles.findByIdForUpdate(USER_ID))
			.willReturn(Optional.of(profile("k", UserProfile.CHARACTER_PROCESSING, "old.wav", null)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, wav(20), true));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOICE_CLONE_IN_PROGRESS);
		verifyNoInteractions(storage, deletions);
		verify(jobs, never()).save(any());
	}

	@Test
	@DisplayName("처음 올린 wav는 지우기를 빼고 올려 PROCESSING과 녹음 문장을 남기고 큐에 넣는다")
	void 처음_wav를_올린다() {
		// given
		given(publisher.enabled()).willReturn(true);
		UserProfile profile = profile("k", null, null, null);
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));
		givenJobSaved();
		ProfileCharacterService.ProfileCharacterResponse expected = new ProfileCharacterService.ProfileCharacterResponse(
				UserProfile.CHARACTER_READY, null, null, null, null);
		given(views.profileResponse(USER_ID)).willReturn(expected);
		byte[] audio = wav(20);
		String key = ImageStorage.voiceCloneSampleKey(USER_ID, "wav");

		// when
		ProfileCharacterService.ProfileCharacterResponse response = service.upload(USER_ID, audio, true);

		// then
		assertThat(response).isSameAs(expected);
		InOrder order = inOrder(deletions, storage);
		order.verify(deletions).cancel(key);
		order.verify(storage).put(key, audio, "audio/wav");
		assertThat(profile.getVoiceCloneStatus()).isEqualTo(UserProfile.CHARACTER_PROCESSING);
		assertThat(profile.getVoiceCloneKey()).isEqualTo(key);
		assertThat(profile.getVoiceCloneText()).isEqualTo(VoiceCloneService.RECORD_SENTENCE);
		assertThat(profile.getVoiceCloneConsentedAt()).isNotNull();
		ArgumentCaptor<Job> job = ArgumentCaptor.forClass(Job.class);
		verify(jobs).save(job.capture());
		assertThat(job.getValue().getJobType()).isEqualTo("VOICE_CLONE");
		verify(deletions, never()).schedule(anyString());
		verify(publisher).publishQuietly(JOB_ID);
	}

	@Test
	@DisplayName("확장자가 바뀐 다시 올리기는 예전 원본과 미리 듣기 지우기를 맡긴다")
	void 확장자가_바뀌면_예전_원본을_지운다() {
		// given
		given(publisher.enabled()).willReturn(true);
		String oldSample = ImageStorage.voiceCloneSampleKey(USER_ID, "wav");
		String oldPreview = ImageStorage.voiceClonePreviewKey(USER_ID);
		UserProfile profile = profile("k", UserProfile.CHARACTER_READY, oldSample, oldPreview);
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));
		givenJobSaved();
		byte[] audio = m4a(20);
		String key = ImageStorage.voiceCloneSampleKey(USER_ID, "m4a");

		// when
		service.upload(USER_ID, audio, true);

		// then
		verify(storage).put(key, audio, "audio/mp4");
		verify(deletions).schedule(oldSample);
		verify(deletions).schedule(oldPreview);
		assertThat(profile.getVoiceCloneKey()).isEqualTo(key);
		assertThat(profile.getVoiceClonePreviewKey()).isNull();
	}

	@Test
	@DisplayName("같은 확장자로 다시 올리면 새 원본은 지우지 않고 예전 미리 듣기만 지운다")
	void 같은_확장자면_원본은_지우지_않는다() {
		// given
		given(publisher.enabled()).willReturn(true);
		String sample = ImageStorage.voiceCloneSampleKey(USER_ID, "wav");
		String oldPreview = ImageStorage.voiceClonePreviewKey(USER_ID);
		given(profiles.findByIdForUpdate(USER_ID))
			.willReturn(Optional.of(profile("k", UserProfile.CHARACTER_FAILED, sample, oldPreview)));
		givenJobSaved();

		// when
		service.upload(USER_ID, wav(20), true);

		// then
		verify(deletions, never()).schedule(sample);
		verify(deletions).schedule(oldPreview);
	}

	// ---- delete ----

	@Test
	@DisplayName("내 목소리가 없으면 아무것도 하지 않는다")
	void 없으면_지우지_않는다() {
		// given
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile("k", null, null, null)));

		// when
		service.delete(USER_ID);

		// then
		verify(jobs, never()).failQueued(anyLong(), anyString(), anyString());
		verifyNoInteractions(deletions);
	}

	@Test
	@DisplayName("프로필이 없어도 오류 없이 끝난다")
	void 프로필이_없어도_끝난다() {
		// given
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.empty());

		// when
		service.delete(USER_ID);

		// then
		verifyNoInteractions(jobs, deletions);
	}

	@Test
	@DisplayName("지우면 상태와 self 선택을 비우고 작업을 실패로 두고 원본·미리 듣기 지우기를 맡긴다")
	void 지우면_모두_비운다() {
		// given
		UserProfile profile = profile("k", UserProfile.CHARACTER_READY, "sample.wav", "preview.wav");
		ReflectionTestUtils.setField(profile, "characterVoice", UserProfile.SELF_VOICE);
		ReflectionTestUtils.setField(profile, "voiceCloneShareFriends", true);
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		service.delete(USER_ID);

		// then
		assertThat(profile.getVoiceCloneStatus()).isNull();
		assertThat(profile.getCharacterVoice()).isNull();
		assertThat(profile.isVoiceCloneShareFriends()).isFalse();
		verify(jobs).failQueued(eq(USER_ID), eq("VOICE_CLONE"), anyString());
		verify(deletions).schedule("sample.wav");
		verify(deletions).schedule("preview.wav");
	}

	@Test
	@DisplayName("기본 화자 목소리를 골랐으면 지워도 그 선택은 남긴다")
	void 기본_화자_선택은_남긴다() {
		// given
		UserProfile profile = profile("k", UserProfile.CHARACTER_FAILED, null, null);
		ReflectionTestUtils.setField(profile, "characterVoice", "young-f-01");
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		service.delete(USER_ID);

		// then
		assertThat(profile.getCharacterVoice()).isEqualTo("young-f-01");
		verifyNoInteractions(deletions);
	}

	// ---- share ----

	@Test
	@DisplayName("동의 값이 null이면 shareWithFriends 칸 INVALID_REQUEST다")
	void 동의_값이_없으면_INVALID_REQUEST() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.share(USER_ID, null));

		// then
		assertThat(e.getField()).isEqualTo("shareWithFriends");
		verifyNoInteractions(profiles);
	}

	@Test
	@DisplayName("내 목소리를 만든 적이 없으면 VOICE_CLONE_NOT_FOUND다")
	void 만든_적이_없으면_VOICE_CLONE_NOT_FOUND() {
		// given
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile("k", null, null, null)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.share(USER_ID, true));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOICE_CLONE_NOT_FOUND);
	}

	@Test
	@DisplayName("친구 쇼츠 동의만 바꾼다")
	void 동의만_바꾼다() {
		// given
		UserProfile profile = profile("k", UserProfile.CHARACTER_READY, "sample.wav", null);
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		service.share(USER_ID, true);

		// then
		assertThat(profile.isVoiceCloneShareFriends()).isTrue();
		assertThat(profile.getVoiceCloneKey()).isEqualTo("sample.wav");
	}

	// ---- sample ----

	@Test
	@DisplayName("실패한 내 목소리의 원본은 주지 않고 VOICE_CLONE_NOT_FOUND다")
	void 실패한_목소리면_원본은_VOICE_CLONE_NOT_FOUND() {
		// given
		given(profiles.findById(USER_ID)).willReturn(Optional.of(profile("k", UserProfile.CHARACTER_FAILED, null, null)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sample(USER_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOICE_CLONE_NOT_FOUND);
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("READY인 내 목소리의 원본을 준다")
	void READY면_원본을_준다() {
		// given
		given(profiles.findById(USER_ID))
			.willReturn(Optional.of(profile("k", UserProfile.CHARACTER_READY, "sample.wav", null)));
		ImageStorage.StoredObject stored = new ImageStorage.StoredObject(new byte[] { 1 }, "audio/wav");
		given(storage.getObject("sample.wav")).willReturn(stored);

		// when
		ImageStorage.StoredObject result = service.sample(USER_ID);

		// then
		assertThat(result).isSameAs(stored);
	}

	@Test
	@DisplayName("저장소에 원본이 없으면 VOICE_CLONE_NOT_FOUND다")
	void 저장소에_원본이_없으면_VOICE_CLONE_NOT_FOUND() {
		// given
		given(profiles.findById(USER_ID))
			.willReturn(Optional.of(profile("k", UserProfile.CHARACTER_PROCESSING, "sample.wav", null)));
		given(storage.getObject("sample.wav")).willThrow(NoSuchKeyException.builder().build());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.sample(USER_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOICE_CLONE_NOT_FOUND);
	}

	// ---- preview ----

	@Test
	@DisplayName("wav가 아니거나 null이면 file 칸 INVALID_REQUEST다")
	void 미리_듣기가_wav가_아니면_INVALID_REQUEST() {
		// when
		ApiException notWav = catchThrowableOfType(ApiException.class, () -> service.preview(USER_ID, m4a(20)));
		ApiException nullWav = catchThrowableOfType(ApiException.class, () -> service.preview(USER_ID, null));

		// then
		assertThat(notWav.getField()).isEqualTo("file");
		assertThat(nullWav.getField()).isEqualTo("file");
		verifyNoInteractions(profiles, storage);
	}

	@Test
	@DisplayName("4MB를 넘는 미리 듣기는 PAYLOAD_TOO_LARGE다")
	void 큰_미리_듣기는_PAYLOAD_TOO_LARGE() {
		// given
		byte[] wav = wav((int) VoiceCloneService.MAX_AUDIO_BYTES + 1);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.preview(USER_ID, wav));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
	}

	@Test
	@DisplayName("만드는 중이 아니면 미리 듣기를 받지 않고 VOICE_CLONE_NOT_FOUND다")
	void 만드는_중이_아니면_미리_듣기는_VOICE_CLONE_NOT_FOUND() {
		// given
		given(profiles.findByIdForUpdate(USER_ID))
			.willReturn(Optional.of(profile("k", UserProfile.CHARACTER_READY, "sample.wav", "p.wav")));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.preview(USER_ID, wav(20)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VOICE_CLONE_NOT_FOUND);
		verifyNoInteractions(storage, push);
	}

	@Test
	@DisplayName("미리 듣기를 받으면 올리고 READY로 바꾸고 작업을 끝내고 알린다")
	void 미리_듣기를_받으면_READY() {
		// given
		UserProfile profile = profile("k", UserProfile.CHARACTER_PROCESSING, "sample.wav", null);
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));
		byte[] wav = wav(20);
		String key = ImageStorage.voiceClonePreviewKey(USER_ID);

		// when
		service.preview(USER_ID, wav);

		// then
		InOrder order = inOrder(deletions, storage);
		order.verify(deletions).cancel(key);
		order.verify(storage).put(key, wav, "audio/wav");
		assertThat(profile.getVoiceCloneStatus()).isEqualTo(UserProfile.CHARACTER_READY);
		assertThat(profile.getVoiceClonePreviewKey()).isEqualTo(key);
		verify(jobs).completeQueued(USER_ID, "VOICE_CLONE");
		verify(push).notify(USER_ID, PushService.voiceCloneReady());
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
		Job job = Job.voiceClone(USER_ID);
		job.fail("먼저 온 실패");
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));

		// when
		service.fail(JOB_ID, "두 번째 실패");

		// then
		assertThat(job.getFailReason()).isEqualTo("먼저 온 실패");
		verifyNoInteractions(profiles, deletions, push);
	}

	@Test
	@DisplayName("만드는 중이 아니면 작업만 실패로 두고 알리지 않는다")
	void 만드는_중이_아니면_작업만_실패() {
		// given
		Job job = Job.voiceClone(USER_ID);
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));
		UserProfile profile = profile("k", UserProfile.CHARACTER_READY, "sample.wav", "p.wav");
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		service.fail(JOB_ID, "오류");

		// then
		assertThat(job.getStatus()).isEqualTo("FAILED");
		assertThat(profile.getVoiceCloneStatus()).isEqualTo(UserProfile.CHARACTER_READY);
		verifyNoInteractions(deletions, push);
	}

	@Test
	@DisplayName("만드는 중이면 FAILED와 다듬은 이유를 남기고 self 선택을 비우고 원본 지우기를 맡기고 알린다")
	void 만드는_중이면_FAILED() {
		// given
		Job job = Job.voiceClone(USER_ID);
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));
		UserProfile profile = profile("k", UserProfile.CHARACTER_PROCESSING, "sample.wav", null);
		ReflectionTestUtils.setField(profile, "characterVoice", UserProfile.SELF_VOICE);
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		service.fail(JOB_ID, "  잡음이 많음  ");

		// then
		assertThat(profile.getVoiceCloneStatus()).isEqualTo(UserProfile.CHARACTER_FAILED);
		assertThat(profile.getVoiceCloneFailReason()).isEqualTo("잡음이 많음");
		assertThat(profile.getVoiceCloneKey()).isNull();
		assertThat(profile.getCharacterVoice()).isNull();
		verify(deletions).schedule("sample.wav");
		verify(push).notify(USER_ID, PushService.voiceCloneFailed());
	}

	@Test
	@DisplayName("이유가 공백뿐이면 이유 없이 남긴다")
	void 이유가_비면_null() {
		// given
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(Job.voiceClone(USER_ID)));
		UserProfile profile = profile("k", UserProfile.CHARACTER_PROCESSING, null, null);
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		service.fail(JOB_ID, "   ");

		// then
		assertThat(profile.getVoiceCloneFailReason()).isNull();
		verifyNoInteractions(deletions);
	}

	@Test
	@DisplayName("이유가 200자를 넘으면 200자까지 남긴다")
	void 긴_이유는_200자로_자른다() {
		// given
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(Job.voiceClone(USER_ID)));
		UserProfile profile = profile("k", UserProfile.CHARACTER_PROCESSING, null, null);
		given(profiles.findByIdForUpdate(USER_ID)).willReturn(Optional.of(profile));

		// when
		service.fail(JOB_ID, "a".repeat(VoiceCloneService.MAX_FAIL_REASON + 50));

		// then
		assertThat(profile.getVoiceCloneFailReason()).hasSize(VoiceCloneService.MAX_FAIL_REASON);
	}

	// ---- audioType ----

	@Test
	@DisplayName("머리말로 wav·m4a를 가리고 짧거나 모르는 것은 null이다")
	void 머리말로_종류를_가린다() {

		// when
		Object wavType = VoiceCloneService.audioType(wav(12));
		Object m4aType = VoiceCloneService.audioType(m4a(12));
		Object shortType = VoiceCloneService.audioType(new byte[11]);

		// then
		assertThat(wavType).hasToString("Audio[ext=wav, contentType=audio/wav]");
		assertThat(m4aType).hasToString("Audio[ext=m4a, contentType=audio/mp4]");
		assertThat(shortType).isNull();
		assertThat(VoiceCloneService.isWav("RIFF....AVI ".getBytes())).isFalse();
	}

	// ---- 도움 ----

	// 저장할 때 id를 붙여 돌려준다.
	private void givenJobSaved() {
		given(jobs.save(any(Job.class))).willAnswer(inv -> {
			Job job = inv.getArgument(0);
			ReflectionTestUtils.setField(job, "id", JOB_ID);
			return job;
		});
	}

	// 프로필 그림 키와 내 목소리 상태·키를 정한 프로필
	private static UserProfile profile(String characterKey, String cloneStatus, String sampleKey, String previewKey) {
		UserProfile profile = new UserProfile();
		ReflectionTestUtils.setField(profile, "userId", USER_ID);
		ReflectionTestUtils.setField(profile, "characterKey", characterKey);
		ReflectionTestUtils.setField(profile, "voiceCloneStatus", cloneStatus);
		ReflectionTestUtils.setField(profile, "voiceCloneKey", sampleKey);
		ReflectionTestUtils.setField(profile, "voiceClonePreviewKey", previewKey);
		return profile;
	}

	// RIFF....WAVE 머리말로 시작하는 length 바이트
	private static byte[] wav(int length) {
		byte[] bytes = new byte[length];
		System.arraycopy("RIFF".getBytes(), 0, bytes, 0, 4);
		System.arraycopy("WAVE".getBytes(), 0, bytes, 8, 4);
		return bytes;
	}

	// ....ftyp 머리말로 시작하는 length 바이트
	private static byte[] m4a(int length) {
		byte[] bytes = new byte[length];
		System.arraycopy("ftypM4A ".getBytes(), 0, bytes, 4, 8);
		return bytes;
	}

}
