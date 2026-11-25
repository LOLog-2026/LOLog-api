package com.lolog.api.global.storage.repository;

import com.lolog.api.global.storage.entity.StorageDeletion;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 지울 저장소 파일 DB 조회.
public interface StorageDeletionRepository extends JpaRepository<StorageDeletion, Long> {

	/**
	 * 줄 하나를 잠그고 읽는다. 다른 쪽이 지우는 중(잠금)이거나 이미 지웠으면 빈 값이다.
	 * 기다리지 않고 건너뛰어야(skip locked) 커밋 뒤 지우기와 주기 작업이 같은 줄을 두 번 지우지 않는다.
	 *
	 * @param id 줄 ID
	 * @return 잠근 줄, 다른 쪽이 잠갔거나 없으면 빈 값
	 */
	@Query(value = "select * from storage_deletions where id = :id for update skip locked", nativeQuery = true)
	Optional<StorageDeletion> findByIdForUpdateSkipLocked(@Param("id") Long id);

	/**
	 * 다시 지울 줄. 덜 실패한 줄, 오래된 줄부터 읽어서 계속 실패하는 줄이 다른 줄을 막지 않는다.
	 *
	 * @param limit 읽을 줄 수
	 * @return 다시 지울 줄 ID
	 */
	@Query("select d.id from StorageDeletion d order by d.attempts, d.id")
	List<Long> findRetryIds(Limit limit);

	/**
	 * 그 키 하나를 지울 줄을 잠그고 읽는다. 지우는 중이면 끝날 때까지 기다린다.
	 *
	 * @param key 지울 객체 키(prefix 줄은 보지 않음)
	 * @return 그 키를 지울 줄(id 순)
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from StorageDeletion d where d.objectKey = :key and d.prefix = false order by d.id")
	List<StorageDeletion> findKeyForUpdate(@Param("key") String key);

}
