package com.lolog.api.card;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
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

	// 오래된순(id 오름차순). after가 null이면 처음부터.
	@Query("select c from Card c where c.userId = :userId and (:after is null or c.id > :after) order by c.id asc")
	List<Card> findPageOldest(@Param("userId") Long userId, @Param("after") Long after, Limit limit);

	// 만들기 횟수를 3보다 작을 때만 늘린다. 바뀐 줄이 0이면 이미 3번 만든 카드다. 조건을 붙인 UPDATE 한 줄이라 동시에 눌러도 3을 넘지 않는다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "update cards set comic_count = comic_count + 1 where id = :id and comic_count < 3", nativeQuery = true)
	int incrementComicCount(@Param("id") Long id);

	// 만화가 실패하면 횟수를 돌려준다(실패한 만화는 세지 않음).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "update cards set comic_count = comic_count - 1 where id = :id and comic_count > 0", nativeQuery = true)
	int decrementComicCount(@Param("id") Long id);

	@Query("select l.card.id, count(l) from CardLine l where l.card.id in :cardIds group by l.card.id")
	List<Object[]> countLinesByCardIds(@Param("cardIds") Collection<Long> cardIds);

}
