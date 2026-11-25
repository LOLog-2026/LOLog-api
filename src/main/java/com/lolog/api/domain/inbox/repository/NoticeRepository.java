package com.lolog.api.domain.inbox.repository;

import com.lolog.api.domain.inbox.entity.Notice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 공지 DB 조회.
public interface NoticeRepository extends JpaRepository<Notice, Long> {

	/**
	 * 이 사용자가 읽지 않은 공지가 있는지 본다.
	 *
	 * @param userId 공지를 볼 회원 ID
	 * @return 읽지 않은 공지가 있으면 true
	 */
	@Query("select count(n) > 0 from Notice n where not exists (select r from NoticeRead r where r.noticeId = n.id and r.userId = :userId)")
	boolean existsUnread(@Param("userId") Long userId);

	/**
	 * 공지 하나를 지운다. 지운 줄 수를 돌려준다(0이면 없음). 한 문장이라 동시에 지워도 오류가 나지 않는다.
	 *
	 * @param id 지울 공지 ID
	 * @return 지운 줄 수(0이면 없음)
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Notice n where n.id = :id")
	int deleteNotice(@Param("id") Long id);
}
