package com.lolog.api.domain.recording.repository;

import com.lolog.api.domain.recording.entity.RecordingPart;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 녹음 묶음을 DB에서 읽고 쓴다.
public interface RecordingPartRepository extends JpaRepository<RecordingPart, Long> {

	// 녹음의 묶음을 순서대로 찾는다.
	List<RecordingPart> findByRecordingIdOrderByPartNo(Long recordingId);

	// 넘길 글이 있는데 다음 묶음이 오지 않은 묶음(늦게 온 묶음 규칙). before보다 먼저 받은 것만.
	@Query("""
			select p.recording.id from RecordingPart p
			where p.status = com.lolog.api.domain.recording.entity.RecordingStatus.DONE and p.carryFromMs is not null and p.last = false
			  and p.createdAt < :before
			  and not exists (select q.id from RecordingPart q where q.recording = p.recording and q.partNo = p.partNo + 1)
			order by p.id
			""")
	List<Long> findRecordingIdsWaitingTooLong(@Param("before") OffsetDateTime before);

}
