package com.lolog.api.domain.recording.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.recording.entity.RecordingPart;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("RecordingPartRepository")
class RecordingPartRepositoryTest extends RepositoryTest {

	private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 1, 12, 0, 0, 0, ZoneOffset.UTC);

	@Autowired
	private RecordingPartRepository recordingPartRepository;

	@Autowired
	private JdbcTemplate jdbc;

	private long recording() {
		return jdbc.queryForObject("""
				insert into recordings (user_id, client_recording_id, started_at) values (1, gen_random_uuid()::text, ?) returning id
				""", Long.class, T0);
	}

	private long part(long recordingId, int partNo, boolean last, String status, Long carryFromMs,
			OffsetDateTime createdAt) {
		return jdbc.queryForObject("""
				insert into recording_parts (recording_id, part_no, is_last, duration_sec, status, carry_from_ms, created_at)
				values (?, ?, ?, 60, ?, ?, ?) returning id
				""", Long.class, recordingId, partNo, last, status, carryFromMs, createdAt);
	}

	// 넘길 글이 있고 마지막이 아닌 DONE 묶음.
	private long waitingPart(long recordingId, int partNo, OffsetDateTime createdAt) {
		return part(recordingId, partNo, false, "DONE", 1000L, createdAt);
	}

	@Test
	@DisplayName("findByRecordingIdOrderByPartNo는 그 녹음의 묶음을 번호순으로 읽는다")
	void findByRecordingIdOrderByPartNo_번호순으로_읽는다() {
		// given
		long recordingId = recording();
		part(recordingId, 2, false, "RECEIVED", null, T0);
		part(recordingId, 1, false, "RECEIVED", null, T0);
		part(recording(), 1, false, "RECEIVED", null, T0);

		// when & then
		assertThat(recordingPartRepository.findByRecordingIdOrderByPartNo(recordingId))
				.extracting(RecordingPart::getPartNo).containsExactly(1, 2);
	}

	@Test
	@DisplayName("findRecordingIdsWaitingTooLong은 넘길 글이 있고 다음 묶음이 없는 오래된 DONE 묶음의 녹음 ID를 묶음 id순으로 돌려준다")
	void findRecordingIdsWaitingTooLong_조건에_맞는_녹음만_돌려준다() {
		// given
		OffsetDateTime before = T0.plusMinutes(10);
		long second = recording();
		long first = recording();
		waitingPart(first, 1, T0);
		waitingPart(second, 3, T0);
		// 다음 묶음이 왔다
		long hasNext = recording();
		waitingPart(hasNext, 1, T0);
		part(hasNext, 2, false, "RECEIVED", null, T0);
		// 마지막 묶음이다
		part(recording(), 1, true, "DONE", 1000L, T0);
		// 넘길 글이 없다
		part(recording(), 1, false, "DONE", null, T0);
		// 아직 감지가 끝나지 않았다
		part(recording(), 1, false, "DETECTING", 1000L, T0);
		// before와 같은 시각에 받았다
		waitingPart(recording(), 1, before);

		// when & then
		assertThat(recordingPartRepository.findRecordingIdsWaitingTooLong(before)).containsExactly(first, second);
	}

	@Test
	@DisplayName("findRecordingIdsWaitingTooLong은 다음 번호가 아닌 묶음이 와 있어도 기다리는 것으로 본다")
	void findRecordingIdsWaitingTooLong_다음_번호만_본다() {
		// given
		long recordingId = recording();
		waitingPart(recordingId, 1, T0);
		part(recordingId, 3, false, "RECEIVED", null, T0);

		// when & then
		assertThat(recordingPartRepository.findRecordingIdsWaitingTooLong(T0.plusMinutes(1))).containsExactly(recordingId);
	}

	@Test
	@DisplayName("existsLast는 그 녹음에 마지막 묶음이 있을 때만 true다")
	void existsLast_마지막_묶음이_있을_때만_true다() {
		// given
		long recordingId = recording();
		part(recordingId, 1, false, "RECEIVED", null, T0);
		part(recording(), 1, true, "RECEIVED", null, T0);

		// when & then
		assertThat(recordingPartRepository.existsLast(recordingId)).isFalse();

		// given
		part(recordingId, 2, true, "RECEIVED", null, T0);

		// when & then
		assertThat(recordingPartRepository.existsLast(recordingId)).isTrue();
	}

}
