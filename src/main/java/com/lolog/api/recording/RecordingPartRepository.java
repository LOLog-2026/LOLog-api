package com.lolog.api.recording;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RecordingPartRepository extends JpaRepository<RecordingPart, Long> {

	List<RecordingPart> findByRecordingIdOrderByPartNo(Long recordingId);

	// 넘길 글이 있는데 다음 묶음이 오지 않은 묶음(늦게 온 묶음 규칙). before보다 먼저 받은 것만.
	@Query("""
			select p.recording.id from RecordingPart p
			where p.status = com.lolog.api.recording.RecordingStatus.DONE and p.carryFromMs is not null and p.last = false
			  and p.createdAt < :before
			  and not exists (select q.id from RecordingPart q where q.recording = p.recording and q.partNo = p.partNo + 1)
			order by p.id
			""")
	List<Long> findRecordingIdsWaitingTooLong(@Param("before") OffsetDateTime before);

}
