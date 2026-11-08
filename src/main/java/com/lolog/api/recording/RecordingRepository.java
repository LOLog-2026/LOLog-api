package com.lolog.api.recording;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecordingRepository extends JpaRepository<Recording, Long> {

	// 같은 녹음의 묶음을 한 번에 하나씩 처리하려고 행을 잠근다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Recording> findByUserIdAndClientRecordingId(Long userId, String clientRecordingId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Recording r where r.id = :id")
	Optional<Recording> findByIdForUpdate(@Param("id") Long id);

	Optional<Recording> findByIdAndUserId(Long id, Long userId);

	@Query(value = "select count(*) from cards where recording_id = :id", nativeQuery = true)
	long countCards(@Param("id") Long id);

	// 내 녹음 목록. 최신순(id 내림차순), before가 null이면 처음부터, statuses에 든 상태만.
	@Query("select r from Recording r where r.userId = :userId and r.status in :statuses and (:before is null or r.id < :before) order by r.id desc")
	List<Recording> findPage(@Param("userId") Long userId, @Param("statuses") Collection<RecordingStatus> statuses,
			@Param("before") Long before, Limit limit);

	// 목록의 cardCount. 녹음마다 따로 세지 않고 한 번에 센다.
	@Query(value = "select recording_id, count(*) from cards where recording_id in (:ids) group by recording_id", nativeQuery = true)
	List<Object[]> countCardsByRecordingIds(@Param("ids") Collection<Long> ids);

}
