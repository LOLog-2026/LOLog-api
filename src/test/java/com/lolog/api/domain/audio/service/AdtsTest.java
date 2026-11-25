package com.lolog.api.domain.audio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("Adts")
class AdtsTest {

	// 16kHz 샘플레이트 번호
	private static final int RATE_16K = 8;

	// ADTS 조각 하나. 머리말의 조각 길이는 length, 실제 바이트 수는 size(나머지는 0으로 채움).
	private static byte[] frame(int length, int size, boolean crc, int rateIndex, int extraBlocks) {
		byte[] data = new byte[size];
		data[0] = (byte) 0xFF;
		data[1] = (byte) (crc ? 0xF0 : 0xF1);
		data[2] = (byte) (0x40 | (rateIndex << 2));
		data[3] = (byte) (0x40 | ((length >> 11) & 0x03));
		data[4] = (byte) ((length >> 3) & 0xFF);
		data[5] = (byte) (((length & 0x07) << 5) | 0x1F);
		data[6] = (byte) (0xFC | (extraBlocks & 0x03));
		return data;
	}

	private static byte[] frame(int length) {
		return frame(length, length, false, RATE_16K, 0);
	}

	private static byte[] concat(byte[]... parts) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		for (byte[] part : parts) {
			out.writeBytes(part);
		}
		return out.toByteArray();
	}

	@Test
	@DisplayName("빈 파일은 예외다")
	void 빈_파일은_예외다() {
		// when & then
		assertThatThrownBy(() -> Adts.frames(new byte[0])).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	@DisplayName("CRC가 없는 머리말은 7바이트 조각을 받는다")
	void CRC가_없는_머리말은_7바이트_조각을_받는다() {
		// when
		List<Adts.Frame> frames = Adts.frames(frame(7));

		// then
		assertThat(frames).containsExactly(new Adts.Frame(0, 7, 64_000));
	}

	@Test
	@DisplayName("CRC가 있는 머리말은 9바이트 조각을 받는다")
	void CRC가_있는_머리말은_9바이트_조각을_받는다() {
		// when
		List<Adts.Frame> frames = Adts.frames(frame(9, 9, true, RATE_16K, 0));

		// then
		assertThat(frames).containsExactly(new Adts.Frame(0, 9, 64_000));
	}

	@Test
	@DisplayName("CRC가 있는 머리말에서 조각 길이가 8이면 예외다")
	void CRC가_있는_머리말에서_조각_길이가_8이면_예외다() {
		// when & then
		assertThatThrownBy(() -> Adts.frames(frame(8, 8, true, RATE_16K, 0)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("조각 길이가 맞지 않습니다.");
	}

	@ParameterizedTest
	@DisplayName("쓰지 않는 샘플레이트 번호는 예외다")
	@ValueSource(ints = { 13, 14, 15 })
	void 쓰지_않는_샘플레이트_번호는_예외다(int rateIndex) {
		// when & then
		assertThatThrownBy(() -> Adts.frames(frame(10, 10, false, rateIndex, 0)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("샘플레이트를 알 수 없습니다.");
	}

	@Test
	@DisplayName("마지막 샘플레이트 번호 12는 7350Hz로 계산한다")
	void 마지막_샘플레이트_번호_12는_7350Hz로_계산한다() {
		// when
		List<Adts.Frame> frames = Adts.frames(frame(10, 10, false, 12, 0));

		// then
		assertThat(frames.getFirst().durationUs()).isEqualTo(1024L * 1_000_000L / 7350);
	}

	@Test
	@DisplayName("조각에 블록이 여러 개면 블록 수만큼 길다")
	void 조각에_블록이_여러_개면_블록_수만큼_길다() {
		// when
		// data[6]&3 = 3이면 블록 4개
		List<Adts.Frame> frames = Adts.frames(frame(10, 10, false, RATE_16K, 3));

		// then
		assertThat(frames.getFirst().durationUs()).isEqualTo(256_000);
	}

	@Test
	@DisplayName("남은 바이트가 7보다 적으면 머리말이 잘린 것이다")
	void 남은_바이트가_7보다_적으면_머리말이_잘린_것이다() {
		// given
		byte[] data = concat(frame(10), new byte[] { (byte) 0xFF, (byte) 0xF1, 0, 0, 0, 0 });

		// when & then
		assertThatThrownBy(() -> Adts.frames(data)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("조각 머리말이 잘렸습니다.");
	}

	@Test
	@DisplayName("조각 길이가 남은 바이트보다 길면 예외다")
	void 조각_길이가_남은_바이트보다_길면_예외다() {
		// given
		byte[] data = concat(frame(10), frame(11, 10, false, RATE_16K, 0));

		// when & then
		assertThatThrownBy(() -> Adts.frames(data)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("조각 길이가 맞지 않습니다.");
	}

	@Test
	@DisplayName("조각 길이가 남은 바이트와 같으면 받는다")
	void 조각_길이가_남은_바이트와_같으면_받는다() {
		// when & then
		assertThat(Adts.frames(concat(frame(10), frame(12)))).hasSize(2);
	}

	@Test
	@DisplayName("여러 조각의 위치와 길이를 차례로 돌려준다")
	void 여러_조각의_위치와_길이를_차례로_돌려준다() {
		// given
		byte[] data = concat(frame(10), frame(7), frame(9, 9, true, RATE_16K, 1), frame(20));

		// when
		List<Adts.Frame> frames = Adts.frames(data);

		// then
		assertThat(frames).containsExactly(new Adts.Frame(0, 10, 64_000), new Adts.Frame(10, 7, 64_000),
				new Adts.Frame(17, 9, 128_000), new Adts.Frame(26, 20, 64_000));
	}

	@Test
	@DisplayName("동기 표시가 아니면 예외다")
	void 동기_표시가_아니면_예외다() {
		// given
		byte[] data = frame(10);
		data[1] = (byte) 0xF7;

		// when & then
		assertThatThrownBy(() -> Adts.frames(data)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("ADTS 조각이 아닙니다.");
	}

}
