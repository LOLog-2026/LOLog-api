package com.lolog.api.domain.video.repository;

import com.lolog.api.domain.video.entity.Video;
import com.lolog.api.domain.video.entity.VideoStatus;
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

// 영상 DB 조회.
public interface VideoRepository extends JpaRepository<Video, Long> {

	/**
	 * 워커의 단계 보내기·fail이 겹쳐도 한 번에 하나씩 상태를 바꾸게 영상 행을 잠근다.
	 *
	 * @param id 영상 ID
	 * @return 잠근 영상. 없으면 빈 값
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select v from Video v where v.id = :id")
	Optional<Video> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 내 영상 하나를 찾는다.
	 *
	 * @param id 영상 ID
	 * @param userId 영상 주인이어야 하는 회원 ID
	 * @return 영상. 없거나 주인이 다르면 빈 값
	 */
	Optional<Video> findByIdAndUserId(Long id, Long userId);

	/**
	 * 최신순(id 내림차순). before가 null이면 처음부터.
	 *
	 * @param userId 영상 주인 회원 ID
	 * @param before 이 ID보다 작은 영상만(cursor). null이면 처음부터
	 * @param limit 가져올 최대 개수
	 * @return 영상 목록(id 내림차순)
	 */
	@Query("select v from Video v where v.userId = :userId and (:before is null or v.id < :before) order by v.id desc")
	List<Video> findPage(@Param("userId") Long userId, @Param("before") Long before, Limit limit);

	/**
	 * 오래된순(id 오름차순). after가 null이면 처음부터.
	 *
	 * @param userId 영상 주인 회원 ID
	 * @param after 이 ID보다 큰 영상만(cursor). null이면 처음부터
	 * @param limit 가져올 최대 개수
	 * @return 영상 목록(id 오름차순)
	 */
	@Query("select v from Video v where v.userId = :userId and (:after is null or v.id > :after) order by v.id asc")
	List<Video> findPageOldest(@Param("userId") Long userId, @Param("after") Long after, Limit limit);

	/**
	 * 그 카드로 만든 영상(최근 것이 앞).
	 *
	 * @param cardId 카드 ID
	 * @return 영상 목록(id 내림차순)
	 */
	List<Video> findByCardIdOrderByIdDesc(Long cardId);

	/**
	 * 그 카드에 주어진 상태의 영상이 있는지 본다.
	 *
	 * @param cardId 카드 ID
	 * @param statuses 찾을 영상 상태들
	 * @return 그런 영상이 있으면 true
	 */
	boolean existsByCardIdAndStatusIn(Long cardId, Collection<VideoStatus> statuses);

	/**
	 * 회원의 영상을 모두 지운다(탈퇴).
	 *
	 * @param userId 탈퇴하는 회원 ID
	 * @return 지운 영상 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Video v where v.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
