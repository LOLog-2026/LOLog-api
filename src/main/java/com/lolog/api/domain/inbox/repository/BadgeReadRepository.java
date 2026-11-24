package com.lolog.api.domain.inbox.repository;

import com.lolog.api.domain.inbox.entity.BadgeRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 새 소식 확인 시각 DB 조회.
public interface BadgeReadRepository extends JpaRepository<BadgeRead, BadgeRead.Key> {

	// 지금 봤다고 남긴다. seen_at은 이 문장을 실행한 시각(clock_timestamp)이다(알림 created_at과 같은 시계).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			insert into badge_reads (user_id, kind, seen_at) values (:userId, :kind, clock_timestamp())
			on conflict (user_id, kind) do update set seen_at = excluded.seen_at
			""", nativeQuery = true)
	void markSeen(@Param("userId") Long userId, @Param("kind") String kind);

	// 사용자의 확인 기록을 모두 지운다(탈퇴).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from BadgeRead b where b.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
