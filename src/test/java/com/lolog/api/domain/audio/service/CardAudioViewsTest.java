package com.lolog.api.domain.audio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.audio.service.CardAudioViews.CardAudio;
import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.recording.entity.Recording;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.global.storage.ImageStorage;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("CardAudioViews")
class CardAudioViewsTest {

	private static final long USER_ID = 1L;
	private static final long RECORDING_ID = 5L;

	@Mock
	private CardRepository cards;

	@Mock
	private RecordingRepository recordings;

	@Mock
	private ImageStorage storage;

	private CardAudioViews views;

	@BeforeEach
	void setUp() {
		views = new CardAudioViews(cards, recordings, storage);
	}

	// ---- ready ----

	@Test
	@DisplayName("카드 ID가 없으면 저장소를 읽지 않고 빈 결과다")
	void 카드_ID가_없으면_읽지_않는다() {
		// when
		Map<Long, Boolean> result = views.ready(List.of());

		// then
		assertThat(result).isEmpty();
		verifyNoInteractions(cards, recordings, storage);
	}

	@Test
	@DisplayName("구간 음성이 있으면 READY라 true, 없는 카드는 결과에서 빠진다")
	void 구간_음성이_있으면_true() {
		// given
		Card withAudio = card(10L, RECORDING_ID, "a.aac");
		Card without = card(11L, RECORDING_ID, null);
		given(cards.findAllById(List.of(10L, 11L, 12L))).willReturn(List.of(withAudio, without));
		given(recordings.findAllById(List.of(RECORDING_ID))).willReturn(List.of(recording(false, daysAgo(1))));

		// when
		Map<Long, Boolean> result = views.ready(List.of(10L, 11L, 12L));

		// then
		assertThat(result).containsExactlyInAnyOrderEntriesOf(Map.of(10L, true, 11L, false));
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("여러 카드가 같은 녹음이면 녹음 ID를 한 번만 넣어 읽는다")
	void 같은_녹음은_한_번만_읽는다() {
		// given
		given(cards.findAllById(List.of(10L, 11L)))
			.willReturn(List.of(card(10L, RECORDING_ID, null), card(11L, RECORDING_ID, null)));
		given(recordings.findAllById(List.of(RECORDING_ID))).willReturn(List.of());

		// when
		views.ready(List.of(10L, 11L));

		// then
		verify(recordings).findAllById(List.of(RECORDING_ID));
	}

	// ---- detail ----

	@Test
	@DisplayName("카드가 없으면 NONE이고 주소를 만들지 않는다")
	void 카드가_없으면_NONE() {
		// given
		given(cards.findAllById(List.of(10L))).willReturn(List.of());
		given(recordings.findAllById(List.of())).willReturn(List.of());

		// when
		CardAudio audio = views.detail(10L);

		// then
		assertThat(audio).isEqualTo(new CardAudio("NONE", null, null));
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("READY면 서명 주소와 길이를 준다")
	void READY면_주소와_길이를_준다() throws Exception {
		// given
		Card card = card(10L, RECORDING_ID, "a.aac");
		ReflectionTestUtils.setField(card, "audioDurationMs", 4200L);
		given(cards.findAllById(List.of(10L))).willReturn(List.of(card));
		given(recordings.findAllById(List.of(RECORDING_ID))).willReturn(List.of(recording(true, daysAgo(1))));
		given(storage.presignedUrl("a.aac")).willReturn(URI.create("https://files.test/a").toURL());

		// when
		CardAudio audio = views.detail(10L);

		// then
		assertThat(audio).isEqualTo(new CardAudio("READY", "https://files.test/a", 4200L));
	}

	@Test
	@DisplayName("녹음 기록이 지워진 카드라도 구간 음성이 있으면 READY다")
	void 녹음이_없어도_음성이_있으면_READY() throws Exception {
		// given
		given(cards.findAllById(List.of(10L))).willReturn(List.of(card(10L, null, "a.aac")));
		given(recordings.findAllById(List.of())).willReturn(List.of());
		given(storage.presignedUrl("a.aac")).willReturn(URI.create("https://files.test/a").toURL());

		// when & then
		assertThat(views.detail(10L).status()).isEqualTo("READY");
	}

	@Test
	@DisplayName("음성 저장을 켠 녹음이 끝난 지 7일 안이면 PENDING이고 주소를 만들지 않는다")
	void 칠일_안이면_PENDING() {
		// given
		givenOne(recording(true, daysAgo(6)));

		// when
		CardAudio audio = views.detail(10L);

		// then
		assertThat(audio).isEqualTo(new CardAudio("PENDING", null, null));
		verifyNoInteractions(storage);
	}

	@Test
	@DisplayName("음성 저장을 켠 녹음이라도 끝난 지 7일이 지났으면 NONE이다")
	void 칠일이_지났으면_NONE() {
		// given
		givenOne(recording(true, daysAgo(8)));

		// when & then
		assertThat(views.detail(10L).status()).isEqualTo("NONE");
	}

	@Test
	@DisplayName("음성 저장을 끈 녹음이면 NONE이다")
	void 음성_저장을_껐으면_NONE() {
		// given
		givenOne(recording(false, daysAgo(1)));

		// when & then
		assertThat(views.detail(10L).status()).isEqualTo("NONE");
	}

	@Test
	@DisplayName("녹음 끝 시각이 없으면 NONE이다")
	void 끝_시각이_없으면_NONE() {
		// given
		givenOne(recording(true, null));

		// when & then
		assertThat(views.detail(10L).status()).isEqualTo("NONE");
	}

	@Test
	@DisplayName("녹음 기록이 지워져 recordingId가 없으면 NONE이다")
	void 녹음_기록이_지워졌으면_NONE() {
		// given
		given(cards.findAllById(List.of(10L))).willReturn(List.of(card(10L, null, null)));
		given(recordings.findAllById(List.of())).willReturn(List.of());

		// when & then
		assertThat(views.detail(10L).status()).isEqualTo("NONE");
	}

	@Test
	@DisplayName("카드의 녹음을 저장소에서 찾지 못하면 NONE이다")
	void 녹음을_찾지_못하면_NONE() {
		// given
		given(cards.findAllById(List.of(10L))).willReturn(List.of(card(10L, RECORDING_ID, null)));
		given(recordings.findAllById(List.of(RECORDING_ID))).willReturn(List.of());

		// when & then
		assertThat(views.detail(10L).status()).isEqualTo("NONE");
	}

	private void givenOne(Recording recording) {
		given(cards.findAllById(List.of(10L))).willReturn(List.of(card(10L, RECORDING_ID, null)));
		given(recordings.findAllById(List.of(RECORDING_ID))).willReturn(List.of(recording));
	}

	private static Card card(long id, Long recordingId, String audioKey) {
		Card card = new Card(recordingId, USER_ID, "웃김", "요약", 0, 1000, OffsetDateTime.now());
		ReflectionTestUtils.setField(card, "id", id);
		ReflectionTestUtils.setField(card, "audioKey", audioKey);
		return card;
	}

	private static Recording recording(boolean audioSaved, OffsetDateTime endedAt) {
		Recording recording = new Recording(USER_ID, "c", daysAgo(30), audioSaved);
		ReflectionTestUtils.setField(recording, "id", RECORDING_ID);
		recording.setEndedAt(endedAt);
		return recording;
	}

	private static OffsetDateTime daysAgo(int days) {
		return OffsetDateTime.now().minusDays(days);
	}

}
