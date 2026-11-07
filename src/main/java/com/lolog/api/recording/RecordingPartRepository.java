package com.lolog.api.recording;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecordingPartRepository extends JpaRepository<RecordingPart, Long> {

	List<RecordingPart> findByRecordingIdOrderByPartNo(Long recordingId);

}
