package com.lolog.api.domain.audio.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.audio.repository.RecordingAudioFileRepository;
import com.lolog.api.domain.audio.repository.RecordingAudioFileRepository.ExpiredRecording;
import com.lolog.api.global.storage.ImageStorage;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AudioRetentionJob")
class AudioRetentionJobTest {

	@Mock
	private RecordingAudioFileRepository files;

	@Mock
	private ImageStorage storage;

	@Mock
	private CardAudioClipper clipper;

	private AudioRetentionJob job;

	@BeforeEach
	void setUp() {
		job = new AudioRetentionJob(files, storage, clipper);
	}

	@Test
	@DisplayName("지우기 전에 구간 음성이 없는 카드를 500개까지 먼저 다시 자른다")
	void 지우기_전에_먼저_자른다() {
		// given
		given(files.findCardIdsToClip(500)).willReturn(List.of(1L, 2L));
		given(files.findExpiredRecordings(AudioRetentionJob.KEEP.toSeconds())).willReturn(List.of());

		// when
		job.run();

		// then
		InOrder order = inOrder(clipper, files);
		order.verify(clipper).clipQuietly(1L);
		order.verify(clipper).clipQuietly(2L);
		order.verify(files).findExpiredRecordings(AudioRetentionJob.KEEP.toSeconds());
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("7일 지난 녹음은 저장소를 먼저 지운 뒤 음성 파일 줄을 지운다")
	void 저장소를_먼저_지우고_줄을_지운다() {
		// given
		given(files.findExpiredRecordings(AudioRetentionJob.KEEP.toSeconds())).willReturn(List.of(expired(3L, 1L)));

		// when
		job.deleteExpired();

		// then
		InOrder order = inOrder(storage, files);
		order.verify(storage).deletePrefix(ImageStorage.recordingAudioPrefix(1L, 3L));
		order.verify(files).deleteAllByRecordingId(3L);
	}

	@Test
	@DisplayName("한 녹음의 저장소 지우기가 실패하면 그 줄은 남기고 다음 녹음은 지운다")
	void 한_녹음이_실패해도_다음_녹음은_지운다() {
		// given
		given(files.findExpiredRecordings(AudioRetentionJob.KEEP.toSeconds()))
			.willReturn(List.of(expired(3L, 1L), expired(4L, 2L)));
		willThrow(new IllegalStateException("저장소 오류")).given(storage)
			.deletePrefix(ImageStorage.recordingAudioPrefix(1L, 3L));

		// when
		job.deleteExpired();

		// then
		verify(files, never()).deleteAllByRecordingId(3L);
		verify(storage).deletePrefix(ImageStorage.recordingAudioPrefix(2L, 4L));
		verify(files).deleteAllByRecordingId(4L);
	}

	@Test
	@DisplayName("다시 자르기가 실패해도 예외를 넘기지 않는다")
	void 자르기_조회가_실패해도_예외를_넘기지_않는다() {
		// given
		given(files.findCardIdsToClip(500)).willThrow(new IllegalStateException("DB 오류"));

		// when
		job.run();

		// then
		verify(files, never()).deleteAllByRecordingId(anyLong());
		verifyNoInteractions(clipper, storage);
	}

	private static ExpiredRecording expired(long id, long userId) {
		return new ExpiredRecording() {

			@Override
			public Long getId() {
				return id;
			}

			@Override
			public Long getUserId() {
				return userId;
			}

		};
	}

}
