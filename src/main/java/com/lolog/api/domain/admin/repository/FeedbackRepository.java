package com.lolog.api.domain.admin.repository;

import com.lolog.api.domain.admin.entity.Feedback;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 오류 신고·의견 DB 조회.
public interface FeedbackRepository extends JpaRepository<Feedback, Long> {

	// 트랜잭션이 끝날 때까지 회원 한 명의 보내기를 잠근다(PostgreSQL advisory lock). JPA로 쓸 수 없어 native query로 둔다.
	@Query(value = "select 1 from pg_advisory_xact_lock(:key1, :key2)", nativeQuery = true)
	Integer lockSender(@Param("key1") int key1, @Param("key2") int key2);

	// since 뒤에 이 회원이 보낸 수.
	@Query("select count(f) from Feedback f where f.userId = :userId and f.createdAt > :since")
	long countSentSince(@Param("userId") Long userId, @Param("since") OffsetDateTime since);

	// 최신순(id 내림차순). before가 null이면 처음부터.
	@Query("select f from Feedback f where (:before is null or f.id < :before) order by f.id desc")
	List<Feedback> findPage(@Param("before") Long before, Limit limit);

	// 처리 상태가 status인 것만 최신순으로.
	@Query("select f from Feedback f where f.status = :status and (:before is null or f.id < :before) order by f.id desc")
	List<Feedback> findPageByStatus(@Param("status") String status, @Param("before") Long before, Limit limit);

	// 보낸 사람을 비운다(탈퇴). user_id는 엔티티로 바꾸지 않는 칸이라 일괄 UPDATE로 바꾼다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Feedback f set f.userId = null, f.updatedAt = current_timestamp where f.userId = :userId")
	int clearUser(@Param("userId") Long userId);

}
