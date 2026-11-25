package com.lolog.api.internal.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.character.service.CharacterViews;
import com.lolog.api.domain.character.service.DefaultCharacterService;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.user.repository.UserProfileRepository;
import com.lolog.api.domain.video.entity.Video;
import com.lolog.api.domain.video.entity.VideoStatus;
import com.lolog.api.domain.video.repository.VideoRepository;
import com.lolog.api.domain.video.service.VideoPublisher;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.storage.StorageDeletions;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoWorkerService")
class VideoWorkerServiceTest {

	private static final long USER_ID = 1L;
	private static final long CARD_ID = 10L;
	private static final long VIDEO_ID = 100L;
	private static final String AUDIO_KEY = ImageStorage.videoAudioKey(USER_ID, VIDEO_ID);

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Mock
	private UserProfileRepository profiles;

	@Mock
	private VideoRepository videos;

	@Mock
	private CardRepository cards;

	@Mock
	private JobRepository jobs;

	@Mock
	private CharacterViews characters;

	@Mock
	private ImageStorage storage;

	@Mock
	private StorageDeletions deletions;

	@Mock
	private TransactionTemplate tx;

	@Mock
	private VideoPublisher publisher;

	@Mock
	private PushService push;

	@Mock
	private DefaultCharacterService defaultCharacters;

	private VideoWorkerService service;

	@BeforeEach
	void setUp() {
		// 트랜잭션 템플릿은 받은 콜백을 바로 실행한다.
		lenient().when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
		lenient().doAnswer(inv -> {
			inv.<Consumer<TransactionStatus>>getArgument(0).accept(null);
			return null;
		}).when(tx).executeWithoutResult(any());
		service = new VideoWorkerService(profiles, videos, cards, jobs, characters, storage, deletions, objectMapper, tx,
				publisher, push, defaultCharacters);
	}

	// ---- saveAudio ----

