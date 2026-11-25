package com.lolog.api.global.storage;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

// 저장소 키 모양. 회원을 지울 때 users/{id}/ 아래를 통째로 지우므로 회원 키는 모두 그 아래여야 한다.
@DisplayName("ImageStorageKey")
class ImageStorageKeyTest {

	@Test
	@DisplayName("회원 키는 모두 users id 슬래시로 시작한다")
	void 회원_키는_모두_users_id_슬래시로_시작한다() {
		// given
		long userId = 7;

		// when
		List<String> keys = List.of(ImageStorage.comicKey(userId, 1), ImageStorage.videoPrefix(userId, 1),
				ImageStorage.videoKey(userId, 1), ImageStorage.videoThumbKey(userId, 1),
				ImageStorage.videoAudioKey(userId, 1), ImageStorage.recordingAudioKey(userId, 1, 0),
				ImageStorage.recordingAudioPrefix(userId, 1), ImageStorage.cardAudioKey(userId, 1),
				ImageStorage.profileCharacterKey(userId), ImageStorage.profileCharacterThumbKey(userId),
				ImageStorage.profilePhotoKey(userId), ImageStorage.voiceCloneSampleKey(userId, "wav"),
				ImageStorage.voiceClonePreviewKey(userId));

		// then
		assertThat(keys).allSatisfy(key -> assertThat(key).startsWith("users/7/"));
	}

	@Test
	@DisplayName("영상 키는 영상 prefix 아래다")
	void 영상_키는_영상_prefix_아래다() {
		// given
		String prefix = ImageStorage.videoPrefix(7, 3);

		// when & then
		assertThat(List.of(ImageStorage.videoKey(7, 3), ImageStorage.videoThumbKey(7, 3),
				ImageStorage.videoAudioKey(7, 3)))
			.allSatisfy(key -> assertThat(key).startsWith(prefix));
	}

	@Test
	@DisplayName("녹음 음성 키는 녹음 음성 prefix 아래다")
	void 녹음_음성_키는_녹음_음성_prefix_아래다() {
		// given
		String prefix = ImageStorage.recordingAudioPrefix(7, 3);

		// when & then
		assertThat(ImageStorage.recordingAudioKey(7, 3, 0)).startsWith(prefix);
		assertThat(ImageStorage.recordingAudioKey(7, 3, 12)).startsWith(prefix);
	}

	@Test
	@DisplayName("prefix가 슬래시로 끝나 회원 1과 12가 섞이지 않는다")
	void prefix가_슬래시로_끝나_회원_1과_12가_섞이지_않는다() {
		// when & then
		assertThat(ImageStorage.videoPrefix(1, 1)).endsWith("/");
		assertThat(ImageStorage.recordingAudioPrefix(1, 1)).endsWith("/");

		assertThat(ImageStorage.comicKey(12, 1)).doesNotStartWith("users/1/");
		assertThat(ImageStorage.videoKey(12, 1)).doesNotStartWith(ImageStorage.videoPrefix(1, 1));
		assertThat(ImageStorage.recordingAudioKey(12, 1, 0)).doesNotStartWith(ImageStorage.recordingAudioPrefix(1, 1));
	}

	@Test
	@DisplayName("같은 회원의 영상 1과 12도 섞이지 않는다")
	void 같은_회원의_영상_1과_12도_섞이지_않는다() {
		// when & then
		assertThat(ImageStorage.videoKey(1, 12)).doesNotStartWith(ImageStorage.videoPrefix(1, 1));
		assertThat(ImageStorage.recordingAudioKey(1, 12, 0)).doesNotStartWith(ImageStorage.recordingAudioPrefix(1, 1));
	}

}
