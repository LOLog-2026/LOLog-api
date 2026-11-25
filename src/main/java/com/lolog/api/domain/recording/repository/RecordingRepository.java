package com.lolog.api.domain.recording.repository;

import com.lolog.api.domain.recording.entity.Recording;
import com.lolog.api.domain.recording.entity.RecordingStatus;
import jakarta.persistence.LockModeType;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 녹음을 DB에서 읽고 쓴다.
public interface RecordingRepository extends JpaRepository<Recording, Long> {

	/**
	 * 같은 녹음의 묶음을 한 번에 하나씩 처리하려고 행을 잠근다.
	 *
	 * @param userId 녹음 주인 회원 ID
	 * @param clientRecordingId 앱이 만든 녹음 UUID
	 * @return 잠근 녹음. 없으면 빈 값
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	Optional<Recording> findByUserIdAndClientRecordingId(Long userId, String clientRecordingId);

	/**
	 * 녹음 행을 잠그고 찾는다.
	 *
	 * @param id 녹음 ID
	 * @return 잠근 녹음. 없으면 빈 값
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Recording r where r.id = :id")
	Optional<Recording> findByIdForUpdate(@Param("id") Long id);

	/**
	 * 내 녹음 행을 잠그고 찾는다.
	 *
	 * @param id 녹음 ID
	 * @param userId 녹음 주인이어야 하는 회원 ID
	 * @return 잠근 녹음. 없거나 주인이 다르면 빈 값
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Recording r where r.id = :id and r.userId = :userId")
	Optional<Recording> findByIdAndUserIdForUpdate(@Param("id") Long id, @Param("userId") Long userId);

	/**
	 * 내 녹음 하나를 찾는다.
	 *
	 * @param id 녹음 ID
	 * @param userId 녹음 주인이어야 하는 회원 ID
	 * @return 녹음. 없거나 주인이 다르면 빈 값
	 */
	Optional<Recording> findByIdAndUserId(Long id, Long userId);

	/**
	 * 이 녹음으로 만든 카드 수를 센다.
	 *
	 * @param id 녹음 ID
	 * @return 카드 수
	 */
	@Query(value = "select count(*) from cards where recording_id = :id", nativeQuery = true)
	long countCards(@Param("id") Long id);

	/**
	 * 내 녹음 목록. 최신순(id 내림차순), before가 null이면 처음부터, statuses에 든 상태만.
	 *
	 * @param userId 녹음 주인 회원 ID
	 * @param statuses 돌려줄 녹음 상태들
	 * @param before 이 ID보다 작은 녹음만(cursor). null이면 처음부터
	 * @param limit 가져올 최대 개수
	 * @return 녹음 목록(id 내림차순)
	 */
	@Query("select r from Recording r where r.userId = :userId and r.status in :statuses and (:before is null or r.id < :before) order by r.id desc")
	List<Recording> findPage(@Param("userId") Long userId, @Param("statuses") Collection<RecordingStatus> statuses,
			@Param("before") Long before, Limit limit);

	/**
	 * since 뒤에 시작했고 마지막 묶음(isLast)이 아직 안 온 이 회원의 녹음이 있는지 본다.
	 *
	 * @param userId 녹음 주인 회원 ID
	 * @param since 이 시각 뒤에 시작한 녹음만 본다
	 * @return 그런 녹음이 있으면 true
	 */
	@Query("""
			select count(r) > 0 from Recording r where r.userId = :userId and r.startedAt > :since
			  and not exists (select p.id from RecordingPart p where p.recording = r and p.last = true)
			""")
	boolean existsUnfinishedSince(@Param("userId") Long userId, @Param("since") OffsetDateTime since);

	/**
	 * 목록의 cardCount. 녹음마다 따로 세지 않고 한 번에 센다.
	 *
	 * @param ids 카드 수를 셀 녹음 ID들(비어 있으면 안 된다)
	 * @return [녹음 ID, 카드 수] 행 목록. 카드가 없는 녹음은 빠진다
	 */
	@Query(value = "select recording_id, count(*) from cards where recording_id in (:ids) group by recording_id", nativeQuery = true)
	List<Object[]> countCardsByRecordingIds(@Param("ids") Collection<Long> ids);

	/**
	 * 회원의 녹음을 모두 지운다(탈퇴).
	 *
	 * @param userId 탈퇴하는 회원 ID
	 * @return 지운 녹음 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Recording r where r.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
