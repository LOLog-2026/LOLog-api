package com.lolog.api.domain.audio.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.audio.entity.RecordingAudioFile;
import com.lolog.api.domain.audio.repository.RecordingAudioFileRepository.ExpiredRecording;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("RecordingAudioFileRepository")
class RecordingAudioFileRepositoryTest extends RepositoryTest {

	private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 1, 12, 0, 0, 0, ZoneOffset.UTC);

	@Autowired
	private RecordingAudioFileRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	private long recording(long userId, boolean audioSaved) {
		return jdbc.queryForObject("""
				insert into recordings (user_id, client_recording_id, started_at, audio_saved)
				values (?, gen_random_uuid()::text, ?, ?) returning id
				""", Long.class, userId, T0, audioSaved);
	}

	// 끝난 지 secondsAgo초 지난 녹음(트랜잭션 시작 시각 now() 기준). null이면 끝난 시각이 없다.
	private long endedRecording(long userId, Integer secondsAgo) {
		return jdbc.queryForObject("""
				insert into recordings (user_id, client_recording_id, started_at, ended_at)
				values (?, gen_random_uuid()::text, ?, now() - make_interval(secs => cast(? as integer))) returning id
				""", Long.class, userId, T0, secondsAgo);
	}

	private void file(long recordingId, int seq) {
		jdbc.update("insert into recording_audio_files (recording_id, seq, audio_key, start_ms, end_ms) values (?, ?, 'k', 0, 1000)",
				recordingId, seq);
	}

	private long card(long recordingId) {
		return jdbc.queryForObject("""
				insert into cards (recording_id, user_id, kind, summary, start_ms, end_ms, recorded_at)
				values (?, 1, '웃김', '요약', 0, 1000, ?) returning id
				""", Long.class, recordingId, T0);
	}

	@Test
	@DisplayName("upsert는 처음 번호면 넣고, 같은 번호가 있으면 키와 구간을 덮어쓴다")
	void upsert_같은_번호면_덮어쓴다() {
		// given
		long recordingId = recording(1L, true);

		// when
		repository.upsert(recordingId, 1, "first", 0L, 1000L);
		repository.upsert(recordingId, 1, "second", 500L, 2000L);
		repository.upsert(recordingId, 2, "third", 2000L, 3000L);

		// then
		List<Map<String, Object>> rows = jdbc.queryForList(
				"select seq, audio_key, start_ms, end_ms from recording_audio_files where recording_id = ? order by seq",
				recordingId);
		assertThat(rows).hasSize(2);
		assertThat(rows.get(0)).containsEntry("audio_key", "second").containsEntry("start_ms", 500L)
				.containsEntry("end_ms", 2000L);
		assertThat(rows.get(1)).containsEntry("audio_key", "third");
	}

	@Test
	@DisplayName("findByRecordingIdOrderByStartMsAscSeqAsc는 시작 시각순, 같으면 번호순으로 읽는다")
	void findByRecordingId_시작_시각_다음_번호순이다() {
		// given
		long recordingId = recording(1L, true);
		repository.upsert(recordingId, 3, "c", 0L, 1000L);
		repository.upsert(recordingId, 1, "a", 2000L, 3000L);
		repository.upsert(recordingId, 2, "b", 0L, 500L);

		// when & then
		assertThat(repository.findByRecordingIdOrderByStartMsAscSeqAsc(recordingId))
				.extracting(RecordingAudioFile::getSeq).containsExactly(2, 3, 1);
	}

	@Test
	@DisplayName("findCardIdsToClip은 음성을 저장했고 파일이 있는 녹음의 구간 음성 없는 카드를 최근 것부터 limit개 돌려준다")
	void findCardIdsToClip_조건에_맞는_카드를_최근_것부터_돌려준다() {
		// given
		long saved = recording(1L, true);
		file(saved, 1);
		long a = card(saved);
		long withAudio = card(saved);
		long b = card(saved);
		jdbc.update("update cards set audio_key = 'k', audio_duration_ms = 10 where id = ?", withAudio);

		// given
		// 음성 파일이 없다
		card(recording(1L, true));
		// 음성을 저장하지 않았다
		long notSaved = recording(1L, false);
		file(notSaved, 1);
		card(notSaved);

		// when & then
		assertThat(repository.findCardIdsToClip(10)).containsExactly(b, a);
		assertThat(repository.findCardIdsToClip(1)).containsExactly(b);
	}

	@Test
	@DisplayName("findExpiredRecordings는 끝난 지 keepSeconds가 지났고 음성 파일이 남은 녹음만 돌려준다")
	void findExpiredRecordings_오래되고_파일이_남은_녹음만_돌려준다() {
		// given
		long expired = endedRecording(7L, 100);
		file(expired, 1);
		long recent = endedRecording(7L, 30);
		file(recent, 1);
		endedRecording(7L, 100);
		long notEnded = endedRecording(7L, null);
		file(notEnded, 1);

		// when
		List<ExpiredRecording> result = repository.findExpiredRecordings(60);

		// then
		assertThat(result).extracting(ExpiredRecording::getId).containsExactly(expired);
		assertThat(result).extracting(ExpiredRecording::getUserId).containsExactly(7L);
	}

	@Test
	@DisplayName("deleteAllByRecordingId는 그 녹음의 음성 파일 줄만 지운다")
	void deleteAllByRecordingId_그_녹음의_줄만_지운다() {
		// given
		long recordingId = recording(1L, true);
		long other = recording(1L, true);
		file(recordingId, 1);
		file(recordingId, 2);
		file(other, 1);

		// when & then
		assertThat(repository.deleteAllByRecordingId(recordingId)).isEqualTo(2);

		// when & then
		assertThat(jdbc.queryForObject("select count(*) from recording_audio_files where recording_id = ?", Long.class, other))
				.isEqualTo(1);
	}

}
