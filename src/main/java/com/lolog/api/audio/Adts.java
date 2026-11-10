package com.lolog.api.audio;

import java.util.ArrayList;
import java.util.List;

// AAC ADTS 파일을 조각(프레임)으로 나눈다(audio.md 2장 "형식"). 소리를 풀지 않고 머리말만 읽는다.
// 조각마다 7바이트(CRC가 있으면 9바이트) 머리말이 있고, 그 안에 조각 길이와 샘플레이트가 있다.
// 앱은 16kHz·조각마다 1024샘플이라 조각 하나가 64ms다. 서버는 머리말 값으로 길이를 계산해 다른 값이 와도 시간이 맞게 한다.
public final class Adts {

	// 샘플레이트 번호(머리말의 4비트) → Hz. 13~15는 쓰지 않는 값이다.
	private static final int[] SAMPLE_RATES = { 96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000,
			11025, 8000, 7350 };

	private Adts() {
	}

	// offset·length는 파일 안 바이트 위치, durationUs는 이 조각의 소리 길이(마이크로초).
	public record Frame(int offset, int length, long durationUs) {
	}

	// 파일 처음부터 끝까지 조각으로 나눈다. 비었거나, 머리말이 아니거나, 끝이 조각 중간에서 끊기면 예외다.
	public static List<Frame> frames(byte[] data) {
		if (data.length == 0) {
			throw new IllegalArgumentException("빈 파일입니다.");
		}
		List<Frame> frames = new ArrayList<>();
		int pos = 0;
		while (pos < data.length) {
			if (data.length - pos < 7) {
				throw new IllegalArgumentException("조각 머리말이 잘렸습니다.");
			}
			// 동기 표시 12비트(0xFFF), layer 2비트는 00
			if ((data[pos] & 0xFF) != 0xFF || (data[pos + 1] & 0xF6) != 0xF0) {
				throw new IllegalArgumentException("ADTS 조각이 아닙니다.");
			}
			boolean crc = (data[pos + 1] & 0x01) == 0;
			int rateIndex = (data[pos + 2] & 0x3C) >> 2;
			if (rateIndex >= SAMPLE_RATES.length) {
				throw new IllegalArgumentException("샘플레이트를 알 수 없습니다.");
			}
			int length = ((data[pos + 3] & 0x03) << 11) | ((data[pos + 4] & 0xFF) << 3) | ((data[pos + 5] & 0xE0) >> 5);
			int blocks = (data[pos + 6] & 0x03) + 1;
			if (length < (crc ? 9 : 7) || pos + length > data.length) {
				throw new IllegalArgumentException("조각 길이가 맞지 않습니다.");
			}
			long durationUs = blocks * 1024L * 1_000_000L / SAMPLE_RATES[rateIndex];
			frames.add(new Frame(pos, length, durationUs));
			pos += length;
		}
		return frames;
	}

}
