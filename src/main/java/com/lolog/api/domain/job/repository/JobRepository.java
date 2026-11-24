package com.lolog.api.domain.job.repository;

import com.lolog.api.domain.job.entity.Job;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 작업(jobs)을 DB에서 읽고 쓴다.
public interface JobRepository extends JpaRepository<Job, Long> {

	// 커밋 뒤 큐에 넣지 못한 작업(attempts = 0). type은 DETECT 또는 COMIC.
	@Query("select j.id from Job j where j.jobType = :type and j.status = 'QUEUED' and j.attempts = 0 and j.createdAt < :before order by j.id")
	List<Long> findUnpublishedIds(@Param("type") String type, @Param("before") OffsetDateTime before);

	// 만화의 작업을 찾는다.
	Optional<Job> findByComicId(Long comicId);

	// 잠그기 전에 엔티티를 읽어 두면 뒤의 잠금 조회가 그 옛 값을 돌려주므로, 만화 ID만 꺼낸다.
	@Query("select j.comicId from Job j where j.id = :id")
	Optional<Long> findComicIdById(@Param("id") Long id);

	// findComicIdById와 같은 이유로 영상 ID만 꺼낸다.
	@Query("select j.videoId from Job j where j.id = :id")
	Optional<Long> findVideoIdById(@Param("id") Long id);

	// 작업 종류만 꺼낸다.
	@Query("select j.jobType from Job j where j.id = :id")
	Optional<String> findTypeById(@Param("id") Long id);

	// 같은 작업의 fail이 동시에 두 번 와도 한 번만 처리하게 행을 잠근다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select j from Job j where j.id = :id")
	Optional<Job> findByIdForUpdate(@Param("id") Long id);

	// 같은 묶음에 앞서 끝난 작업이 있으면, 이 작업은 늦게 온 묶음의 넘겨 둔 부분만 처리하는 작업이다.
	boolean existsByRecordingPartIdAndStatusAndIdNot(Long recordingPartId, String status, Long id);

	// 녹음 상태를 정할 때 쓴다. 감지에 성공한 묶음이 하나라도 있는지.
	@Query(value = "select count(*) from jobs j join recording_parts p on p.id = j.recording_part_id where p.recording_id = :recordingId and j.status = 'DONE'", nativeQuery = true)
	long countDoneByRecordingId(@Param("recordingId") Long recordingId);

	// 녹음 행을 먼저 잠그려고, 작업을 읽기 전에 녹음 ID만 꺼낸다.
	@Query(value = "select p.recording_id from jobs j join recording_parts p on p.id = j.recording_part_id where j.id = :jobId", nativeQuery = true)
	Optional<Long> findRecordingIdByJobId(@Param("jobId") Long jobId);

	// since 뒤에 받은 이 회원의 프로필 캐릭터 작업 수. 실패한 작업은 세지 않지만, 사용자가 지워서 멈춘 작업은 센다.
	@Query("""
			select count(j) from Job j where j.userId = :userId and j.jobType = 'AVATAR'
			  and (j.status <> 'FAILED' or j.failReason = '캐릭터를 지움') and j.createdAt >= :since
			""")
	long countAvatarJobsSince(@Param("userId") Long userId, @Param("since") OffsetDateTime since);

	// 회원의 type 작업 중 아직 대기 중인(QUEUED) 것을 모두 성공으로 끝낸다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Job j set j.status = 'DONE', j.updatedAt = current_timestamp
			where j.userId = :userId and j.jobType = :type and j.status = 'QUEUED'
			""")
	int completeQueued(@Param("userId") Long userId, @Param("type") String type);

	// 회원의 type 작업 중 아직 대기 중인(QUEUED) 것을 모두 실패로 끝내고 이유를 남긴다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Job j set j.status = 'FAILED', j.failReason = :reason, j.updatedAt = current_timestamp
			where j.userId = :userId and j.jobType = :type and j.status = 'QUEUED'
			""")
	int failQueued(@Param("userId") Long userId, @Param("type") String type, @Param("reason") String reason);

	// 영상의 그 단계(type) 작업을 아직 끝나지 않았으면 성공으로 끝낸다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Job j set j.status = 'DONE', j.updatedAt = current_timestamp
			where j.videoId = :videoId and j.jobType = :type and j.status not in ('DONE', 'FAILED')
			""")
	int completeVideoStep(@Param("videoId") Long videoId, @Param("type") String type);

	// 회원의 작업을 모두 지운다(탈퇴).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Job j where j.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
