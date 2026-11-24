package com.lolog.api.domain.friend.repository;

import com.lolog.api.domain.friend.entity.UserBlock;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 차단 DB 조회.
public interface UserBlockRepository extends JpaRepository<UserBlock, UserBlock.Key> {

	// 내가 차단한 사람 목록(최신순).
	List<UserBlock> findAllByBlockerIdOrderByCreatedAtDescBlockedIdDesc(Long blockerId);

	// userIds 중 내가 차단한 사람 ID.
	@Query("select b.blockedId from UserBlock b where b.blockerId = :blocker and b.blockedId in :userIds")
	List<Long> findBlockedIdsAmong(@Param("blocker") Long blocker, @Param("userIds") Collection<Long> userIds);

	// 내가 차단하고 방 글을 숨기기로 한 사람 ID.
	@Query("select b.blockedId from UserBlock b where b.blockerId = :blocker and b.hideInRooms = true")
	List<Long> findHiddenInRoomsIds(@Param("blocker") Long blocker);

	// 차단을 푼다. 차단한 적 없으면 그대로다. 같은 요청이 겹쳐도 오류 없이 지우도록 한 문장으로 지운다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from UserBlock b where b.blockerId = :blocker and b.blockedId = :blocked")
	int deleteBlock(@Param("blocker") Long blocker, @Param("blocked") Long blocked);

	// 회원이 어느 쪽이든 차단을 모두 지운다(탈퇴).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from UserBlock b where b.blockerId = :userId or b.blockedId = :userId")
	int deleteAllOf(@Param("userId") Long userId);

}
