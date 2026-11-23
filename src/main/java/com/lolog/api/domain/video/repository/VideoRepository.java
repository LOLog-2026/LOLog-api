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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VideoRepository extends JpaRepository<Video, Long> {

	// 워커의 단계 보내기·fail이 겹쳐도 한 번에 하나씩 상태를 바꾸게 영상 행을 잠근다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select v from Video v where v.id = :id")
	Optional<Video> findByIdForUpdate(@Param("id") Long id);

	Optional<Video> findByIdAndUserId(Long id, Long userId);

	// 최신순(id 내림차순). before가 null이면 처음부터.
	@Query("select v from Video v where v.userId = :userId and (:before is null or v.id < :before) order by v.id desc")
	List<Video> findPage(@Param("userId") Long userId, @Param("before") Long before, Limit limit);

	// 오래된순(id 오름차순). after가 null이면 처음부터.
	@Query("select v from Video v where v.userId = :userId and (:after is null or v.id > :after) order by v.id asc")
	List<Video> findPageOldest(@Param("userId") Long userId, @Param("after") Long after, Limit limit);

	List<Video> findByCardIdOrderByIdDesc(Long cardId);

	boolean existsByCardIdAndStatusIn(Long cardId, Collection<VideoStatus> statuses);

}
