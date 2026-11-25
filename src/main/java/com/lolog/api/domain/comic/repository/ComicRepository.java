package com.lolog.api.domain.comic.repository;

import com.lolog.api.domain.comic.entity.Comic;
import com.lolog.api.domain.comic.entity.ComicStatus;
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

// 만화를 DB에서 읽고 쓴다.
public interface ComicRepository extends JpaRepository<Comic, Long> {

	/**
	 * 카드로 만든 만화를 최신순으로 찾는다.
	 *
	 * @param cardId 만화를 만든 카드 ID
	 * @return 그 카드의 만화(최신순)
	 */
	List<Comic> findByCardIdOrderByIdDesc(Long cardId);

	/**
	 * 워커의 콘티·이미지·fail이 겹쳐도 한 번에 하나씩 상태를 바꾸게 만화 행을 잠근다.
	 *
	 * @param id 잠글 만화 ID
	 * @return 잠근 만화(없으면 빈 값)
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Comic m where m.id = :id")
	Optional<Comic> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 내 만화 하나를 찾는다.
	 *
	 * @param id 만화 ID
	 * @param userId 만화 주인이어야 할 회원 ID
	 * @return 그 회원의 만화(없거나 남의 만화면 빈 값)
	 */
	Optional<Comic> findByIdAndUserId(Long id, Long userId);

	/**
	 * 내 만화 하나를 잠그고 찾는다.
	 *
	 * @param id 만화 ID
	 * @param userId 만화 주인이어야 할 회원 ID
	 * @return 잠근 만화(없거나 남의 만화면 빈 값)
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select m from Comic m where m.id = :id and m.userId = :userId")
	Optional<Comic> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	/**
	 * 최신순(id 내림차순). before가 null이면 처음부터.
	 *
	 * @param userId 만화 주인 회원 ID
	 * @param before 이 ID보다 작은 만화만 읽는다(null이면 처음부터)
	 * @param limit 읽을 최대 개수
	 * @return 만화 목록(id 내림차순)
	 */
	@Query("select m from Comic m where m.userId = :userId and (:before is null or m.id < :before) order by m.id desc")
	List<Comic> findPage(@Param("userId") Long userId, @Param("before") Long before, Limit limit);

	/**
	 * 오래된순(id 오름차순). after가 null이면 처음부터.
	 *
	 * @param userId 만화 주인 회원 ID
	 * @param after 이 ID보다 큰 만화만 읽는다(null이면 처음부터)
	 * @param limit 읽을 최대 개수
	 * @return 만화 목록(id 오름차순)
	 */
	@Query("select m from Comic m where m.userId = :userId and (:after is null or m.id > :after) order by m.id asc")
	List<Comic> findPageOldest(@Param("userId") Long userId, @Param("after") Long after, Limit limit);

	/**
	 * 카드에 이 상태들의 만화가 있는지 본다.
	 *
	 * @param cardId 살펴볼 카드 ID
	 * @param statuses 찾을 만화 상태들
	 * @return 있으면 true
	 */
	boolean existsByCardIdAndStatusIn(Long cardId, Collection<ComicStatus> statuses);

	/**
	 * 카드 목록의 comicCount. 남아 있는 만화 중 실패 아닌 것(지운 만화는 행이 없으므로 빠진다).
	 *
	 * @param cardIds 셀 카드 ID들
	 * @return [카드 ID, 만화 수] 배열 목록(만화가 없는 카드는 빠짐)
	 */
	@Query("select m.cardId, count(m) from Comic m where m.cardId in :cardIds and m.status <> com.lolog.api.domain.comic.entity.ComicStatus.FAILED group by m.cardId")
	List<Object[]> countNotFailedByCardIds(@Param("cardIds") Collection<Long> cardIds);

	/**
	 * 회원의 만화를 모두 지운다(탈퇴).
	 *
	 * @param userId 탈퇴하는 회원 ID
	 * @return 지운 행 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Comic m where m.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
