package com.lolog.api.domain.inbox.repository;

import static com.lolog.api.support.RepositoryRows.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.lolog.api.domain.inbox.repository.InboxQueries.Cursor;
import com.lolog.api.domain.inbox.repository.InboxQueries.Row;
import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("InboxQueries")
@Import(InboxQueries.class)
class InboxQueriesTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;
	// 알림은 이 시각보다 뒤의 것만 읽는다
	private static final OffsetDateTime SINCE = at(-100);

	@Autowired
	private InboxQueries queries;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
	}

	@Test
	@DisplayName("최근 것이 위이고, 같은 시각이면 알림이 공지보다 앞이며 같은 종류는 ID가 큰 것이 앞이다")
	void 시각_종류_ID_순서로_읽는다() {
		// given
		long oldNotice = rows.notice("옛 공지", at(-1));
		long n1 = rows.notice("공지1", at(0));
		long x1 = rows.notification(ME, "COMIC_DONE", at(0), null);
		long n2 = rows.notice("공지2", at(0));
		long x2 = rows.notification(ME, "COMIC_DONE", at(0), null);
		long newX = rows.notification(ME, "COMIC_DONE", at(1), null);

		// when
		List<Row> page = queries.page(ME, true, true, SINCE, null, 100);

		// then
		assertThat(page).extracting(Row::kind, Row::id)
			.containsExactly(tuple("NOTIFICATION", newX), tuple("NOTIFICATION", x2), tuple("NOTIFICATION", x1),
					tuple("NOTICE", n2), tuple("NOTICE", n1), tuple("NOTICE", oldNotice));
	}

	@ParameterizedTest(name = "한 쪽에 {0}줄씩 이어 읽어도 겹치거나 빠지는 줄이 없다")
	@ValueSource(ints = { 1, 2, 3, 4 })
	@DisplayName("같은 시각에 공지와 알림이 섞여도 커서로 이어 읽으면 겹치거나 빠지는 줄이 없다")
	void 같은_시각에_섞여도_커서로_이어_읽으면_겹침과_누락이_없다(int limit) {
		// given
		rows.notice("앞 공지", at(1));
		for (int i = 0; i < 3; i++) {
			rows.notice("같은 시각 공지" + i, at(0));
			rows.notification(ME, "COMIC_DONE", at(0), null);
		}
		rows.notification(ME, "COMIC_DONE", at(-1), null);
		rows.notice("뒤 공지", at(-1));
		List<Row> all = queries.page(ME, true, true, SINCE, null, 100);

		// when
		List<Row> joined = new ArrayList<>();
		Cursor cursor = null;
		for (int guard = 0; guard < 100; guard++) {
			List<Row> page = queries.page(ME, true, true, SINCE, cursor, limit);
			joined.addAll(page);
			if (page.size() < limit) {
				break;
			}
			Row last = page.get(page.size() - 1);
			cursor = new Cursor(last.createdAt(), last.kind(), last.id());
		}

		// then
		assertThat(all).hasSize(9);
		assertThat(joined).extracting(Row::kind, Row::id)
			.containsExactlyElementsOf(all.stream().map(r -> tuple(r.kind(), r.id())).toList());
	}

	@Test
	@DisplayName("커서가 같은 시각의 공지이면 그 시각의 알림은 모두 이미 받은 것으로 보고 더 작은 ID의 공지부터 읽는다")
	void 공지_커서_뒤에는_같은_시각_알림이_오지_않는다() {
		// given
		long n1 = rows.notice("공지1", at(0));
		long n2 = rows.notice("공지2", at(0));
		rows.notification(ME, "COMIC_DONE", at(0), null);
		long older = rows.notification(ME, "COMIC_DONE", at(-1), null);

		// when
		List<Row> page = queries.page(ME, true, true, SINCE, new Cursor(at(0), "NOTICE", n2), 100);

		// then
		assertThat(page).extracting(Row::kind, Row::id)
			.containsExactly(tuple("NOTICE", n1), tuple("NOTIFICATION", older));
	}

	@Test
	@DisplayName("커서가 같은 시각의 알림이면 그 시각의 공지는 아직 받지 않은 것으로 보고 함께 읽는다")
	void 알림_커서_뒤에는_같은_시각_공지가_온다() {
		// given
		long n1 = rows.notice("공지1", at(0));
		long x1 = rows.notification(ME, "COMIC_DONE", at(0), null);
		long x2 = rows.notification(ME, "COMIC_DONE", at(0), null);

		// when
		List<Row> page = queries.page(ME, true, true, SINCE, new Cursor(at(0), "NOTIFICATION", x2), 100);

		// then
		assertThat(page).extracting(Row::kind, Row::id)
			.containsExactly(tuple("NOTIFICATION", x1), tuple("NOTICE", n1));
	}

	@Test
	@DisplayName("알림은 since보다 뒤의 것만, 내 것만 읽고 공지는 since와 상관없이 읽는다")
	void 알림은_since_뒤의_내_것만_읽는다() {
		// given
		long atSince = rows.notification(ME, "COMIC_DONE", SINCE, null);
		long afterSince = rows.notification(ME, "COMIC_DONE", SINCE.plusSeconds(1), null);
		rows.notification(OTHER, "COMIC_DONE", at(0), null);
		long oldNotice = rows.notice("옛 공지", SINCE.minusDays(1));

		// when
		List<Row> page = queries.page(ME, true, true, SINCE, null, 100);

		// then
		assertThat(page).extracting(Row::kind, Row::id)
			.containsExactly(tuple("NOTIFICATION", afterSince), tuple("NOTICE", oldNotice))
			.doesNotContain(tuple("NOTIFICATION", atSince));
	}

	@Test
	@DisplayName("공지는 내가 읽은 것만 읽음이고, 알림은 read_at이 있으면 읽음이다")
	void 읽음_여부를_담는다() {
		// given
		long readByMe = rows.notice("내가 읽음", at(2));
		long readByOther = rows.notice("남이 읽음", at(1));
		rows.noticeRead(readByMe, ME);
		rows.noticeRead(readByOther, OTHER);
		long read = rows.notification(ME, "COMIC_DONE", at(0), at(3));
		long unread = rows.notification(ME, "COMIC_DONE", at(-1), null);

		// when
		List<Row> page = queries.page(ME, true, true, SINCE, null, 100);

		// then
		assertThat(page).extracting(Row::id, Row::read)
			.containsExactly(tuple(readByMe, true), tuple(readByOther, false), tuple(read, true),
					tuple(unread, false));
	}

	@Test
	@DisplayName("공지 줄은 제목·본문, 알림 줄은 종류·값을 담는다")
	void 줄마다_종류에_맞는_값을_담는다() {
		// given
		rows.notice("공지", at(1));
		rows.notification(ME, "COMIC_DONE", at(0), null);

		// when
		List<Row> page = queries.page(ME, true, true, SINCE, null, 100);

		// then
		assertThat(page).extracting(Row::kind, Row::title, Row::body, Row::type, Row::data)
			.containsExactly(tuple("NOTICE", "공지", "공지 본문", null, null),
					tuple("NOTIFICATION", null, null, "COMIC_DONE", "{}"));
	}

	@Test
	@DisplayName("탭에 따라 공지만 또는 알림만 읽는다")
	void 탭에_따라_한쪽만_읽는다() {
		// given
		long notice = rows.notice("공지", at(1));
		long notification = rows.notification(ME, "COMIC_DONE", at(0), null);

		// when
		List<Row> noticesOnly = queries.page(ME, true, false, SINCE, null, 100);
		List<Row> notificationsOnly = queries.page(ME, false, true, SINCE, null, 100);

		// then
		assertThat(noticesOnly).extracting(Row::id).containsExactly(notice);
		assertThat(notificationsOnly).extracting(Row::id).containsExactly(notification);
	}

}
