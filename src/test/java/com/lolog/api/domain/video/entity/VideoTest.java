package com.lolog.api.domain.video.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

@DisplayName("Video")
class VideoTest {

	private static Video video(VideoStatus status) {
		Video video = new Video(1L, 2L, "요약");
		ReflectionTestUtils.setField(video, "status", status);
		return video;
	}

	@Test
	@DisplayName("새 영상은 QUEUED로 시작한다")
	void 새_영상은_QUEUED로_시작한다() {
		// when
		Video video = new Video(1L, 2L, "요약");

		// then
		assertThat(video.getStatus()).isEqualTo(VideoStatus.QUEUED);
		assertThat(video.isInProgress()).isTrue();
		assertThat(video.hasStoryboard()).isFalse();
	}

	@Test
	@DisplayName("startVoice는 QUEUED를 VOICE로 바꾼다")
	void startVoice는_QUEUED를_VOICE로_바꾼다() {
		// given
		Video video = video(VideoStatus.QUEUED);

		// when
		video.startVoice();

		// then
		assertThat(video.getStatus()).isEqualTo(VideoStatus.VOICE);
	}

	@ParameterizedTest
	@DisplayName("startVoice는 QUEUED가 아니면 상태를 바꾸지 않는다")
	@EnumSource(value = VideoStatus.class, names = "QUEUED", mode = EnumSource.Mode.EXCLUDE)
	void startVoice는_QUEUED가_아니면_상태를_바꾸지_않는다(VideoStatus status) {
		// given
		Video video = video(status);

		// when
		video.startVoice();

		// then
		assertThat(video.getStatus()).isEqualTo(status);
	}

	@ParameterizedTest
	@DisplayName("처음 대사 음성을 받으면 DRAWING이 되고 true다")
	@EnumSource(value = VideoStatus.class, names = { "QUEUED", "VOICE" })
	void 처음_대사_음성을_받으면_DRAWING이_되고_true다(VideoStatus status) {
		// given
		Video video = video(status);

		// when
		boolean first = video.attachAudio("voice/1.wav");

		// then
		assertThat(first).isTrue();
		assertThat(video.getStatus()).isEqualTo(VideoStatus.DRAWING);
		assertThat(video.getAudioKey()).isEqualTo("voice/1.wav");
	}

	@Test
	@DisplayName("DRAWING에서 다시 대사 음성을 받으면 false이고 키만 바뀐다")
	void DRAWING에서_다시_대사_음성을_받으면_false이고_키만_바뀐다() {
		// given
		Video video = video(VideoStatus.VOICE);
		video.attachAudio("voice/1.wav");

		// when
		boolean first = video.attachAudio("voice/2.wav");

		// then
		assertThat(first).isFalse();
		assertThat(video.getStatus()).isEqualTo(VideoStatus.DRAWING);
		assertThat(video.getAudioKey()).isEqualTo("voice/2.wav");
	}

	@Test
	@DisplayName("콘티를 저장하면 hasStoryboard가 true다")
	void 콘티를_저장하면_hasStoryboard가_true다() {
		// given
		Video video = video(VideoStatus.VOICE);

		// when
		video.saveStoryboard("{\"v\":1}");

		// then
		assertThat(video.hasStoryboard()).isTrue();
		assertThat(video.getStoryboard()).isEqualTo("{\"v\":1}");
		assertThat(video.getStatus()).isEqualTo(VideoStatus.VOICE);
	}

	@Test
	@DisplayName("finish는 DONE으로 바꾸고 대사 음성 키를 비운다")
	void finish는_DONE으로_바꾸고_대사_음성_키를_비운다() {
		// given
		Video video = video(VideoStatus.VOICE);
		video.attachAudio("voice/1.wav");

		// when
		video.finish("video/1.mp4", "thumb/1.jpg", 12_345);

		// then
		assertThat(video.getStatus()).isEqualTo(VideoStatus.DONE);
		assertThat(video.getAudioKey()).isNull();
		assertThat(video.getVideoKey()).isEqualTo("video/1.mp4");
		assertThat(video.getThumbKey()).isEqualTo("thumb/1.jpg");
		assertThat(video.getDurationMs()).isEqualTo(12_345L);
		assertThat(video.isInProgress()).isFalse();
	}

	@Test
	@DisplayName("fail은 FAILED로 바꾸고 대사 음성 키를 비운다")
	void fail은_FAILED로_바꾸고_대사_음성_키를_비운다() {
		// given
		Video video = video(VideoStatus.VOICE);
		video.attachAudio("voice/1.wav");

		// when
		video.fail("그리기 실패");

		// then
		assertThat(video.getStatus()).isEqualTo(VideoStatus.FAILED);
		assertThat(video.getAudioKey()).isNull();
		assertThat(video.getFailReason()).isEqualTo("그리기 실패");
		assertThat(video.isInProgress()).isFalse();
	}

	@ParameterizedTest
	@DisplayName("만드는 중인 상태는 isInProgress가 true다")
	@EnumSource(value = VideoStatus.class, names = { "QUEUED", "VOICE", "DRAWING" })
	void 만드는_중인_상태는_isInProgress가_true다(VideoStatus status) {
		// when & then
		assertThat(video(status).isInProgress()).isTrue();
	}

	@ParameterizedTest
	@DisplayName("끝난 상태는 isInProgress가 false다")
	@EnumSource(value = VideoStatus.class, names = { "DONE", "FAILED" })
	void 끝난_상태는_isInProgress가_false다(VideoStatus status) {
		// when & then
		assertThat(video(status).isInProgress()).isFalse();
	}

}
