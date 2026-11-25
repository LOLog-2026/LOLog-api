package com.lolog.api.domain.sound.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.sound.entity.Sound;
import com.lolog.api.domain.sound.repository.SoundRepository;
import com.lolog.api.domain.sound.repository.SoundRepository.SoundMatch;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.storage.StorageDeletions;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
@DisplayName("SoundService")
class SoundServiceTest {

	private static final String MODEL = "clap-test";
	private static final long FREESOUND_ID = 1234L;
	private static final long SOUND_ID = 9L;
	private static final double SFX_MAX = 0.3;
	private static final double BGM_MAX = 0.6;

	@Mock
	private SoundRepository repository;

	@Mock
	private ImageStorage storage;

	@Mock
	private StorageDeletions deletions;

	@Mock
	private SoundTextEmbedder embedder;

	private SoundService service;

	@BeforeEach
	void setUp() {
		lenient().when(embedder.modelName()).thenReturn(MODEL);
		lenient().when(storage.presignedUrl(anyString()))
			.thenAnswer(inv -> URI.create("https://s3.test/" + inv.getArgument(0)).toURL());
		service = new SoundService(repository, storage, deletions, embedder, JsonMapper.builder().build(), SFX_MAX, BGM_MAX);
	}

	// ---- upsert 검사 ----

