package com.lolog.api.comic;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ComicRepository extends JpaRepository<Comic, Long> {

	List<Comic> findByCardIdOrderByIdDesc(Long cardId);

	boolean existsByCardIdAndStatusIn(Long cardId, Collection<ComicStatus> statuses);

	// 카드 목록의 comicCount. 남아 있는 만화 중 실패 아닌 것(지운 만화는 행이 없으므로 빠진다).
	@Query("select m.cardId, count(m) from Comic m where m.cardId in :cardIds and m.status <> com.lolog.api.comic.ComicStatus.FAILED group by m.cardId")
	List<Object[]> countNotFailedByCardIds(@Param("cardIds") Collection<Long> cardIds);

}
