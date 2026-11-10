package com.lolog.api.comic;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComicRepository extends JpaRepository<Comic, Long> {

	List<Comic> findByCardIdOrderByIdDesc(Long cardId);

	// 워커의 콘티·이미지·fail이 겹쳐도 한 번에 하나씩 상태를 바꾸게 만화 행을 잠근다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Comic m where m.id = :id")
	Optional<Comic> findByIdForUpdate(@Param("id") Long id);

	Optional<Comic> findByIdAndUserId(Long id, Long userId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Comic m where m.id = :id and m.userId = :userId")
	Optional<Comic> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	// 최신순(id 내림차순). before가 null이면 처음부터.
	@Query("select m from Comic m where m.userId = :userId and (:before is null or m.id < :before) order by m.id desc")
	List<Comic> findPage(@Param("userId") Long userId, @Param("before") Long before, Limit limit);

	// 오래된순(id 오름차순). after가 null이면 처음부터.
	@Query("select m from Comic m where m.userId = :userId and (:after is null or m.id > :after) order by m.id asc")
	List<Comic> findPageOldest(@Param("userId") Long userId, @Param("after") Long after, Limit limit);

	boolean existsByCardIdAndStatusIn(Long cardId, Collection<ComicStatus> statuses);

	// 카드 목록의 comicCount. 남아 있는 만화 중 실패 아닌 것(지운 만화는 행이 없으므로 빠진다).
	@Query("select m.cardId, count(m) from Comic m where m.cardId in :cardIds and m.status <> com.lolog.api.comic.ComicStatus.FAILED group by m.cardId")
	List<Object[]> countNotFailedByCardIds(@Param("cardIds") Collection<Long> cardIds);

}