	@Test
	@DisplayName("WAV가 아니면 field가 file인 INVALID_REQUEST이고 올리지 않는다")
	void WAV가_아니면_올리지_않는다() {
		// given
		given(videos.findById(VIDEO_ID)).willReturn(Optional.of(video()));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.saveAudio(VIDEO_ID, new byte[12]));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("file");
		verifyNoInteractions(storage, deletions);
	}

	@Test
	@DisplayName("콘티가 아직 없으면 올린 음성을 지울 줄에 넣고 INVALID_REQUEST다")
	void 콘티가_없으면_올린_음성을_지운다() {
		// given
		Video video = video();
		given(videos.findById(VIDEO_ID)).willReturn(Optional.of(video));
		given(videos.findByIdForUpdate(VIDEO_ID)).willReturn(Optional.of(video));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.saveAudio(VIDEO_ID, wav()));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		verify(storage).put(AUDIO_KEY, wav(), "audio/wav");
		verify(deletions).schedule(AUDIO_KEY);
		verifyNoInteractions(publisher);
	}

	@Test
	@DisplayName("올리는 사이 영상이 지워졌으면 올린 음성을 지울 줄에 넣고 VIDEO_NOT_FOUND다")
	void 올리는_사이_지워지면_올린_음성을_지운다() {
		// given
		given(videos.findById(VIDEO_ID)).willReturn(Optional.of(video()));
		given(videos.findByIdForUpdate(VIDEO_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.saveAudio(VIDEO_ID, wav()));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VIDEO_NOT_FOUND);
		verify(deletions).schedule(AUDIO_KEY);
		verifyNoInteractions(publisher);
	}

	@Test
	@DisplayName("올리는 사이 영상이 실패했으면 오류 없이 올린 음성만 지울 줄에 넣는다")
	void 올리는_사이_실패하면_올린_음성만_지운다() {
		// given
		Video failed = video();
		failed.fail("실패");
		given(videos.findById(VIDEO_ID)).willReturn(Optional.of(video()));
		given(videos.findByIdForUpdate(VIDEO_ID)).willReturn(Optional.of(failed));

		// when
		service.saveAudio(VIDEO_ID, wav());

		// then
		verify(deletions).schedule(AUDIO_KEY);
		verify(jobs, never()).save(any());
		verifyNoInteractions(publisher);
	}

	@Test
	@DisplayName("이미 끝난 영상이면 받기만 하고 올리지 않는다")
	void 끝난_영상이면_무시한다() {
		// given
		Video done = video();
		done.finish("v", "t", 1000);
		given(videos.findById(VIDEO_ID)).willReturn(Optional.of(done));

		// when
		service.saveAudio(VIDEO_ID, wav());

		// then
		verifyNoInteractions(storage, deletions, publisher);
	}

	@Test
	@DisplayName("콘티 뒤에 처음 받은 음성이면 DRAWING이 되고 목소리 작업을 끝낸 뒤 그림 작업을 큐에 넣는다")
	void 처음_받으면_그림_작업을_넣는다() {
		// given
		Video video = video();
		video.saveStoryboard("{}");
		given(videos.findById(VIDEO_ID)).willReturn(Optional.of(video));
		given(videos.findByIdForUpdate(VIDEO_ID)).willReturn(Optional.of(video));
		given(jobs.save(any())).willAnswer(inv -> {
			ReflectionTestUtils.setField((Object) inv.getArgument(0), "id", 77L);
			return inv.getArgument(0);
		});

		// when
		service.saveAudio(VIDEO_ID, wav());

		// then
		assertThat(video.getStatus()).isEqualTo(VideoStatus.DRAWING);
		assertThat(video.getAudioKey()).isEqualTo(AUDIO_KEY);
		verify(jobs).completeVideoStep(VIDEO_ID, "VIDEO_VOICE");
		verify(publisher).publishQuietly(77L);
		verifyNoInteractions(deletions);
	}

	// ---- saveStoryboard ----

	@Test
	@DisplayName("version이 1이 아니면 field가 version인 INVALID_REQUEST다")
	void version이_1이_아니면_400() {
		// when & then
		assertStoryboardInvalid("{\"version\":2,\"videoId\":100,\"cardId\":10}", "version");
	}

	@Test
	@DisplayName("videoId가 이 영상과 다르면 field가 videoId인 INVALID_REQUEST다")
	void videoId가_다르면_400() {
		// when & then
		assertStoryboardInvalid("{\"version\":1,\"videoId\":101,\"cardId\":10}", "videoId");
	}

	@Test
	@DisplayName("cardId가 문자열이면 field가 cardId인 INVALID_REQUEST다")
	void cardId가_숫자가_아니면_400() {
		// when & then
		assertStoryboardInvalid("{\"version\":1,\"videoId\":100,\"cardId\":\"10\"}", "cardId");
	}

	@Test
	@DisplayName("JSON 객체가 아니면 field 없는 INVALID_REQUEST다")
	void 객체가_아니면_400() {
		// when & then
		assertStoryboardInvalid("[1]", null);
	}

	@Test
	@DisplayName("맞는 콘티는 그대로 저장한다")
	void 맞는_콘티는_저장한다() {
		// given
		Video video = video();
		given(videos.findByIdForUpdate(VIDEO_ID)).willReturn(Optional.of(video));

		// when
		service.saveStoryboard(VIDEO_ID, objectMapper.readTree("{\"version\":1,\"videoId\":100,\"cardId\":10,\"x\":\"y\"}"));

		// then
		assertThat(objectMapper.readTree(video.getStoryboard()).path("x").asString()).isEqualTo("y");
	}

	@Test
	@DisplayName("끝난 영상의 콘티는 검사하지 않고 무시한다")
	void 끝난_영상의_콘티는_무시한다() {
		// given
		Video video = video();
		video.fail("실패");
		given(videos.findByIdForUpdate(VIDEO_ID)).willReturn(Optional.of(video));

		// when
		service.saveStoryboard(VIDEO_ID, objectMapper.readTree("[1]"));

		// then
		assertThat(video.hasStoryboard()).isFalse();
	}

	private void assertStoryboardInvalid(String json, String field) {
		// given
		Video video = video();
		given(videos.findByIdForUpdate(VIDEO_ID)).willReturn(Optional.of(video));

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.saveStoryboard(VIDEO_ID, objectMapper.readTree(json)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo(field);
		assertThat(video.hasStoryboard()).isFalse();
	}

	private static Video video() {
		Video video = new Video(CARD_ID, USER_ID, "요약");
		ReflectionTestUtils.setField(video, "id", VIDEO_ID);
		return video;
	}

	private static byte[] wav() {
		byte[] wav = new byte[16];
		System.arraycopy("RIFF".getBytes(), 0, wav, 0, 4);
		System.arraycopy("WAVE".getBytes(), 0, wav, 8, 4);
		return wav;
	}

}
