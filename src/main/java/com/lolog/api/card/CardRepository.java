package com.lolog.api.card;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CardRepository extends JpaRepository<Card, Long> {

	List<Card> findByRecordingIdOrderById(Long recordingId);

}
