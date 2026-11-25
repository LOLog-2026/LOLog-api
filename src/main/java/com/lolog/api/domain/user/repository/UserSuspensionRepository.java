package com.lolog.api.domain.user.repository;

import com.lolog.api.domain.user.entity.UserSuspension;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 정지된 회원 DB 조회.
public interface UserSuspensionRepository extends JpaRepository<UserSuspension, Long> {

	/**
	 * 없으면 넣고, 있으면 기한을 바꾼다. 한 문장이라 동시에 와도 줄이 둘 생기지 않는다.
	 *
	 * @param userId 정지할 회원 ID
	 * @param until 정지 기한. null이면 영구 정지
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			insert into user_suspensions (user_id, suspended_until) values (:userId, :until)
			on conflict (user_id) do update set suspended_until = excluded.suspended_until
			""", nativeQuery = true)
	void upsert(@Param("userId") Long userId, @Param("until") OffsetDateTime until);

	/**
	 * userIds 중 지금 정지된 회원 ID. 기한이 지난 정지는 정지 아님으로 본다.
	 *
	 * @param userIds 확인할 회원 ID들
	 * @return 그중 지금 정지된 회원 ID
	 */
	@Query("""
			select s.userId from UserSuspension s
			where s.userId in :userIds and (s.suspendedUntil is null or s.suspendedUntil > current_timestamp)
			""")
	List<Long> findSuspendedIdsAmong(@Param("userIds") Collection<Long> userIds);

	/**
	 * 회원의 정지 표시를 지운다(탈퇴).
	 *
	 * @param userId 탈퇴하는 회원 ID
	 * @return 지운 줄 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from UserSuspension s where s.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
