package com.lolog.api.domain.friend.repository;

import static com.lolog.api.support.RepositoryRows.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.friend.entity.Friendship;
import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("FriendshipRepository")
class FriendshipRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private FriendshipRepository friendships;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
	}

	@Test
	@DisplayName("두 사람 사이의 줄은 누가 보냈든 찾는다")
	void 두_사람_사이의_줄은_방향과_상관없이_찾는다() {
		// given
		long id = rows.friendship(OTHER, ME, "PENDING", at(0));

		// when & then
		assertThat(friendships.findBetween(ME, OTHER)).map(Friendship::getId).contains(id);
		assertThat(friendships.findBetween(OTHER, ME)).map(Friendship::getId).contains(id);
		assertThat(friendships.findBetween(ME, 3L)).isEmpty();
	}

	@Test
	@DisplayName("ACCEPTED일 때만 친구다")
	void ACCEPTED일_때만_친구다() {
		// given
		rows.friendship(ME, OTHER, "PENDING", at(0));
		rows.friendship(3L, ME, "ACCEPTED", at(0));

		// when & then
		assertThat(friendships.areFriends(ME, OTHER)).isFalse();
		assertThat(friendships.areFriends(ME, 3L)).isTrue();
		assertThat(friendships.areFriends(ME, 4L)).isFalse();
	}

	@Test
	@DisplayName("친구 목록은 ACCEPTED만, 최근에 친구가 된 사람이 위이고 같은 시각이면 ID가 큰 것이 위다")
	void 친구_목록은_친구가_된_시각_최신순이다() {
		// given
		long old = rows.friendship(ME, 2L, "ACCEPTED", at(1));
		long sameFirst = rows.friendship(3L, ME, "ACCEPTED", at(5));
		long sameSecond = rows.friendship(ME, 4L, "ACCEPTED", at(5));
		rows.friendship(ME, 5L, "PENDING", at(9));
		rows.friendship(6L, 7L, "ACCEPTED", at(9));

		// when
		List<Friendship> friends = friendships.findFriendsOf(ME);

		// then
		assertThat(friends).extracting(Friendship::getId).containsExactly(sameSecond, sameFirst, old);
		assertThat(friendships.countFriendsOf(ME)).isEqualTo(3);
	}

	@Test
	@DisplayName("주어진 회원 중 나와 친구인 회원의 ID(상대 쪽)만 돌려준다")
	void 주어진_회원_중_친구인_상대_ID만_돌려준다() {
		// given
		rows.friendship(ME, 2L, "ACCEPTED", at(0));
		rows.friendship(3L, ME, "ACCEPTED", at(0));
		rows.friendship(ME, 4L, "PENDING", at(0));
		rows.friendship(ME, 5L, "ACCEPTED", at(0));

		// when
		List<Long> ids = friendships.findFriendIdsAmong(ME, List.of(2L, 3L, 4L, 9L));

		// then
		assertThat(ids).containsExactlyInAnyOrder(2L, 3L);
	}

	@Test
	@DisplayName("나와 주어진 회원 사이의 요청·친구 줄을 방향과 상관없이 모두 읽는다")
	void 나와_주어진_회원_사이의_줄을_모두_읽는다() {
		// given
		long sent = rows.friendship(ME, 2L, "PENDING", at(0));
		long received = rows.friendship(3L, ME, "ACCEPTED", at(0));
		rows.friendship(ME, 4L, "PENDING", at(0));
		rows.friendship(2L, 3L, "PENDING", at(0));

		// when
		List<Friendship> found = friendships.findAllBetween(ME, List.of(2L, 3L));

		// then
		assertThat(found).extracting(Friendship::getId).containsExactlyInAnyOrder(sent, received);
	}

	@Test
	@DisplayName("대기 중인 요청은 보낸 사람이나 받은 사람만 지울 수 있고, 친구 줄은 지우지 않는다")
	void 대기_중인_요청만_당사자가_지운다() {
		// given
		long sent = rows.friendship(ME, 2L, "PENDING", at(0));
		long received = rows.friendship(3L, ME, "PENDING", at(0));
		long accepted = rows.friendship(ME, 4L, "ACCEPTED", at(0));

		// when & then
		assertThat(friendships.deletePendingOf(sent, 9L)).isZero();
		assertThat(friendships.deletePendingOf(accepted, ME)).isZero();
		assertThat(friendships.deletePendingOf(sent, ME)).isEqualTo(1);
		assertThat(friendships.deletePendingOf(received, ME)).isEqualTo(1);
		assertThat(friendships.deletePendingOf(received, ME)).isZero();
	}

	@Test
	@DisplayName("친구 끊기는 ACCEPTED 줄만 방향과 상관없이 지운다")
	void 친구_끊기는_ACCEPTED만_지운다() {
		// given
		rows.friendship(ME, 2L, "PENDING", at(0));
		rows.friendship(3L, ME, "ACCEPTED", at(0));

		// when & then
		assertThat(friendships.deleteAcceptedBetween(ME, 2L)).isZero();
		assertThat(friendships.deleteAcceptedBetween(ME, 3L)).isEqualTo(1);
	}

	@Test
	@DisplayName("차단하면 두 사람 사이의 요청이나 친구 줄을 상태와 상관없이 지운다")
	void 차단하면_상태와_상관없이_지운다() {
		// given
		rows.friendship(2L, ME, "PENDING", at(0));
		rows.friendship(ME, 3L, "ACCEPTED", at(0));

		// when & then
		assertThat(friendships.deleteBetween(ME, 2L)).isEqualTo(1);
		assertThat(friendships.deleteBetween(3L, ME)).isEqualTo(1);
		assertThat(friendships.deleteBetween(ME, 4L)).isZero();
	}

	@Test
	@DisplayName("같은 방에 있고 닉네임을 정한 회원일 때만 그 회원 ID를 돌려준다")
	void 같은_방의_닉네임_있는_회원만_찾는다() {
		// given
		long room = rows.room("방", ME, at(0), null);
		long otherRoom = rows.room("다른방", 9L, at(0), null);
		rows.member(room, ME, at(0), at(0));
		rows.member(room, 2L, at(0), at(0));
		rows.member(room, 3L, at(0), at(0));
		rows.member(otherRoom, 4L, at(0), at(0));
		rows.profile(2L, "CODE0002", "닉네임");
		rows.profile(3L, "CODE0003", null);
		rows.profile(4L, "CODE0004", "남의방");

		// when & then
		assertThat(friendships.findRoommateWithNickname(2L, ME)).contains(2L);
		assertThat(friendships.findRoommateWithNickname(3L, ME)).isEmpty();
		assertThat(friendships.findRoommateWithNickname(4L, ME)).isEmpty();
	}

	@Test
	@DisplayName("탈퇴하면 그 회원이 어느 쪽이든 줄을 모두 지운다")
	void 탈퇴하면_어느_쪽이든_모두_지운다() {
		// given
		rows.friendship(ME, 2L, "PENDING", at(0));
		rows.friendship(3L, ME, "ACCEPTED", at(0));
		long others = rows.friendship(2L, 3L, "ACCEPTED", at(0));

		// when
		int deleted = friendships.deleteAllOf(ME);

		// then
		assertThat(deleted).isEqualTo(2);
		assertThat(friendships.findAll()).extracting(Friendship::getId).containsExactly(others);
	}

}
