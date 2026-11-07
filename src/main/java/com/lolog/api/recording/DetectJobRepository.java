package com.lolog.api.recording;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DetectJobRepository extends JpaRepository<DetectJob, Long> {

	@Query("select j.id from DetectJob j where j.jobType = 'DETECT' and j.status = 'QUEUED' and j.attempts = 0 and j.createdAt < :before order by j.id")
	List<Long> findUnpublishedIds(@Param("before") OffsetDateTime before);

	// 같은 묶음에 앞서 끝난 작업이 있으면, 이 작업은 늦게 온 묶음의 넘겨 둔 부분만 처리하는 작업이다.
	boolean existsByRecordingPartIdAndStatusAndIdNot(Long recordingPartId, String status, Long id);

	// 녹음 상태를 정할 때 쓴다. 감지에 성공한 묶음이 하나라도 있는지.
	@Query(value = "select count(*) from jobs j join recording_parts p on p.id = j.recording_part_id where p.recording_id = :recordingId and j.status = 'DONE'", nativeQuery = true)
	long countDoneByRecordingId(@Param("recordingId") Long recordingId);

	// 녹음 행을 먼저 잠그려고, 작업을 읽기 전에 녹음 ID만 꺼낸다.
	@Query(value = "select p.recording_id from jobs j join recording_parts p on p.id = j.recording_part_id where j.id = :jobId", nativeQuery = true)
	Optional<Long> findRecordingIdByJobId(@Param("jobId") Long jobId);

}
