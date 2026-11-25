package com.lolog.api.domain.audio.repository;

import com.lolog.api.domain.audio.entity.RecordingAudioFile;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// 녹음 음성 파일 DB 조회.
public interface RecordingAudioFileRepository extends JpaRepository<RecordingAudioFile, Long> {

	// 7일 지난 녹음(음성 파일이 남은 것)의 ID와 주인.
	interface ExpiredRecording {

		Long getId();

		Long getUserId();

	}

	/**
	 * 같은 번호가 있으면 덮어쓰고, 없으면 새로 넣는다.
	 *
	 * @param recordingId 음성 파일이 속한 녹음 ID
	 * @param seq 음성 파일 번호(1부터)
	 * @param audioKey 저장소에 올린 음성 파일 키
	 * @param startMs 파일이 시작하는 녹음 시작부터의 시각(ms)
	 * @param endMs 파일이 끝나는 녹음 시작부터의 시각(ms)
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			insert into recording_audio_files (recording_id, seq, audio_key, start_ms, end_ms)
			values (:recordingId, :seq, :audioKey, :startMs, :endMs)
			on conflict (recording_id, seq) do update
			set audio_key = excluded.audio_key, start_ms = excluded.start_ms, end_ms = excluded.end_ms,
			    updated_at = current_timestamp
			""", nativeQuery = true)
	void upsert(@Param("recordingId") Long recordingId, @Param("seq") Integer seq, @Param("audioKey") String audioKey,
			@Param("startMs") Long startMs, @Param("endMs") Long endMs);

	/**
	 * 녹음의 음성 파일을 시간순으로.
	 *
	 * @param recordingId 녹음 ID
	 * @return 시작 시각, 같으면 번호 오름차순 음성 파일
	 */
	List<RecordingAudioFile> findByRecordingIdOrderByStartMsAscSeqAsc(Long recordingId);

	/**
	 * 음성 파일이 있는데 구간 음성이 아직 없는 카드(최근 것부터 limit개).
	 *
	 * @param limit 가져올 최대 카드 수
	 * @return 카드 ID 목록(ID 내림차순)
	 */
	@Query(value = """
			select c.id from cards c join recordings r on r.id = c.recording_id
			where c.audio_key is null and r.audio_saved
			  and exists (select 1 from recording_audio_files f where f.recording_id = r.id)
			order by c.id desc limit :limit
			""", nativeQuery = true)
	List<Long> findCardIdsToClip(@Param("limit") int limit);

	/**
	 * 끝난 지 keepSeconds가 지났는데 음성 파일이 남은 녹음.
	 *
	 * @param keepSeconds 녹음이 끝난 뒤 음성 파일을 두는 시간(초)
	 * @return 녹음 ID와 주인 회원 ID
	 */
	@Query(value = """
			select r.id as "id", r.user_id as "userId" from recordings r
			where r.ended_at < now() - make_interval(secs => :keepSeconds)
			  and exists (select 1 from recording_audio_files f where f.recording_id = r.id)
			""", nativeQuery = true)
	List<ExpiredRecording> findExpiredRecordings(@Param("keepSeconds") long keepSeconds);

	/**
	 * 녹음의 음성 파일 줄을 모두 지운다. 정리 작업이 녹음마다 따로 부르므로 이 메서드가 트랜잭션을 연다.
	 *
	 * @param recordingId 음성 파일 줄을 지울 녹음 ID
	 * @return 지운 행 수
	 */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from RecordingAudioFile f where f.recordingId = :recordingId")
	int deleteAllByRecordingId(@Param("recordingId") Long recordingId);

}
