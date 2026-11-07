package com.lolog.api.recording;

import jakarta.persistence.LockModeType;
import java.util.Optional;
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

}