	// 바른 값에서 하나만 바꾼 요청과 그때의 field
	static Stream<Arguments> invalidUpserts() {
		String ok = embedding(0.1);
		return Stream.of(
				Arguments.of("freesoundId 0", new Upsert(0, ogg(), "SFX", "door", 1000, "https://freesound.org/s/1", null, ok, MODEL), "freesoundId"),
				Arguments.of("빈 파일", new Upsert(1, new byte[0], "SFX", "door", 1000, "https://f/1", null, ok, MODEL), "file"),
				Arguments.of("null 파일", new Upsert(1, null, "SFX", "door", 1000, "https://f/1", null, ok, MODEL), "file"),
				Arguments.of("Ogg 아님", new Upsert(1, "RIFF".getBytes(), "SFX", "door", 1000, "https://f/1", null, ok, MODEL), "file"),
				Arguments.of("모르는 kind", new Upsert(1, ogg(), "VOICE", "door", 1000, "https://f/1", null, ok, MODEL), "kind"),
				Arguments.of("kind null", new Upsert(1, ogg(), null, "door", 1000, "https://f/1", null, ok, MODEL), "kind"),
				Arguments.of("검색어 대문자", new Upsert(1, ogg(), "SFX", "Door", 1000, "https://f/1", null, ok, MODEL), "query"),
				Arguments.of("검색어 51자", new Upsert(1, ogg(), "SFX", "a".repeat(51), 1000, "https://f/1", null, ok, MODEL), "query"),
				Arguments.of("길이 0", new Upsert(1, ogg(), "SFX", "door", 0, "https://f/1", null, ok, MODEL), "durationMs"),
				Arguments.of("길이 null", new Upsert(1, ogg(), "SFX", "door", null, "https://f/1", null, ok, MODEL), "durationMs"),
				Arguments.of("ftp 주소", new Upsert(1, ogg(), "SFX", "door", 1000, "ftp://f/1", null, ok, MODEL), "sourceUrl"),
				Arguments.of("빈 주소", new Upsert(1, ogg(), "SFX", "door", 1000, "  ", null, ok, MODEL), "sourceUrl"),
				Arguments.of("501자 주소", new Upsert(1, ogg(), "SFX", "door", 1000, "https://" + "a".repeat(493), null, ok, MODEL), "sourceUrl"),
				Arguments.of("CC0 아닌 라이선스", new Upsert(1, ogg(), "SFX", "door", 1000, "https://f/1", "CC-BY", ok, MODEL), "license"),
				Arguments.of("벡터 null", new Upsert(1, ogg(), "SFX", "door", 1000, "https://f/1", null, null, MODEL), "embedding"),
				Arguments.of("벡터 JSON 아님", new Upsert(1, ogg(), "SFX", "door", 1000, "https://f/1", null, "[1,2", MODEL), "embedding"),
				Arguments.of("벡터 511개", new Upsert(1, ogg(), "SFX", "door", 1000, "https://f/1", null, embedding(511, 0.1), MODEL), "embedding"),
				Arguments.of("벡터 모두 0", new Upsert(1, ogg(), "SFX", "door", 1000, "https://f/1", null, embedding(0.0), MODEL), "embedding"),
				Arguments.of("다른 모델", new Upsert(1, ogg(), "SFX", "door", 1000, "https://f/1", null, ok, "other"), "embeddingModel"),
				Arguments.of("모델 null", new Upsert(1, ogg(), "SFX", "door", 1000, "https://f/1", null, ok, null), "embeddingModel"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidUpserts")
	@DisplayName("틀린 칸이 있으면 그 칸 INVALID_REQUEST이고 넣지도 올리지도 않는다")
	void 틀린_칸이면_INVALID_REQUEST(String name, Upsert u, String field) {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> call(u));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo(field);
		verifyNoInteractions(repository, storage);
	}

	@Test
	@DisplayName("5MB를 넘는 파일은 PAYLOAD_TOO_LARGE다")
	void 큰_파일은_PAYLOAD_TOO_LARGE() {
		// given
		byte[] file = new byte[(int) SoundService.MAX_FILE_BYTES + 1];
		System.arraycopy(ogg(), 0, file, 0, 4);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> call(
				new Upsert(1, file, "SFX", "door", 1000, "https://f/1", null, embedding(0.1), MODEL)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
		verifyNoInteractions(repository);
	}

	// ---- upsert 성공 ----

	@Test
	@DisplayName("줄을 먼저 넣고 id로 정한 키를 채운 뒤 파일을 올린다")
	void 줄을_넣고_파일을_올린다() {
		// given
		Sound sound = new Sound();
		given(repository.upsert(eq(FREESOUND_ID), eq("BGM"), eq("calm_piano"), eq("Calm piano"), eq(30000),
				eq("https://freesound.org/s/1234"), anyString(), eq(MODEL))).willReturn(SOUND_ID);
		given(repository.findById(SOUND_ID)).willReturn(Optional.of(sound));
		byte[] file = ogg();

		// when
		SoundService.SoundSaved saved = service.upsert(FREESOUND_ID, file, "BGM", "calm_piano", "  Calm piano  ", 30000,
				" https://freesound.org/s/1234 ", " CC0 ", embedding(0.5), " " + MODEL + " ");

		// then
		assertThat(saved.id()).isEqualTo(SOUND_ID);
		String key = ImageStorage.soundKey(SOUND_ID);
		assertThat(sound.getObjectKey()).isEqualTo(key);
		InOrder order = inOrder(repository, storage);
		order.verify(repository).upsert(anyLong(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(),
				anyString());
		order.verify(storage).put(key, file, "audio/ogg");
	}

	@Test
	@DisplayName("벡터는 pgvector 글 모양으로 넘긴다")
	void 벡터를_pgvector_글로_넘긴다() {
		// given
		given(repository.upsert(anyLong(), anyString(), anyString(), any(), anyInt(), anyString(), anyString(), anyString()))
			.willReturn(SOUND_ID);
		given(repository.findById(SOUND_ID)).willReturn(Optional.of(new Sound()));

		// when
		service.upsert(FREESOUND_ID, ogg(), "SFX", "door", null, 1000, "http://f/1", null, embedding(0.5), MODEL);

		// then
		String expected = "[" + IntStream.range(0, 512).mapToObj(i -> "0.5").collect(Collectors.joining(",")) + "]";
		verify(repository).upsert(eq(FREESOUND_ID), eq("SFX"), eq("door"), eq(null), eq(1000), eq("http://f/1"),
				eq(expected), eq(MODEL));
	}

	@Test
	@DisplayName("제목은 공백이면 null, 200자를 넘으면 글자(코드 포인트) 200개로 자른다")
	void 제목을_다듬는다() {
		// given
		given(repository.upsert(anyLong(), anyString(), anyString(), any(), anyInt(), anyString(), anyString(), anyString()))
			.willReturn(SOUND_ID);
		given(repository.findById(SOUND_ID)).willReturn(Optional.of(new Sound()));
		String emoji = "😀";

		// when
		service.upsert(1L, ogg(), "SFX", "door", "   ", 1000, "https://f/1", null, embedding(0.1), MODEL);
		service.upsert(2L, ogg(), "SFX", "door", emoji.repeat(201), 1000, "https://f/1", null, embedding(0.1), MODEL);

		// then
		verify(repository).upsert(eq(1L), anyString(), anyString(), eq(null), anyInt(), anyString(), anyString(), anyString());
		verify(repository).upsert(eq(2L), anyString(), anyString(), eq(emoji.repeat(200)), anyInt(), anyString(), anyString(),
				anyString());
	}

	// ---- delete ----

	@Test
	@DisplayName("지운 소리가 있으면 그 파일 지우기를 맡긴다")
	void 지운_소리의_파일을_지운다() {
		// given
		given(repository.deleteByFreesoundIdReturningKey(FREESOUND_ID)).willReturn(Optional.of("defaults/sounds/9.ogg"));

		// when
		service.delete(FREESOUND_ID);

		// then
		verify(deletions).schedule("defaults/sounds/9.ogg");
	}

	@Test
	@DisplayName("없는 소리여도 오류 없이 끝나고 지우기를 맡기지 않는다")
	void 없는_소리면_아무것도_안_한다() {
		// given
		given(repository.deleteByFreesoundIdReturningKey(FREESOUND_ID)).willReturn(Optional.empty());

		// when
		service.delete(FREESOUND_ID);

		// then
		verifyNoInteractions(deletions);
	}

	// ---- search 검사 ----

	static Stream<Arguments> invalidSearches() {
		return Stream.of(
				Arguments.of("요청 null", null, "kind"),
				Arguments.of("모르는 kind", new SoundService.SoundSearch("VOICE", "door", null), "kind"),
				Arguments.of("소문자 kind", new SoundService.SoundSearch("sfx", "door", null), "kind"),
				Arguments.of("글 null", new SoundService.SoundSearch("SFX", null, null), "text"),
				Arguments.of("글 공백", new SoundService.SoundSearch("SFX", "   ", null), "text"),
				Arguments.of("글 201자", new SoundService.SoundSearch("SFX", "가".repeat(201), null), "text"),
				Arguments.of("limit 0", new SoundService.SoundSearch("SFX", "door", 0), "limit"),
				Arguments.of("limit 11", new SoundService.SoundSearch("SFX", "door", 11), "limit"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidSearches")
	@DisplayName("검색 요청이 틀리면 그 칸 INVALID_REQUEST이고 글 벡터를 만들지 않는다")
	void 검색_요청이_틀리면_INVALID_REQUEST(String name, SoundService.SoundSearch request, String field) {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.search(request));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo(field);
		verify(embedder, never()).embed(anyString());
		verifyNoInteractions(repository);
	}

	// ---- search ----

	@Test
	@DisplayName("효과음은 효과음 거리 기준과 기본 5개로 찾고 서명 주소를 붙인다")
	void 효과음은_효과음_기준으로_찾는다() {
		// given
		given(embedder.embed("door slamming")).willReturn(new float[] { 1.0f, 0.5f });
		given(repository.searchNearest("SFX", "[1.0,0.5]", MODEL, SFX_MAX, SoundService.DEFAULT_LIMIT))
			.willReturn(List.of(new Match(3L, "SFX", 800, "defaults/sounds/3.ogg", 0.12)));

		// when
		SoundService.SoundMatches result = service.search(new SoundService.SoundSearch("SFX", "  door slamming  ", null));

		// then
		assertThat(result.sounds()).containsExactly(
				new SoundService.SoundMatchItem(3L, "SFX", 800, 0.12, "https://s3.test/defaults/sounds/3.ogg"));
	}

	@Test
	@DisplayName("배경음은 배경음 거리 기준과 받은 limit으로 찾는다")
	void 배경음은_배경음_기준으로_찾는다() {
		// given
		given(embedder.embed("calm")).willReturn(new float[] { 0.25f });
		given(repository.searchNearest("BGM", "[0.25]", MODEL, BGM_MAX, 10)).willReturn(List.of());

		// when
		SoundService.SoundMatches result = service.search(new SoundService.SoundSearch("BGM", "calm", 10));

		// then
		assertThat(result.sounds()).isEmpty();
	}

	@Test
	@DisplayName("거리 기준 설정이 없으면 2.0(모두)으로 찾는다")
	void 설정이_없으면_모두_찾는다() {
		// given
		SoundService noLimit = new SoundService(repository, storage, deletions, embedder, JsonMapper.builder().build(), null,
				null);
		given(embedder.embed("calm")).willReturn(new float[] { 0.25f });

		// when
		noLimit.search(new SoundService.SoundSearch("BGM", "calm", 1));
		noLimit.search(new SoundService.SoundSearch("SFX", "calm", 1));

		// then
		verify(repository).searchNearest("BGM", "[0.25]", MODEL, SoundService.NO_MAX_DISTANCE, 1);
		verify(repository).searchNearest("SFX", "[0.25]", MODEL, SoundService.NO_MAX_DISTANCE, 1);
	}

	@Test
	@DisplayName("글 벡터 모델이 EMBEDDING_UNAVAILABLE을 던지면 그대로 올리고 검색하지 않는다")
	void 모델을_못_쓰면_EMBEDDING_UNAVAILABLE() {
		// given
		given(embedder.embed("door")).willThrow(new ApiException(ErrorCode.EMBEDDING_UNAVAILABLE));

		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.search(new SoundService.SoundSearch("SFX", "door", null)));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.EMBEDDING_UNAVAILABLE);
		verify(repository, never()).searchNearest(anyString(), anyString(), anyString(), anyDouble(), anyInt());
	}

	// ---- 도움 ----

	@Test
	@DisplayName("OggS 머리말만 Ogg로 본다")
	void Ogg_머리말() {
		// when & then
		assertThat(SoundService.isOgg(ogg())).isTrue();
		assertThat(SoundService.isOgg("Ogg".getBytes())).isFalse();
		assertThat(SoundService.isOgg("RIFF".getBytes())).isFalse();
	}

	// upsert 인자 묶음
	record Upsert(long freesoundId, byte[] file, String kind, String query, Integer durationMs, String sourceUrl,
			String license, String embedding, String model) {
	}

	// 묶음으로 upsert를 부른다(제목은 없음).
	private void call(Upsert u) {
		service.upsert(u.freesoundId(), u.file(), u.kind(), u.query(), null, u.durationMs(), u.sourceUrl(), u.license(),
				u.embedding(), u.model());
	}

	// 검색 결과 한 줄
	record Match(Long getId, String getKind, Integer getDurationMs, String getObjectKey, Double getDistance)
			implements SoundMatch {
	}

	// OggS로 시작하는 파일
	private static byte[] ogg() {
		return "OggS\0\0\0\0".getBytes();
	}

	// 값이 모두 같은 512개 JSON 배열
	private static String embedding(double value) {
		return embedding(ClapTextEmbedder.DIMENSIONS, value);
	}

	// 값이 모두 같은 size개 JSON 배열
	private static String embedding(int size, double value) {
		double[] values = new double[size];
		Arrays.fill(values, value);
		return Arrays.toString(values);
	}

}
