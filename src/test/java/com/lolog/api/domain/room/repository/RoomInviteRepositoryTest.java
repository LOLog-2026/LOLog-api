package com.lolog.api.domain.room.repository;

import static com.lolog.api.support.RepositoryRows.at;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("RoomInviteRepository")
class RoomInviteRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OWNER = 2L;

	@Autowired
	private RoomInviteRepository invites;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
	}

	@Test
	@DisplayName("초대 줄을 넣으면 새 ID를 돌려준다")
	void 초대_줄을_넣고_ID를_돌려준다() {
		// given
		long room = rows.room("방", OWNER, at(0), null);

		// when
		Long id = invites.insert(room, ME, OWNER);

		// then
		assertThat(id).isNotNull();
		assertThat(invites.findRoomIdByIdAndUserId(id, ME)).contains(room);
	}

	@Test
	@DisplayName("남이 받은 초대의 방 ID는 찾지 않는다")
	void 남이_받은_초대는_찾지_않는다() {
		// given
		long room = rows.room("방", OWNER, at(0), null);
		long id = rows.invite(room, 3L, OWNER, at(0));

		// when & then
		assertThat(invites.findRoomIdByIdAndUserId(id, ME)).isEmpty();
	}

	@Test
	@DisplayName("받은 초대는 최신순이고, 같은 시각이면 ID가 큰 것이 앞이다")
	void 받은_초대는_최신순이다() {
		// given
		long a = rows.room("가", OWNER, at(0), null);
		long b = rows.room("나", OWNER, at(0), null);
		long c = rows.room("다", OWNER, at(0), null);
		long old = rows.invite(a, ME, OWNER, at(1));
		long sameTimeFirst = rows.invite(b, ME, OWNER, at(5));
		long sameTimeSecond = rows.invite(c, ME, OWNER, at(5));
		rows.invite(a, 3L, OWNER, at(9));

		// when & then
		assertThat(invites.findReceived(ME)).extracting(ReceivedInviteView::id)
			.containsExactly(sameTimeSecond, sameTimeFirst, old);
	}

	@Test
	@DisplayName("받은 초대의 방에 내가 차단한 멤버가 있을 때만 hasBlocked가 true다")
	void 차단한_멤버가_있는_방만_표시한다() {
		// given
		long blockedRoom = rows.room("차단있음", OWNER, at(0), null);
		long cleanRoom = rows.room("차단없음", OWNER, at(0), null);
		rows.member(blockedRoom, 5L, at(0), at(0));
		rows.member(cleanRoom, OWNER, at(0), at(0));
		// 방에 없는 사람을 차단한 것은 상관없다
		rows.block(ME, 5L, false);
		rows.block(ME, 6L, false);
		// 남이 방장을 차단한 것도 상관없다
		rows.block(7L, OWNER, false);
		long blockedInvite = rows.invite(blockedRoom, ME, OWNER, at(1));
		long cleanInvite = rows.invite(cleanRoom, ME, OWNER, at(2));

		// when
		List<ReceivedInviteView> received = invites.findReceived(ME);

		// then
		assertThat(received).extracting(ReceivedInviteView::id, ReceivedInviteView::roomName,
				ReceivedInviteView::hasBlocked)
			.containsExactly(tuple(cleanInvite, "차단없음", false),
					tuple(blockedInvite, "차단있음", true));
	}

	@Test
	@DisplayName("보낸 초대는 주어진 방 중 내가 방장인 방의 것만 오래된순으로 읽는다")
	void 보낸_초대는_내가_방장인_방만_오래된순이다() {
		// given
		long mine = rows.room("내방", OWNER, at(0), null);
		long notMine = rows.room("남의방", 9L, at(0), null);
		long notAsked = rows.room("안물은방", OWNER, at(0), null);
		long later = rows.invite(mine, 3L, OWNER, at(5));
		long earlierSecond = rows.invite(mine, 5L, OWNER, at(1));
		long earlierFirst = rows.invite(mine, 4L, OWNER, at(1));
		rows.invite(notMine, 3L, 9L, at(0));
		rows.invite(notAsked, 3L, OWNER, at(0));

		// when & then
		assertThat(invites.findSent(List.of(mine, notMine), OWNER)).extracting(SentInviteView::id)
			.containsExactly(earlierSecond, earlierFirst, later);
	}

	@Test
	@DisplayName("방을 맞춰 지울 때 다른 방의 초대면 지우지 않는다")
	void 다른_방의_초대는_지우지_않는다() {
		// given
		long room = rows.room("방", OWNER, at(0), null);
		long other = rows.room("다른방", OWNER, at(0), null);
		long id = rows.invite(room, ME, OWNER, at(0));

		// when & then
		assertThat(invites.deleteByIdAndRoomId(id, other)).isZero();
		assertThat(invites.deleteByIdAndRoomId(id, room)).isEqualTo(1);
	}

	@Test
	@DisplayName("받은 사람을 맞춰 지울 때 남이 받은 초대면 지우지 않는다")
	void 남이_받은_초대는_지우지_않는다() {
		// given
		long room = rows.room("방", OWNER, at(0), null);
		long id = rows.invite(room, ME, OWNER, at(0));

		// when & then
		assertThat(invites.deleteByIdAndUserId(id, 3L)).isZero();
		assertThat(invites.deleteByIdAndUserId(id, ME)).isEqualTo(1);
		assertThat(invites.deleteInvite(id)).isZero();
	}

	@Test
	@DisplayName("탈퇴하면 그 회원이 받은 초대만 지운다")
	void 탈퇴하면_받은_초대만_지운다() {
		// given
		long room = rows.room("방", OWNER, at(0), null);
		rows.invite(room, ME, OWNER, at(0));
		rows.invite(room, 3L, OWNER, at(0));

		// when & then
		assertThat(invites.deleteAllByUserId(ME)).isEqualTo(1);
		assertThat(invites.countByRoomId(room)).isEqualTo(1);
	}

}
