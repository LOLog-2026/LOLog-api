package com.lolog.api.domain.inbox.repository;

import com.lolog.api.domain.inbox.entity.Notice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 공지 DB 조회.
public interface NoticeRepository extends JpaRepository<Notice, Long> {

	// 이 사용자가 읽지 않은 공지가 있는지 본다.
	@Query("select count(n) > 0 from Notice n where not exists (select r from NoticeRead r where r.noticeId = n.id and r.userId = :userId)")
	boolean existsUnread(@Param("userId") Long userId);

}
