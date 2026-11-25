package com.lolog.api.domain.audio.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.audio.service.CardAudioClipper.AudioFile;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("CardAudioClipper")
class CardAudioClipperTest {

	private static AudioFile file(int seq, long startMs, long endMs) {
		return new AudioFile(seq, "audio/" + seq, startMs, endMs);
	}

	@Test
	@DisplayName("다음 파일이 64ms 뒤에 시작하면 이어진 것으로 본다")
	void 다음_파일이_64ms_뒤에_시작하면_이어진_것으로_본다() {
		// given
		AudioFile first = file(1, 0, 1000);
		AudioFile second = file(2, 1064, 2000);

		// when & then
		assertThat(CardAudioClipper.covering(List.of(first, second), 500, 1500)).containsExactly(first, second);
	}

	@Test
	@DisplayName("다음 파일이 65ms 뒤에 시작하면 빈틈으로 본다")
	void 다음_파일이_65ms_뒤에_시작하면_빈틈으로_본다() {
		// given
		List<AudioFile> files = List.of(file(1, 0, 1000), file(2, 1065, 2000));

		// when & then
		assertThat(CardAudioClipper.covering(files, 500, 1500)).isNull();
	}

	@Test
	@DisplayName("첫 파일이 from보다 65ms 늦게 시작하면 빈틈이다")
	void 첫_파일이_from보다_65ms_늦게_시작하면_빈틈이다() {
		// when & then
		assertThat(CardAudioClipper.covering(List.of(file(1, 565, 2000)), 500, 1500)).isNull();
	}

	@Test
	@DisplayName("파일 경계가 정확히 맞물리면 이어진다")
	void 파일_경계가_정확히_맞물리면_이어진다() {
		// given
		AudioFile first = file(1, 0, 1000);
		AudioFile second = file(2, 1000, 2000);

		// when & then
		assertThat(CardAudioClipper.covering(List.of(first, second), 0, 2000)).containsExactly(first, second);
	}

	@Test
	@DisplayName("겹치는 파일은 둘 다 쓴다")
	void 겹치는_파일은_둘_다_쓴다() {
		// given
		AudioFile first = file(1, 0, 1000);
		AudioFile second = file(2, 800, 2000);

		// when & then
		assertThat(CardAudioClipper.covering(List.of(first, second), 500, 1500)).containsExactly(first, second);
	}

	@Test
	@DisplayName("앞 파일에 다 들어 있는 파일은 건너뛴다")
	void 앞_파일에_다_들어_있는_파일은_건너뛴다() {
		// given
		AudioFile first = file(1, 0, 2000);
		AudioFile inside = file(2, 500, 1500);
		AudioFile third = file(3, 2000, 3000);

		// when & then
		assertThat(CardAudioClipper.covering(List.of(first, inside, third), 100, 2500)).containsExactly(first,
				third);
	}

	@Test
	@DisplayName("구간 앞에서 끝나는 파일은 건너뛴다")
	void 구간_앞에서_끝나는_파일은_건너뛴다() {
		// given
		AudioFile before = file(1, 0, 500);
		AudioFile second = file(2, 500, 1500);

		// when & then
		assertThat(CardAudioClipper.covering(List.of(before, second), 500, 1000)).containsExactly(second);
	}

	@Test
	@DisplayName("구간을 다 덮으면 뒤 파일은 넣지 않는다")
	void 구간을_다_덮으면_뒤_파일은_넣지_않는다() {
		// given
		AudioFile first = file(1, 0, 1000);
		AudioFile after = file(2, 1000, 2000);

		// when & then
		assertThat(CardAudioClipper.covering(List.of(first, after), 0, 1000)).containsExactly(first);
	}

	@Test
	@DisplayName("to를 다 덮지 못하면 null이다")
	void to를_다_덮지_못하면_null이다() {
		// given
		List<AudioFile> files = List.of(file(1, 0, 1000), file(2, 1000, 1999));

		// when & then
		assertThat(CardAudioClipper.covering(files, 0, 2000)).isNull();
	}

	@Test
	@DisplayName("파일이 없으면 null이다")
	void 파일이_없으면_null이다() {
		// when & then
		assertThat(CardAudioClipper.covering(List.of(), 0, 1000)).isNull();
	}

}
