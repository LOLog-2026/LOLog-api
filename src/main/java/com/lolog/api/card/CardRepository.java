package com.lolog.api.card;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CardRepository extends JpaRepository<Card, Long> {

	List<Card> findByRecordingIdOrderById(Long recordingId);

	Optional<Card> findByIdAndUserId(Long id, Long userId);

	// 고치기·지우기 중에 같은 카드로 만화를 요청하지 못하게 카드 행을 잠근다(#5의 만화 요청도 같은 행을 잠근다).
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Card c where c.id = :id and c.userId = :userId")
	Optional<Card> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	// 최신순(id 내림차순). before가 null이면 처음부터.
	@Query("select c from Card c where c.userId = :userId and (:before is null or c.id < :before) order by c.id desc")
	List<Card> findPage(@Param("userId") Long userId, @Param("before") Long before, Limit limit);

	@Query("select l.card.id, count(l) from CardLine l where l.card.id in :cardIds group by l.card.id")
	List<Object[]> countLinesByCardIds(@Param("cardIds") Collection<Long> cardIds);

}
