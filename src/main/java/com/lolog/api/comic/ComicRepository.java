package com.lolog.api.comic;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

	boolean existsByCardIdAndStatusIn(Long cardId, Collection<ComicStatus> statuses);

	// 카드 목록의 comicCount. 남아 있는 만화 중 실패 아닌 것(지운 만화는 행이 없으므로 빠진다).
	@Query("select m.cardId, count(m) from Comic m where m.cardId in :cardIds and m.status <> com.lolog.api.comic.ComicStatus.FAILED group by m.cardId")
	List<Object[]> countNotFailedByCardIds(@Param("cardIds") Collection<Long> cardIds);

}
