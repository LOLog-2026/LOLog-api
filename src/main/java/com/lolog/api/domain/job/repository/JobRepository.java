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

	/**
	 * 커밋 뒤 큐에 넣지 못한 작업(attempts = 0). type은 DETECT 또는 COMIC.
	 *
	 * @param type 작업 종류(DETECT 또는 COMIC)
	 * @param before 이 시각보다 먼저 만든 작업만 고른다
	 * @return 아직 큐에 넣지 못한 작업 ID 목록(ID 오름차순)
	 */
	@Query("select j.id from Job j where j.jobType = :type and j.status = 'QUEUED' and j.attempts = 0 and j.createdAt < :before order by j.id")
	List<Long> findUnpublishedIds(@Param("type") String type, @Param("before") OffsetDateTime before);

	/**
	 * 만화의 작업을 찾는다.
	 *
	 * @param comicId 작업을 찾을 만화 ID
	 * @return 그 만화의 작업(없으면 빈 값)
	 */
	Optional<Job> findByComicId(Long comicId);

	/**
	 * 잠그기 전에 엔티티를 읽어 두면 뒤의 잠금 조회가 그 옛 값을 돌려주므로, 만화 ID만 꺼낸다.
	 *
	 * @param id 작업 ID
	 * @return 작업의 만화 ID(작업이 없으면 빈 값)
	 */
	@Query("select j.comicId from Job j where j.id = :id")
	Optional<Long> findComicIdById(@Param("id") Long id);

	/**
	 * findComicIdById와 같은 이유로 영상 ID만 꺼낸다.
	 *
	 * @param id 작업 ID
	 * @return 작업의 영상 ID(작업이 없으면 빈 값)
	 */
	@Query("select j.videoId from Job j where j.id = :id")
	Optional<Long> findVideoIdById(@Param("id") Long id);

	/**
	 * 작업 종류만 꺼낸다.
	 *
	 * @param id 작업 ID
	 * @return 작업 종류(job_type, 작업이 없으면 빈 값)
	 */
	@Query("select j.jobType from Job j where j.id = :id")
	Optional<String> findTypeById(@Param("id") Long id);

	/**
	 * 같은 작업의 fail이 동시에 두 번 와도 한 번만 처리하게 행을 잠근다.
	 *
	 * @param id 잠글 작업 ID
	 * @return 잠근 작업(없으면 빈 값)
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select j from Job j where j.id = :id")
	Optional<Job> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 같은 묶음에 앞서 끝난 작업이 있으면, 이 작업은 늦게 온 묶음의 넘겨 둔 부분만 처리하는 작업이다.
	 *
	 * @param recordingPartId 살펴볼 녹음 묶음 ID
	 * @param status 찾을 작업 상태
	 * @param id 빼고 볼 작업 ID(지금 처리하는 작업)
	 * @return 조건에 맞는 다른 작업이 있으면 true
	 */
	boolean existsByRecordingPartIdAndStatusAndIdNot(Long recordingPartId, String status, Long id);

	/**
	 * 녹음 상태를 정할 때 쓴다. 감지에 성공한 묶음이 하나라도 있는지.
	 *
	 * @param recordingId 녹음 ID
	 * @return 그 녹음의 묶음 작업 중 DONE인 것의 수
	 */
	@Query(value = "select count(*) from jobs j join recording_parts p on p.id = j.recording_part_id where p.recording_id = :recordingId and j.status = 'DONE'", nativeQuery = true)
	long countDoneByRecordingId(@Param("recordingId") Long recordingId);

	/**
	 * 녹음 행을 먼저 잠그려고, 작업을 읽기 전에 녹음 ID만 꺼낸다.
	 *
	 * @param jobId 감지 작업 ID
	 * @return 작업의 묶음이 속한 녹음 ID(없으면 빈 값)
	 */
	@Query(value = "select p.recording_id from jobs j join recording_parts p on p.id = j.recording_part_id where j.id = :jobId", nativeQuery = true)
	Optional<Long> findRecordingIdByJobId(@Param("jobId") Long jobId);

	/**
	 * since 뒤에 받은 이 회원의 프로필 캐릭터 작업 수. 실패한 작업은 세지 않지만, 사용자가 지워서 멈춘 작업은 센다.
	 *
	 * @param userId 작업을 센 회원 ID
	 * @param since 이 시각 이후(포함)에 만든 작업만 센다
	 * @return AVATAR 작업 수
	 */
	@Query("""
			select count(j) from Job j where j.userId = :userId and j.jobType = 'AVATAR'
			  and (j.status <> 'FAILED' or j.failReason = '캐릭터를 지움') and j.createdAt >= :since
			""")
	long countAvatarJobsSince(@Param("userId") Long userId, @Param("since") OffsetDateTime since);

	/**
	 * 회원의 type 작업 중 아직 대기 중인(QUEUED) 것을 모두 성공으로 끝낸다.
	 *
	 * @param userId 작업 주인 회원 ID
	 * @param type 끝낼 작업 종류
	 * @return 바뀐 행 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Job j set j.status = 'DONE', j.updatedAt = current_timestamp
			where j.userId = :userId and j.jobType = :type and j.status = 'QUEUED'
			""")
	int completeQueued(@Param("userId") Long userId, @Param("type") String type);

	/**
	 * 회원의 type 작업 중 아직 대기 중인(QUEUED) 것을 모두 실패로 끝내고 이유를 남긴다.
	 *
	 * @param userId 작업 주인 회원 ID
	 * @param type 끝낼 작업 종류
	 * @param reason 남길 실패 이유(fail_reason)
	 * @return 바뀐 행 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Job j set j.status = 'FAILED', j.failReason = :reason, j.updatedAt = current_timestamp
			where j.userId = :userId and j.jobType = :type and j.status = 'QUEUED'
			""")
	int failQueued(@Param("userId") Long userId, @Param("type") String type, @Param("reason") String reason);

	/**
	 * 영상의 그 단계(type) 작업을 아직 끝나지 않았으면 성공으로 끝낸다.
	 *
	 * @param videoId 영상 ID
	 * @param type 끝낼 단계의 작업 종류(VIDEO_VOICE 또는 VIDEO_DRAW)
	 * @return 바뀐 행 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Job j set j.status = 'DONE', j.updatedAt = current_timestamp
			where j.videoId = :videoId and j.jobType = :type and j.status not in ('DONE', 'FAILED')
			""")
	int completeVideoStep(@Param("videoId") Long videoId, @Param("type") String type);

	/**
	 * 회원의 작업을 모두 지운다(탈퇴).
	 *
	 * @param userId 탈퇴하는 회원 ID
	 * @return 지운 행 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Job j where j.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
