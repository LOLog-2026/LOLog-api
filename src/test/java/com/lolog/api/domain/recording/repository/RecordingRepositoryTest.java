package com.lolog.api.domain.recording.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.recording.entity.Recording;
import com.lolog.api.domain.recording.entity.RecordingStatus;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Limit;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("RecordingRepository")
class RecordingRepositoryTest extends RepositoryTest {

	private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 1, 12, 0, 0, 0, ZoneOffset.UTC);

	@Autowired
	private RecordingRepository recordingRepository;

	@Autowired
	private JdbcTemplate jdbc;

	private long recording(long userId, String status, OffsetDateTime startedAt) {
		return jdbc.queryForObject("""
				insert into recordings (user_id, client_recording_id, started_at, status)
				values (?, gen_random_uuid()::text, ?, ?) returning id
				""", Long.class, userId, startedAt, status);
	}

	private long recording(long userId) {
		return recording(userId, "DONE", T0);
	}

	private void part(long recordingId, int partNo, boolean last) {
		jdbc.update("insert into recording_parts (recording_id, part_no, is_last, duration_sec) values (?, ?, ?, 60)",
				recordingId, partNo, last);
	}

	private long card(long userId, Long recordingId) {
		return jdbc.queryForObject("""
				insert into cards (recording_id, user_id, kind, summary, start_ms, end_ms, recorded_at)
				values (?, ?, '웃김', '요약', 0, 1000, ?) returning id
				""", Long.class, recordingId, userId, T0);
	}

	private static List<Long> ids(List<Recording> recordings) {
		return recordings.stream().map(Recording::getId).toList();
	}

	@Test
	@DisplayName("countCards는 그 녹음으로 만든 카드만 센다")
	void countCards_그_녹음의_카드만_센다() {
		// given
		long id = recording(1L);
		card(1L, id);
		card(1L, id);
		card(1L, recording(1L));

		// when & then
		assertThat(recordingRepository.countCards(id)).isEqualTo(2);
	}

	@Test
	@DisplayName("findPage는 statuses에 든 내 녹음을 최신순으로 읽고 before보다 작은 ID만 읽는다")
	void findPage_상태와_커서로_고른다() {
		// given
		long a = recording(1L, "DONE", T0);
		long b = recording(1L, "DETECTING", T0);
		recording(1L, "FAILED", T0);
		long d = recording(1L, "DONE", T0);
		recording(2L, "DONE", T0);
		List<RecordingStatus> statuses = List.of(RecordingStatus.DONE, RecordingStatus.DETECTING);

		// when & then
		assertThat(ids(recordingRepository.findPage(1L, statuses, null, Limit.of(10)))).containsExactly(d, b, a);
		assertThat(ids(recordingRepository.findPage(1L, statuses, d, Limit.of(10)))).containsExactly(b, a);
		assertThat(ids(recordingRepository.findPage(1L, statuses, null, Limit.of(1)))).containsExactly(d);
	}

	@Test
	@DisplayName("existsUnfinishedSince는 since 뒤에 시작했고 마지막 묶음이 안 온 녹음만 본다")
	void existsUnfinishedSince_마지막_묶음이_안_온_녹음만_본다() {
		// given
		long finished = recording(1L, "DONE", T0.plusMinutes(1));
		part(finished, 1, false);
		part(finished, 2, true);
		recording(1L, "RECEIVED", T0);
		recording(2L, "RECEIVED", T0.plusMinutes(1));

		// when & then
		assertThat(recordingRepository.existsUnfinishedSince(1L, T0)).isFalse();

		// given
		long unfinished = recording(1L, "RECEIVED", T0.plusMinutes(2));
		part(unfinished, 1, false);

		// when & then
		assertThat(recordingRepository.existsUnfinishedSince(1L, T0)).isTrue();
	}

	@Test
	@DisplayName("countCardsByRecordingIds는 녹음마다 카드 수를 세고 카드 없는 녹음은 뺀다")
	void countCardsByRecordingIds_녹음마다_카드_수를_센다() {
		// given
		long a = recording(1L);
		long b = recording(1L);
		long empty = recording(1L);
		card(1L, a);
		card(1L, a);
		card(1L, b);

		// when
		Map<Long, Long> counts = recordingRepository.countCardsByRecordingIds(List.of(a, b, empty)).stream()
				.collect(Collectors.toMap(r -> ((Number) r[0]).longValue(), r -> ((Number) r[1]).longValue()));

		// then
		assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of(a, 2L, b, 1L));
	}

	@Test
	@DisplayName("deleteAllByUserId는 그 회원의 녹음과 묶음을 지우고 다른 회원 녹음은 둔다")
	void deleteAllByUserId_그_회원의_녹음만_지운다() {
		// given
		long mine = recording(1L);
		part(mine, 1, true);
		long other = recording(2L);

		// when & then
		assertThat(recordingRepository.deleteAllByUserId(1L)).isEqualTo(1);

		// when & then
		assertThat(jdbc.queryForList("select id from recordings where user_id in (1, 2)", Long.class)).containsExactly(other);
		assertThat(jdbc.queryForObject("select count(*) from recording_parts where recording_id = ?", Long.class, mine))
				.isZero();
	}

}
