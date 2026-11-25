package com.lolog.api.domain.comic.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.comic.entity.Comic;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("ComicRepository")
class ComicRepositoryTest extends RepositoryTest {

	private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 1, 12, 0, 0, 0, ZoneOffset.UTC);

	@Autowired
	private ComicRepository comicRepository;

	@Autowired
	private JdbcTemplate jdbc;

	private long card() {
		return jdbc.queryForObject("""
				insert into cards (user_id, kind, summary, recorded_at) values (1, '웃김', '요약', ?) returning id
				""", Long.class, T0);
	}

	private long comic(long userId, Long cardId, String status) {
		return jdbc.queryForObject("insert into comics (user_id, card_id, status) values (?, ?, ?) returning id", Long.class,
				userId, cardId, status);
	}

	private static List<Long> ids(List<Comic> comics) {
		return comics.stream().map(Comic::getId).toList();
	}

	@Test
	@DisplayName("findPage는 내 만화를 최신순으로 읽고 before보다 작은 ID만 읽는다")
	void findPage_최신순으로_커서_앞만_읽는다() {
		// given
		long a = comic(1L, null, "DONE");
		long b = comic(1L, null, "DONE");
		long c = comic(1L, null, "FAILED");
		comic(2L, null, "DONE");

		// when & then
		assertThat(ids(comicRepository.findPage(1L, null, Limit.of(10)))).containsExactly(c, b, a);
		assertThat(ids(comicRepository.findPage(1L, c, Limit.of(10)))).containsExactly(b, a);
		assertThat(ids(comicRepository.findPage(1L, null, Limit.of(1)))).containsExactly(c);
	}

	@Test
	@DisplayName("findPageOldest는 내 만화를 오래된순으로 읽고 after보다 큰 ID만 읽는다")
	void findPageOldest_오래된순으로_커서_뒤만_읽는다() {
		// given
		long a = comic(1L, null, "DONE");
		long b = comic(1L, null, "DONE");
		long c = comic(1L, null, "DONE");
		comic(2L, null, "DONE");

		// when & then
		assertThat(ids(comicRepository.findPageOldest(1L, null, Limit.of(10)))).containsExactly(a, b, c);
		assertThat(ids(comicRepository.findPageOldest(1L, a, Limit.of(1)))).containsExactly(b);
	}

	@Test
	@DisplayName("countNotFailedByCardIds는 카드마다 실패 아닌 만화를 세고 그런 만화가 없는 카드는 뺀다")
	void countNotFailedByCardIds_실패를_빼고_센다() {
		// given
		long a = card();
		long b = card();
		long onlyFailed = card();
		comic(1L, a, "DONE");
		comic(1L, a, "DONE");
		comic(1L, a, "QUEUED");
		comic(1L, a, "FAILED");
		comic(1L, b, "DONE");
		comic(1L, onlyFailed, "FAILED");

		// when
		Map<Long, Long> counts = comicRepository.countNotFailedByCardIds(List.of(a, b, onlyFailed)).stream()
				.collect(Collectors.toMap(r -> (Long) r[0], r -> (Long) r[1]));

		// then
		assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(a, 3L, b, 1L));
	}

	@Test
	@DisplayName("deleteAllByUserId는 그 회원의 만화만 지운다")
	void deleteAllByUserId_그_회원의_만화만_지운다() {
		// given
		comic(1L, null, "DONE");
		comic(1L, null, "FAILED");
		long other = comic(2L, null, "DONE");

		// when
		int deleted = comicRepository.deleteAllByUserId(1L);

		// then
		assertThat(deleted).isEqualTo(2);
		assertThat(jdbc.queryForList("select id from comics where user_id in (1, 2)", Long.class)).containsExactly(other);
	}

}
