package com.lolog.api.domain.audio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.audio.repository.RecordingAudioFileRepository;
import com.lolog.api.domain.recording.entity.Recording;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("AudioUploadService")
class AudioUploadServiceTest {

	private static final long USER_ID = 1L;
	private static final long RECORDING_ID = 3L;

	@Mock
	private RecordingRepository recordings;

	@Mock
	private ImageStorage storage;

	@Mock
	private CardAudioClipper clipper;

	@Mock
	private RecordingAudioFileRepository files;

	private AudioUploadService service;

	@BeforeEach
	void setUp() {
		service = new AudioUploadService(recordings, storage, clipper, files);
	}

	@Test
	@DisplayName("번호가 0이면 field가 seq인 INVALID_REQUEST이고 녹음을 찾지 않는다")
	void 번호가_0이면_400() {
		// when & then
		assertInvalid(0, adts(), 0L, 1000L, "seq");
	}

	@Test
	@DisplayName("시작 시각이 음수면 field가 startMs인 INVALID_REQUEST다")
	void 시작이_음수면_400() {
		// when & then
		assertInvalid(1, adts(), -1L, 1000L, "startMs");
	}

	@Test
	@DisplayName("끝이 시작과 같으면 field가 endMs인 INVALID_REQUEST다")
	void 끝이_시작과_같으면_400() {
		// when & then
		assertInvalid(1, adts(), 1000L, 1000L, "endMs");
	}

	@Test
	@DisplayName("파일이 없으면 field가 file인 INVALID_REQUEST다")
	void 파일이_없으면_400() {
		// when & then
		assertInvalid(1, null, 0L, 1000L, "file");
	}

	@Test
	@DisplayName("ADTS가 아니면 field가 file인 INVALID_REQUEST다")
	void ADTS가_아니면_400() {
		// when & then
		assertInvalid(1, new byte[] { 1, 2, 3 }, 0L, 1000L, "file");
	}

	@Test
	@DisplayName("5MB를 1바이트 넘으면 PAYLOAD_TOO_LARGE다")
	void 크기를_넘으면_413() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.upload(USER_ID, RECORDING_ID, 1,
				new byte[(int) AudioUploadService.MAX_BYTES + 1], 0L, 1000L));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
		verifyNoInteractions(recordings, storage);
	}

	@Test
	@DisplayName("내 녹음이 아니면 RECORDING_NOT_FOUND이고 올리지 않는다")
	void 내_녹음이_아니면_404() {
		// given
		given(recordings.findByIdAndUserIdForUpdate(RECORDING_ID, USER_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.upload(USER_ID, RECORDING_ID, 1, adts(), 0L, 1000L));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.RECORDING_NOT_FOUND);
		verifyNoInteractions(storage, files, clipper);
	}

	@Test
	@DisplayName("음성 저장을 끈 녹음이면 INVALID_REQUEST이고 올리지 않는다")
	void 음성_저장을_끈_녹음이면_400() {
		// given
		given(recordings.findByIdAndUserIdForUpdate(RECORDING_ID, USER_ID)).willReturn(Optional.of(recording(false)));

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.upload(USER_ID, RECORDING_ID, 1, adts(), 0L, 1000L));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		verifyNoInteractions(storage, files, clipper);
	}

	@Test
	@DisplayName("맞는 파일이면 번호별 키로 올리고 줄을 고쳐 쓴 뒤 자르기를 맡긴다")
	void 올리고_줄을_쓰고_자르기를_맡긴다() {
		// given
		byte[] file = adts();
		given(recordings.findByIdAndUserIdForUpdate(RECORDING_ID, USER_ID)).willReturn(Optional.of(recording(true)));
		String key = ImageStorage.recordingAudioKey(USER_ID, RECORDING_ID, 2);

		// when
		service.upload(USER_ID, RECORDING_ID, 2, file, 600_000L, 1_200_000L);

		// then
		InOrder order = inOrder(storage, files, clipper);
		order.verify(storage).putAac(key, file);
		order.verify(files).upsert(RECORDING_ID, 2, key, 600_000L, 1_200_000L);
		order.verify(clipper).clipRecordingLater(RECORDING_ID);
	}

	private void assertInvalid(int seq, byte[] file, Long startMs, Long endMs, String field) {
		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.upload(USER_ID, RECORDING_ID, seq, file, startMs, endMs));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo(field);
		verifyNoInteractions(recordings, storage, files, clipper);
	}

	private static Recording recording(boolean audioSaved) {
		return new Recording(USER_ID, "c", OffsetDateTime.now(), audioSaved);
	}

	// 16kHz ADTS 조각 하나(길이 10바이트)
	private static byte[] adts() {
		int length = 10;
		byte[] data = new byte[length];
		data[0] = (byte) 0xFF;
		data[1] = (byte) 0xF1;
		data[2] = (byte) (0x40 | (8 << 2));
		data[3] = (byte) (0x40 | ((length >> 11) & 0x03));
		data[4] = (byte) ((length >> 3) & 0xFF);
		data[5] = (byte) (((length & 0x07) << 5) | 0x1F);
		data[6] = (byte) 0xFC;
		return data;
	}

}
