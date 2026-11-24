package com.lolog.api.domain.friend.repository;

import com.lolog.api.domain.friend.entity.Friendship;
import com.lolog.api.domain.friend.entity.FriendshipStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 친구 요청·친구 DB 조회.
public interface FriendshipRepository extends JpaRepository<Friendship, Long> {

	// 두 사람 사이의 친구 요청이나 친구 줄을 찾는다.
	@Query("""
			select f from Friendship f
			where (f.requesterId = :a and f.addresseeId = :b) or (f.requesterId = :b and f.addresseeId = :a)
			""")
	Optional<Friendship> findBetween(@Param("a") Long a, @Param("b") Long b);

	// 두 사람이 친구(ACCEPTED)인지 본다.
	default boolean areFriends(long a, long b) {
		return findBetween(a, b).map(Friendship::isAccepted).orElse(false);
	}

	// 내가 받은 요청 하나를 상태와 함께 찾는다.
	Optional<Friendship> findByIdAndAddresseeIdAndStatus(Long id, Long addresseeId, FriendshipStatus status);

	// 내가 받은 요청 목록(최신순).
	List<Friendship> findAllByAddresseeIdAndStatusOrderByCreatedAtDescIdDesc(Long addresseeId, FriendshipStatus status);

	// 내가 보낸 요청 목록(최신순).
	List<Friendship> findAllByRequesterIdAndStatusOrderByCreatedAtDescIdDesc(Long requesterId, FriendshipStatus status);

	// 받은 요청이 있는지 본다.
	boolean existsByAddresseeIdAndStatus(Long addresseeId, FriendshipStatus status);

	// 내 친구 줄 목록. 최근에 친구가 된 사람이 위.
	@Query("""
			select f from Friendship f
			where f.status = com.lolog.api.domain.friend.entity.FriendshipStatus.ACCEPTED
			  and (f.requesterId = :me or f.addresseeId = :me)
			order by f.updatedAt desc, f.id desc
			""")
	List<Friendship> findFriendsOf(@Param("me") Long me);

	// 친구 수.
	@Query("""
			select count(f) from Friendship f
			where f.status = com.lolog.api.domain.friend.entity.FriendshipStatus.ACCEPTED
			  and (f.requesterId = :userId or f.addresseeId = :userId)
			""")
	long countFriendsOf(@Param("userId") Long userId);

	// userIds 중 me와 친구(ACCEPTED)인 회원 ID.
	@Query("""
			select case when f.requesterId = :me then f.addresseeId else f.requesterId end from Friendship f
			where f.status = com.lolog.api.domain.friend.entity.FriendshipStatus.ACCEPTED
			  and ((f.requesterId = :me and f.addresseeId in :userIds) or (f.addresseeId = :me and f.requesterId in :userIds))
			""")
	List<Long> findFriendIdsAmong(@Param("me") Long me, @Param("userIds") Collection<Long> userIds);

	// me와 userIds 사이의 친구 요청이나 친구 줄.
	@Query("""
			select f from Friendship f
			where (f.requesterId = :me and f.addresseeId in :userIds) or (f.addresseeId = :me and f.requesterId in :userIds)
			""")
	List<Friendship> findAllBetween(@Param("me") Long me, @Param("userIds") Collection<Long> userIds);

	// me가 보내거나 받은 대기 중인 요청을 지운다. 지운 줄 수(0이면 없는 요청).
	// 잠그지 않고 지우므로 동시에 지워도 한쪽만 1이 되게 조건을 걸어 한 문장으로 지운다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			delete from Friendship f
			where f.id = :id and f.status = com.lolog.api.domain.friend.entity.FriendshipStatus.PENDING
			  and (f.requesterId = :me or f.addresseeId = :me)
			""")
	int deletePendingOf(@Param("id") Long id, @Param("me") Long me);

	// 두 사람 사이의 친구 줄(ACCEPTED)을 지운다. 없으면 그대로다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			delete from Friendship f
			where f.status = com.lolog.api.domain.friend.entity.FriendshipStatus.ACCEPTED
			  and ((f.requesterId = :a and f.addresseeId = :b) or (f.requesterId = :b and f.addresseeId = :a))
			""")
	int deleteAcceptedBetween(@Param("a") Long a, @Param("b") Long b);

	// 두 사람 사이의 친구 요청이나 친구 줄을 지운다(차단). 없으면 그대로다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			delete from Friendship f
			where (f.requesterId = :a and f.addresseeId = :b) or (f.requesterId = :b and f.addresseeId = :a)
			""")
	int deleteBetween(@Param("a") Long a, @Param("b") Long b);

	// 지금 me와 같은 방에 있고 닉네임을 정한 회원이면 그 회원 ID(방 멤버에게 친구 요청).
	// 프로필과 방 멤버를 한 문장으로 봐야 해서 이 도메인의 native query로 둔다.
	@Query(value = """
			select p.user_id from user_profiles p where p.user_id = :userId and p.nickname is not null and exists (
			  select 1 from room_members a join room_members b on b.room_id = a.room_id
			  where a.user_id = :me and b.user_id = p.user_id)
			""", nativeQuery = true)
	Optional<Long> findRoommateWithNickname(@Param("userId") Long userId, @Param("me") Long me);

	// 회원이 어느 쪽이든 친구 관계·요청을 모두 지운다(탈퇴).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Friendship f where f.requesterId = :userId or f.addresseeId = :userId")
	int deleteAllOf(@Param("userId") Long userId);

}
