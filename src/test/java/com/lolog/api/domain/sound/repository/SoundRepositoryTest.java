package com.lolog.api.domain.sound.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.lolog.api.domain.sound.repository.SoundRepository.SoundMatch;
import com.lolog.api.support.RepositoryTest;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("SoundRepository")
class SoundRepositoryTest extends RepositoryTest {

	private static final String MODEL = "clap";

	@Autowired
	private SoundRepository soundRepository;

	@Autowired
	private JdbcTemplate jdbc;

	// 512차원 벡터 글. 앞 두 칸만 x, y로 채운다.
	private static String vector(double x, double y) {
		return IntStream.range(0, 512).mapToObj(i -> i == 0 ? String.valueOf(x) : i == 1 ? String.valueOf(y) : "0")
				.collect(Collectors.joining(",", "[", "]"));
	}

	private long sound(long freesoundId, String kind, String embedding, String model) {
		return soundRepository.upsert(freesoundId, kind, "검색어", "제목", 1000, "https://freesound.org/" + freesoundId,
				embedding, model);
	}

	private static List<Long> ids(List<SoundMatch> matches) {
		return matches.stream().map(SoundMatch::getId).toList();
	}

	@Test
	@DisplayName("upsert는 처음 보는 소리면 파일 키를 비우고 CC0으로 넣은 뒤 id를 돌려준다")
	void upsert_처음_보는_소리면_넣는다() {
		// when
		long id = sound(1L, "SFX", vector(1, 0), MODEL);

		// then
		Map<String, Object> row = jdbc.queryForMap("select * from sound_library where id = ?", id);
		assertThat(row).containsEntry("freesound_id", 1L).containsEntry("kind", "SFX").containsEntry("object_key", "")
				.containsEntry("license", "CC0").containsEntry("embedding_model", MODEL);
	}

	@Test
	@DisplayName("upsert는 같은 freesoundId가 있으면 같은 id로 덮어쓰고 파일 키는 그대로 둔다")
	void upsert_같은_소리면_덮어쓰고_파일_키는_둔다() {
		// given
		long id = sound(1L, "SFX", vector(1, 0), MODEL);
		jdbc.update("update sound_library set object_key = 'sounds/1.mp3' where id = ?", id);

		// when
		long again = soundRepository.upsert(1L, "BGM", "새 검색어", "새 제목", 2000, "https://freesound.org/new",
				vector(0, 1), "clap-2");

		// then
		assertThat(again).isEqualTo(id);
		Map<String, Object> row = jdbc.queryForMap("select * from sound_library where id = ?", id);
		assertThat(row).containsEntry("kind", "BGM").containsEntry("query", "새 검색어").containsEntry("title", "새 제목")
				.containsEntry("duration_ms", 2000).containsEntry("source_url", "https://freesound.org/new")
				.containsEntry("embedding_model", "clap-2").containsEntry("object_key", "sounds/1.mp3");
	}

	@Test
	@DisplayName("searchNearest는 같은 종류·같은 모델의 소리를 거리순, 같으면 id순으로 limit개 돌려준다")
	void searchNearest_거리순_다음_id순이다() {
		// given
		long near = sound(1L, "SFX", vector(1, 0), MODEL);
		long nearToo = sound(2L, "SFX", vector(2, 0), MODEL);
		long middle = sound(3L, "SFX", vector(1, 1), MODEL);
		sound(4L, "BGM", vector(1, 0), MODEL);
		sound(5L, "SFX", vector(1, 0), "other-model");
		long noEmbedding = sound(6L, "SFX", vector(1, 0), MODEL);
		jdbc.update("update sound_library set embedding = null where id = ?", noEmbedding);

		// when
		List<SoundMatch> matches = soundRepository.searchNearest("SFX", vector(1, 0), MODEL, 2.0, 10);

		// then
		assertThat(ids(matches)).containsExactly(near, nearToo, middle);
		assertThat(matches.get(0).getDistance()).isCloseTo(0.0, within(1e-6));
		assertThat(matches.get(2).getDistance()).isCloseTo(1 - Math.sqrt(0.5), within(1e-6));
		assertThat(matches.get(0).getKind()).isEqualTo("SFX");
		assertThat(matches.get(0).getDurationMs()).isEqualTo(1000);
		assertThat(matches.get(0).getObjectKey()).isEmpty();
		assertThat(ids(soundRepository.searchNearest("SFX", vector(1, 0), MODEL, 2.0, 2))).containsExactly(near, nearToo);
	}

	@Test
	@DisplayName("searchNearest는 maxDistance와 같은 거리의 소리는 넣고 더 먼 소리는 뺀다")
	void searchNearest_maxDistance보다_먼_소리는_뺀다() {
		// given
		long same = sound(1L, "SFX", vector(1, 0), MODEL);
		long orthogonal = sound(2L, "SFX", vector(0, 1), MODEL);
		sound(3L, "SFX", vector(-1, 0), MODEL);

		// when & then
		assertThat(ids(soundRepository.searchNearest("SFX", vector(1, 0), MODEL, 1.0, 10))).containsExactly(same, orthogonal);
	}

	@Test
	@DisplayName("deleteByFreesoundIdReturningKey는 지운 소리의 파일 키를 돌려주고, 없으면 비어 있다")
	void deleteByFreesoundIdReturningKey_지운_파일_키를_돌려준다() {
		// given
		long id = sound(1L, "SFX", vector(1, 0), MODEL);
		jdbc.update("update sound_library set object_key = 'sounds/1.mp3' where id = ?", id);

		// when & then
		assertThat(soundRepository.deleteByFreesoundIdReturningKey(1L)).contains("sounds/1.mp3");
		assertThat(soundRepository.deleteByFreesoundIdReturningKey(1L)).isEmpty();
		assertThat(jdbc.queryForObject("select count(*) from sound_library where freesound_id = 1", Long.class)).isZero();
	}

}
