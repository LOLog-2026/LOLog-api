package com.lolog.api.domain.character.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lolog.api.domain.character.entity.DefaultCharacter;
import com.lolog.api.domain.character.repository.DefaultCharacterRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultCharacterService")
class DefaultCharacterServiceTest {

	private static final String ID = "young-f-01";

	@Mock
	private DefaultCharacterRepository repository;

	@Mock
	private ImageStorage storage;

	@InjectMocks
	private DefaultCharacterService service;

	// ---- list ----

	@Test
	@DisplayName("목록은 리포지토리 순서대로 서명 주소를 붙이고 목소리가 없으면 voiceUrl이 null이다")
	void 목록에_서명_주소를_붙인다() throws Exception {
		// given
		DefaultCharacter withVoice = character("young-f-01", "thumb/1.webp", "voice/1.wav");
		DefaultCharacter noVoice = character("young-m-01", "thumb/2.webp", null);
		given(repository.findAllByOrderBySortOrderAscIdAsc()).willReturn(List.of(withVoice, noVoice));
		given(storage.presignedUrl(anyString())).willAnswer(inv -> url(inv.getArgument(0)));

		// when
		DefaultCharacterService.DefaultCharacterList list = service.list();

		// then
		assertThat(list.characters()).extracting(DefaultCharacterService.DefaultCharacterItem::id)
			.containsExactly("young-f-01", "young-m-01");
		DefaultCharacterService.DefaultCharacterItem first = list.characters().getFirst();
		assertThat(first.label()).isEqualTo("label-young-f-01");
		assertThat(first.ageGroup()).isEqualTo("YOUNG");
		assertThat(first.gender()).isEqualTo("F");
		assertThat(first.glasses()).isTrue();
		assertThat(first.thumbnailUrl()).isEqualTo("https://s3.test/thumb/1.webp");
		assertThat(first.voiceUrl()).isEqualTo("https://s3.test/voice/1.wav");
		assertThat(list.characters().get(1).voiceUrl()).isNull();
	}

	// ---- upsert 검사 ----

	// 틀린 값 하나씩과 그때의 field
	static Stream<Arguments> invalidInputs() {
		return Stream.of(
				Arguments.of("id 대문자", "Young-F", "YOUNG", "F", true, "라벨", "생김새", 1, "id"),
				Arguments.of("id 21자", "a".repeat(21), "YOUNG", "F", true, "라벨", "생김새", 1, "id"),
				Arguments.of("id null", null, "YOUNG", "F", true, "라벨", "생김새", 1, "id"),
				Arguments.of("모르는 나이대", ID, "OLD", "F", true, "라벨", "생김새", 1, "ageGroup"),
				Arguments.of("모르는 성별", ID, "YOUNG", "X", true, "라벨", "생김새", 1, "gender"),
				Arguments.of("안경 null", ID, "YOUNG", "F", null, "라벨", "생김새", 1, "glasses"),
				Arguments.of("라벨 공백", ID, "YOUNG", "F", true, "  ", "생김새", 1, "label"),
				Arguments.of("라벨 51자", ID, "YOUNG", "F", true, "a".repeat(51), "생김새", 1, "label"),
				Arguments.of("생김새 null", ID, "YOUNG", "F", true, "라벨", null, 1, "appearance"),
				Arguments.of("생김새 201자", ID, "YOUNG", "F", true, "라벨", "a".repeat(201), 1, "appearance"),
				Arguments.of("순서 null", ID, "YOUNG", "F", true, "라벨", "생김새", null, "sortOrder"));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("invalidInputs")
	@DisplayName("틀린 칸이 있으면 그 칸 INVALID_REQUEST이고 아무것도 올리거나 넣지 않는다")
	void 틀린_칸이면_INVALID_REQUEST(String name, String id, String ageGroup, String gender, Boolean glasses, String label,
			String appearance, Integer sortOrder, String field) {
		// when
		ApiException e = catchThrowableOfType(ApiException.class,
				() -> service.upsert(id, null, null, null, ageGroup, gender, glasses, label, appearance, sortOrder));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo(field);
		verifyNoInteractions(storage, repository);
	}

	@Test
	@DisplayName("768×768이 아닌 PNG는 image 칸 INVALID_REQUEST다")
	void 크기가_틀린_그림은_INVALID_REQUEST() {
		// given
		byte[] image = pngHeader(512, 512);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> upsert(image, null, null));

		// then
		assertThat(e.getField()).isEqualTo("image");
		verifyNoInteractions(storage, repository);
	}

	@Test
	@DisplayName("WebP가 아닌 줄인 그림은 thumbnail 칸 INVALID_REQUEST다")
	void WebP가_아니면_INVALID_REQUEST() {
		// given
		byte[] thumbnail = pngHeader(256, 256);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> upsert(null, thumbnail, null));

