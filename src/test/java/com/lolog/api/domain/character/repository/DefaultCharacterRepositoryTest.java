package com.lolog.api.domain.character.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.character.entity.DefaultCharacter;
import com.lolog.api.support.RepositoryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("DefaultCharacterRepository")
class DefaultCharacterRepositoryTest extends RepositoryTest {

	@Autowired
	private DefaultCharacterRepository repository;

	private void upsert(String id, String label, String voiceKey, int sortOrder) {
		repository.upsert(id, "YOUNG", "F", false, label, "생김새", "img/" + id, "thumb/" + id, voiceKey, sortOrder);
	}

	@Test
	@DisplayName("upsert는 처음 보는 id면 넣는다")
	void upsert_처음_보는_id면_넣는다() {
		// when
		repository.upsert("young-f-01", "YOUNG", "F", true, "지원", "단발", "img/1", "thumb/1", "voice/1", 3);

		// then
		DefaultCharacter c = repository.findById("young-f-01").orElseThrow();
		assertThat(c.getAgeGroup()).isEqualTo("YOUNG");
		assertThat(c.getGender()).isEqualTo("F");
		assertThat(c.isGlasses()).isTrue();
		assertThat(c.getLabel()).isEqualTo("지원");
		assertThat(c.getAppearance()).isEqualTo("단발");
		assertThat(c.getImageKey()).isEqualTo("img/1");
		assertThat(c.getThumbKey()).isEqualTo("thumb/1");
		assertThat(c.getVoiceKey()).isEqualTo("voice/1");
		assertThat(c.getSortOrder()).isEqualTo(3);
	}

	@Test
	@DisplayName("upsert는 같은 id가 있고 voiceKey가 null이면 다른 칸만 바꾸고 목소리는 그대로 둔다")
	void upsert_voiceKey가_null이면_목소리를_둔다() {
		// given
		upsert("young-f-01", "처음", "voice/old", 1);

		// when
		upsert("young-f-01", "바뀐 이름", null, 2);

		// then
		DefaultCharacter c = repository.findById("young-f-01").orElseThrow();
		assertThat(c.getLabel()).isEqualTo("바뀐 이름");
		assertThat(c.getSortOrder()).isEqualTo(2);
		assertThat(c.getVoiceKey()).isEqualTo("voice/old");
	}

	@Test
	@DisplayName("upsert는 같은 id가 있고 voiceKey를 주면 목소리를 바꾼다")
	void upsert_voiceKey를_주면_목소리를_바꾼다() {
		// given
		upsert("young-f-01", "처음", "voice/old", 1);

		// when
		upsert("young-f-01", "처음", "voice/new", 1);

		// then
		assertThat(repository.findById("young-f-01").orElseThrow().getVoiceKey()).isEqualTo("voice/new");
	}

	@Test
	@DisplayName("findAllByOrderBySortOrderAscIdAsc는 순서값순, 같으면 id순으로 읽는다")
	void findAll_순서값_다음_id순이다() {
		// given
		upsert("b", "나", null, 1);
		upsert("c", "다", null, 0);
		upsert("a", "가", null, 1);

		// when & then
		assertThat(repository.findAllByOrderBySortOrderAscIdAsc()).extracting(DefaultCharacter::getId)
				.containsSubsequence("c", "a", "b");
	}

	@Test
	@DisplayName("existsByIdAndVoiceKeyIsNotNull은 목소리가 붙은 기본 화자만 true다")
	void existsByIdAndVoiceKeyIsNotNull_목소리가_붙었을_때만_true다() {
		// given
		upsert("with", "가", "voice/1", 0);
		upsert("without", "나", null, 0);

		// when & then
		assertThat(repository.existsByIdAndVoiceKeyIsNotNull("with")).isTrue();
		assertThat(repository.existsByIdAndVoiceKeyIsNotNull("without")).isFalse();
		assertThat(repository.existsByIdAndVoiceKeyIsNotNull("none")).isFalse();
	}

}
