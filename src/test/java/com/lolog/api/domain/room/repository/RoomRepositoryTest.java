package com.lolog.api.domain.room.repository;

import static com.lolog.api.support.RepositoryRows.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("RoomRepository")
class RoomRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private RoomRepository rooms;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
	}

	@Test
	@DisplayName("내 방 ID는 마지막 소식이 최근인 방부터, 소식이 없으면 만든 시각으로 줄 세운다")
	void 내_방_ID는_마지막_소식_시각_순이다() {
		// given
		long quiet = rows.room("조용", ME, at(10), null);
		long recent = rows.room("최근", ME, at(0), at(30));
		long middle = rows.room("중간", ME, at(0), at(20));
		long notMine = rows.room("남의방", OTHER, at(0), at(40));
		for (long room : List.of(quiet, recent, middle)) {
			rows.member(room, ME, at(0), at(0));
		}
		rows.member(notMine, OTHER, at(0), at(0));

		// when & then
		assertThat(rooms.findIdsOfMember(ME)).containsExactly(recent, middle, quiet);
	}

	@Test
	@DisplayName("마지막 소식 시각이 같은 방은 ID가 큰 방이 위다")
	void 같은_시각이면_ID가_큰_방이_위다() {
		// given
		long first = rows.room("하나", ME, at(0), at(5));
		long second = rows.room("둘", ME, at(5), null);
		rows.member(first, ME, at(0), at(0));
		rows.member(second, ME, at(0), at(0));

		// when & then
		assertThat(rooms.findIdsOfMember(ME)).containsExactly(second, first);
	}

	@Test
	@DisplayName("방 목록 줄은 마지막 대화 줄과, 마지막으로 본 뒤 다른 사람이 남긴 줄 수를 담는다")
	void 방_목록_줄은_마지막_대화와_받지_않은_글_수를_담는다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		rows.member(room, ME, at(0), at(10));
		rows.member(room, OTHER, at(0), at(0));
		rows.message(room, OTHER, "COMMENT", null, "본 뒤 전", at(5));
		rows.message(room, OTHER, "COMMENT", null, "본 시각과 같음", at(10));
		rows.message(room, OTHER, "COMMENT", null, "본 뒤", at(11));
		rows.message(room, ME, "COMMENT", null, "내 글", at(12));

		// when
		List<RoomItemView> items = rooms.findItems(ME, List.of(room));

		// then
		assertThat(items).singleElement().satisfies(item -> {
			assertThat(item.name()).isEqualTo("방");
			assertThat(item.lastType()).isEqualTo("COMMENT");
			assertThat(item.lastUserId()).isEqualTo(ME);
			assertThat(item.lastBody()).isEqualTo("내 글");
			assertThat(item.lastAt()).isAtSameInstantAs(at(12));
			// 본 시각과 같은 줄과 내 글은 세지 않는다
			assertThat(item.unread()).isEqualTo(1);
		});
	}

	@Test
	@DisplayName("대화가 없는 방은 마지막 줄 값이 비고 받지 않은 글 수가 0이다")
	void 대화가_없는_방은_마지막_줄이_비어_있다() {
		// given
		long empty = rows.room("빈방", ME, at(0), null);
		long excluded = rows.room("안읽음", ME, at(0), null);
		rows.member(empty, ME, at(0), at(0));
		rows.member(excluded, ME, at(0), at(0));

		// when
		List<RoomItemView> items = rooms.findItems(ME, List.of(empty));

		// then
		assertThat(items).singleElement().satisfies(item -> {
			assertThat(item.id()).isEqualTo(empty);
			assertThat(item.lastType()).isNull();
			assertThat(item.lastUserId()).isNull();
			assertThat(item.lastAt()).isNull();
			assertThat(item.unread()).isZero();
		});
	}

	@Test
	@DisplayName("마지막 소식 시각을 바꾸면 한 줄이 바뀌고, 없는 방은 0줄이다")
	void 마지막_소식_시각을_바꾼다() {
		// given
		long room = rows.room("방", ME, at(0), null);

		// when & then
		assertThat(rooms.touchActivity(room)).isEqualTo(1);
		assertThat(rooms.touchActivity(room + 1000)).isZero();
		OffsetDateTime last = jdbc.queryForObject("select last_activity_at from rooms where id = ?", OffsetDateTime.class,
				room);
		assertThat(last).isNotNull();
	}

	@Test
	@DisplayName("멤버가 남은 방은 지우지 않고, 멤버가 없는 방만 지운다")
	void 멤버가_없는_방만_지운다() {
		// given
		long withMember = rows.room("있음", ME, at(0), null);
		long empty = rows.room("없음", ME, at(0), null);
		rows.member(withMember, ME, at(0), at(0));

		// when & then
		assertThat(rooms.deleteIfEmpty(withMember)).isZero();
		assertThat(rooms.deleteIfEmpty(empty)).isEqualTo(1);
		assertThat(rooms.existsById(withMember)).isTrue();
		assertThat(rooms.existsById(empty)).isFalse();
	}

	@Test
	@DisplayName("멤버가 없는 방을 모두 지우면 멤버가 있는 방은 남는다")
	void 멤버가_없는_방을_모두_지운다() {
		// given
		long withMember = rows.room("있음", ME, at(0), null);
		rows.room("없음1", ME, at(0), null);
		rows.room("없음2", OTHER, at(0), null);
		rows.member(withMember, ME, at(0), at(0));

		// when & then
		assertThat(rooms.deleteAllEmpty()).isEqualTo(2);
		assertThat(rooms.findAll()).extracting(r -> r.getId()).containsExactly(withMember);
	}

	@Test
	@DisplayName("방장이 아직 멤버이면 방장을 넘기지 않는다")
	void 방장이_멤버이면_넘기지_않는다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		rows.member(room, ME, at(5), at(0));
		rows.member(room, OTHER, at(0), at(0));

		// when & then
		assertThat(rooms.handOverIfOwnerLeft(room)).isZero();
		assertThat(ownerOf(room)).isEqualTo(ME);
	}

	@Test
	@DisplayName("방장이 나가면 가장 먼저 들어온 멤버가 방장이 된다")
	void 방장이_나가면_가장_먼저_들어온_멤버가_방장이_된다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		rows.member(room, 3L, at(2), at(0));
		rows.member(room, 4L, at(1), at(0));

		// when & then
		assertThat(rooms.handOverIfOwnerLeft(room)).isEqualTo(1);
		assertThat(ownerOf(room)).isEqualTo(4L);
	}

	@Test
	@DisplayName("들어온 시각이 같으면 회원 ID가 작은 멤버가 방장이 된다")
	void 들어온_시각이_같으면_회원_ID가_작은_멤버가_방장이_된다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		rows.member(room, 7L, at(1), at(0));
		rows.member(room, 5L, at(1), at(0));

		// when
		rooms.handOverIfOwnerLeft(room);

		// then
		assertThat(ownerOf(room)).isEqualTo(5L);
	}

	@Test
	@DisplayName("방장인 방 ID만 찾는다")
	void 방장인_방_ID만_찾는다() {
		// given
		long mine = rows.room("내방", ME, at(0), null);
		rows.room("남의방", OTHER, at(0), null);

		// when & then
		assertThat(rooms.findIdsByOwnerId(ME)).containsExactly(mine);
	}

	private long ownerOf(long room) {
		return jdbc.queryForObject("select owner_id from rooms where id = ?", Long.class, room);
	}

}
