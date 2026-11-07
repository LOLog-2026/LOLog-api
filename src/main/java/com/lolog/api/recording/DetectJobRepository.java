package com.lolog.api.recording;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DetectJobRepository extends JpaRepository<DetectJob, Long> {

	@Query("select j.id from DetectJob j where j.jobType = 'DETECT' and j.status = 'QUEUED' and j.attempts = 0 and j.createdAt < :before order by j.id")
	List<Long> findUnpublishedIds(@Param("before") OffsetDateTime before);

}
