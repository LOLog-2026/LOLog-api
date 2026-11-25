package com.lolog.api.domain.room.repository;

import static com.lolog.api.support.RepositoryRows.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.friend.entity.FriendshipStatus;
import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("RoomMemberRepository")
class RoomMemberRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private RoomMemberRepository members;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
	}

	@Test
	@DisplayName("멤버로 넣으면 들어온 시각이 채워진다")
	void 멤버로_넣는다() {
		// given
		long room = rows.room("방", ME, at(0), null);

		// when
		members.insert(room, OTHER);

		// then
		assertThat(members.existsByRoomIdAndUserId(room, OTHER)).isTrue();
		OffsetDateTime joined = jdbc.queryForObject(
				"select created_at from room_members where room_id = ? and user_id = ?", OffsetDateTime.class, room,
				OTHER);
		assertThat(joined).isNotNull();
	}

	@Test
	@DisplayName("나 말고 남은 멤버 ID만 읽는다")
	void 나_말고_남은_멤버_ID만_읽는다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		long otherRoom = rows.room("다른방", ME, at(0), null);
		rows.member(room, ME, at(0), at(0));
		rows.member(room, OTHER, at(0), at(0));
		rows.member(room, 3L, at(0), at(0));
		rows.member(otherRoom, 4L, at(0), at(0));

		// when & then
		assertThat(members.findOtherUserIds(room, ME)).containsExactlyInAnyOrder(OTHER, 3L);
		assertThat(members.findUserIdsByRoomId(room)).containsExactlyInAnyOrder(ME, OTHER, 3L);
	}

	@Test
	@DisplayName("마지막으로 본 뒤 다른 사람이 남긴 줄이 있으면 받지 않은 글이 있다")
	void 본_뒤_다른_사람이_남긴_줄이_있으면_받지_않은_글이_있다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		rows.member(room, ME, at(0), at(10));
		rows.message(room, OTHER, "COMMENT", null, "본 뒤", at(11));

		// when & then
		assertThat(members.existsUnread(ME)).isTrue();
	}

	@Test
	@DisplayName("본 시각과 같은 줄, 내가 남긴 줄, 내가 없는 방의 줄은 받지 않은 글로 보지 않는다")
	void 받지_않은_글로_보지_않는_줄() {
		// given
		long room = rows.room("방", ME, at(0), null);
		long notMine = rows.room("남의방", OTHER, at(0), null);
		rows.member(room, ME, at(0), at(10));
		rows.member(notMine, OTHER, at(0), at(0));
		rows.message(room, OTHER, "COMMENT", null, "본 시각과 같음", at(10));
		rows.message(room, OTHER, "COMMENT", null, "본 뒤 전", at(9));
		rows.message(room, ME, "COMMENT", null, "내 글", at(20));
		rows.message(notMine, OTHER, "COMMENT", null, "내가 없는 방", at(20));

		// when & then
		assertThat(members.existsUnread(ME)).isFalse();
	}

	@Test
	@DisplayName("대화를 봤다고 남기면 그 앞의 줄은 받지 않은 글이 아니다")
	void 대화를_봤다고_남긴다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		rows.member(room, ME, at(0), at(0));
		rows.message(room, OTHER, "COMMENT", null, "글", at(5));

		// when & then
		assertThat(members.existsUnread(ME)).isTrue();

		assertThat(members.markRead(room, ME)).isEqualTo(1);
		assertThat(members.markRead(room, OTHER)).isZero();

		assertThat(members.existsUnread(ME)).isFalse();
	}

	@Test
	@DisplayName("멤버 줄을 지우면 1줄, 멤버가 아니면 0줄이다")
	void 멤버_줄을_지운다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		rows.member(room, ME, at(0), at(0));

		// when & then
		assertThat(members.deleteMember(room, ME)).isEqualTo(1);
		assertThat(members.deleteMember(room, ME)).isZero();
	}

	@Test
	@DisplayName("탈퇴하면 그 회원의 멤버 줄만 모두 지운다")
	void 탈퇴하면_그_회원의_멤버_줄만_지운다() {
		// given
		long a = rows.room("가", ME, at(0), null);
		long b = rows.room("나", ME, at(0), null);
		rows.member(a, ME, at(0), at(0));
		rows.member(b, ME, at(0), at(0));
		rows.member(a, OTHER, at(0), at(0));

		// when & then
		assertThat(members.deleteAllByUserId(ME)).isEqualTo(2);
		assertThat(members.findUserIdsByRoomId(a)).containsExactly(OTHER);
	}

	@Test
	@DisplayName("방 화면 사람 정보에 방장·멤버·친구 요청·차단·정지를 담고, 프로필이 없는 회원은 뺀다")
	void 방_화면_사람_정보를_읽는다() {
		// given
		long owner = 2L;
		long member = 3L;
		long outsider = 4L;
		long noProfile = 5L;
		long room = rows.room("방", owner, at(0), null);
		rows.member(room, owner, at(0), at(0));
		rows.member(room, member, at(0), at(0));
		rows.profile(owner, "CODE0002", "방장");
		rows.profile(member, "CODE0003", "멤버");
		rows.profile(outsider, "CODE0004", null);
		rows.friendship(ME, owner, "PENDING", at(0));
		rows.friendship(member, ME, "ACCEPTED", at(0));
		rows.block(ME, outsider, false);
		rows.suspension(owner, OffsetDateTime.now().plusDays(1));
		rows.suspension(member, OffsetDateTime.now().minusDays(1));
		rows.suspension(outsider, null);

		// when
		Map<Long, RoomPersonView> people = byId(members.findPeople(ME, room, List.of(owner, member, outsider, noProfile)));

		// then
		assertThat(people).containsOnlyKeys(owner, member, outsider);
		assertThat(people.get(owner)).satisfies(p -> {
			assertThat(p.nickname()).isEqualTo("방장");
			assertThat(p.owner()).isTrue();
			assertThat(p.member()).isTrue();
			assertThat(p.sentStatus()).isEqualTo(FriendshipStatus.PENDING);
			assertThat(p.receivedStatus()).isNull();
			assertThat(p.blocked()).isFalse();
			assertThat(p.suspended()).isTrue();
		});
		assertThat(people.get(member)).satisfies(p -> {
			assertThat(p.owner()).isFalse();
			assertThat(p.member()).isTrue();
			assertThat(p.sentStatus()).isNull();
			assertThat(p.receivedStatus()).isEqualTo(FriendshipStatus.ACCEPTED);
			// 기한이 지난 정지는 정지 아님
			assertThat(p.suspended()).isFalse();
		});
		assertThat(people.get(outsider)).satisfies(p -> {
			assertThat(p.member()).isFalse();
			assertThat(p.blocked()).isTrue();
			// 기한이 없으면 영구 정지
			assertThat(p.suspended()).isTrue();
		});
	}

	@Test
	@DisplayName("방이 정해지지 않으면 방장·멤버는 모두 false다")
	void 방이_없으면_방장과_멤버는_false다() {
		// given
		long room = rows.room("방", OTHER, at(0), null);
		rows.member(room, OTHER, at(0), at(0));
		rows.profile(OTHER, "CODE0002", "방장");

		// when & then
		assertThat(members.findPeople(ME, null, List.of(OTHER))).singleElement().satisfies(p -> {
			assertThat(p.owner()).isFalse();
			assertThat(p.member()).isFalse();
		});
	}

	private static Map<Long, RoomPersonView> byId(List<RoomPersonView> people) {
		return people.stream().collect(Collectors.toMap(RoomPersonView::userId, Function.identity()));
	}

}
