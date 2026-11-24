package com.lolog.api.domain.inbox.repository;

import com.lolog.api.domain.inbox.entity.NoticeRead;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 공지 읽음 기록 DB 조회.
public interface NoticeReadRepository extends JpaRepository<NoticeRead, NoticeRead.Key> {

	// 공지 하나를 읽음으로 남긴다. 이미 읽었으면 그대로 둔다(같은 공지를 동시에 눌러도 한 줄).
	// 새 줄만 넣어 이미 읽은 엔티티가 낡지 않으므로 영속성 컨텍스트를 비우지 않는다(뒤에 바꾼 엔티티가 저장되게).
	@Modifying(flushAutomatically = true)
	@Query(value = "insert into notice_reads (notice_id, user_id) values (:noticeId, :userId) on conflict do nothing",
			nativeQuery = true)
	void markRead(@Param("noticeId") Long noticeId, @Param("userId") Long userId);

	// 모든 공지를 읽음으로 남긴다.
	// 새 줄만 넣어 이미 읽은 엔티티가 낡지 않으므로 영속성 컨텍스트를 비우지 않는다(뒤에 바꾼 엔티티가 저장되게).
	@Modifying(flushAutomatically = true)
	@Query(value = "insert into notice_reads (notice_id, user_id) select id, :userId from notices on conflict do nothing",
			nativeQuery = true)
	void markAllRead(@Param("userId") Long userId);

	// 사용자의 읽음 기록을 모두 지운다(탈퇴).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from NoticeRead r where r.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