		// then
		assertThat(e.getField()).isEqualTo("thumbnail");
		verifyNoInteractions(storage, repository);
	}

	@Test
	@DisplayName("wav가 아닌 목소리는 voice 칸 INVALID_REQUEST이고 그림도 올리지 않는다")
	void wav가_아니면_INVALID_REQUEST() {
		// given
		byte[] voice = webp();

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> upsert(pngHeader(768, 768), webp(), voice));

		// then
		assertThat(e.getField()).isEqualTo("voice");
		verifyNoInteractions(storage, repository);
	}

	// ---- upsert 성공 ----

	@Test
	@DisplayName("파일을 다 보내면 그림·줄인 그림·목소리를 올리고 다듬은 값과 키로 넣는다")
	void 파일을_다_보내면_올리고_넣는다() {
		// given
		byte[] image = pngHeader(768, 768);
		byte[] thumbnail = webp();
		byte[] voice = wav();

		// when
		service.upsert(ID, image, thumbnail, voice, "YOUNG", "F", true, "  지원  ", "  웃는 얼굴  ", 3);

		// then
		String imageKey = ImageStorage.defaultCharacterKey(ID);
		String thumbKey = ImageStorage.defaultCharacterThumbKey(ID);
		String voiceKey = ImageStorage.defaultCharacterVoiceKey(ID);
		verify(storage).putPng(imageKey, image);
		verify(storage).put(thumbKey, thumbnail, "image/webp");
		verify(storage).put(voiceKey, voice, "audio/wav");
		verify(repository).upsert(ID, "YOUNG", "F", true, "지원", "웃는 얼굴", imageKey, thumbKey, voiceKey, 3);
	}

	@Test
	@DisplayName("파일을 빼면 올리지 않고 목록만 넣고 목소리 키는 null로 넘긴다")
	void 파일을_빼면_목록만_넣는다() {
		// given
		String id = "a".repeat(20);

		// when
		service.upsert(id, null, null, null, "MIDDLE", "M", false, "a".repeat(50), "a".repeat(200), 0);

		// then
		verifyNoInteractions(storage);
		verify(repository).upsert(id, "MIDDLE", "M", false, "a".repeat(50), "a".repeat(200),
				ImageStorage.defaultCharacterKey(id), ImageStorage.defaultCharacterThumbKey(id), null, 0);
	}

	// ---- hasVoice ----

	@Test
	@DisplayName("목소리가 붙어 있는지는 리포지토리에 묻는다")
	void hasVoice는_리포지토리에_묻는다() {
		// given
		given(repository.existsByIdAndVoiceKeyIsNotNull(ID)).willReturn(true);

		// when
		boolean result = service.hasVoice(ID);

		// then
		assertThat(result).isTrue();
	}

	// ---- 머리말 검사 ----

	@Test
	@DisplayName("PNG 머리말과 IHDR 가로·세로를 본다")
	void PNG_크기를_본다() {
		// when & then
		assertThat(DefaultCharacterService.isPng(pngHeader(768, 768), 768, 768)).isTrue();
		assertThat(DefaultCharacterService.isPng(pngHeader(768, 767), 768, 768)).isFalse();
		assertThat(DefaultCharacterService.isPng(new byte[23], 768, 768)).isFalse();
		assertThat(DefaultCharacterService.isWebp(webp())).isTrue();
		assertThat(DefaultCharacterService.isWav(wav())).isTrue();
		assertThat(DefaultCharacterService.isWav(webp())).isFalse();
	}

	// ---- 도움 ----

	// 그림·목소리만 바꿔 가며 부르는 upsert
	private void upsert(byte[] image, byte[] thumbnail, byte[] voice) {
		service.upsert(ID, image, thumbnail, voice, "YOUNG", "F", true, "라벨", "생김새", 1);
	}

	// 기본 화자 엔티티
	private static DefaultCharacter character(String id, String thumbKey, String voiceKey) {
		DefaultCharacter c = new DefaultCharacter();
		ReflectionTestUtils.setField(c, "id", id);
		ReflectionTestUtils.setField(c, "label", "label-" + id);
		ReflectionTestUtils.setField(c, "ageGroup", "YOUNG");
		ReflectionTestUtils.setField(c, "gender", "F");
		ReflectionTestUtils.setField(c, "glasses", true);
		ReflectionTestUtils.setField(c, "thumbKey", thumbKey);
		ReflectionTestUtils.setField(c, "voiceKey", voiceKey);
		return c;
	}

	// 키를 붙인 가짜 서명 주소
	private static URL url(String key) throws MalformedURLException {
		return URI.create("https://s3.test/" + key).toURL();
	}

	// PNG 서명과 IHDR 가로·세로만 적은 24바이트
	static byte[] pngHeader(int width, int height) {
		byte[] b = new byte[24];
		byte[] signature = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };
		System.arraycopy(signature, 0, b, 0, 8);
		b[12] = 'I';
		b[13] = 'H';
		b[14] = 'D';
		b[15] = 'R';
		writeInt(b, 16, width);
		writeInt(b, 20, height);
		return b;
	}

	// 4바이트 big-endian으로 적는다.
	private static void writeInt(byte[] b, int at, int value) {
		b[at] = (byte) (value >>> 24);
		b[at + 1] = (byte) (value >>> 16);
		b[at + 2] = (byte) (value >>> 8);
		b[at + 3] = (byte) value;
	}

	// RIFF....WEBP 머리말
	private static byte[] webp() {
		return "RIFF\0\0\0\0WEBPVP8 ".getBytes();
	}

	// RIFF....WAVE 머리말
	private static byte[] wav() {
		return "RIFF\0\0\0\0WAVEfmt ".getBytes();
	}

}
