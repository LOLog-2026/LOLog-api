package com.lolog.api.domain.notification.repository;

import com.lolog.api.domain.notification.entity.Notification;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

// 알림 DB 조회.
public interface NotificationRepository extends JpaRepository<Notification, Long> {

	/**
	 * 알림을 남긴다. created_at은 트랜잭션 시작 시각이 아니라 이 문장을 실행한 시각(clock_timestamp)이다.
	 * 같은 트랜잭션 안에서 새 소식 확인(badge_reads.seen_at, 이것도 clock_timestamp) 뒤에 생긴 알림이 새 소식으로 잡히게 한다.
	 * 푸시는 트랜잭션 밖에서도 보내므로 없으면 이 메서드가 트랜잭션을 연다(있으면 그 안에서 넣는다).
	 *
	 * @param userId 알림을 받을 회원 ID
	 * @param type 알림 종류(COMIC_DONE 등 푸시의 type)
	 * @param data type을 뺀 푸시 값(JSON 객체 문자열)
	 */
	@Transactional
	// 새 줄만 넣어 이미 읽은 엔티티가 낡지 않으므로 영속성 컨텍스트를 비우지 않는다(뒤에 바꾼 엔티티가 저장되게).
	@Modifying(flushAutomatically = true)
	@Query(value = "insert into notifications (user_id, type, data, created_at) values (:userId, :type, cast(:data as jsonb), clock_timestamp())",
			nativeQuery = true)
	void insert(@Param("userId") Long userId, @Param("type") String type, @Param("data") String data);

	/**
	 * 내 알림 하나를 찾는다.
	 *
	 * @param id 알림 ID
	 * @param userId 알림 주인이어야 할 회원 ID
	 * @return 그 회원의 알림(없거나 남의 알림이면 빈 값)
	 */
	Optional<Notification> findByIdAndUserId(Long id, Long userId);

	/**
	 * 안 읽은 알림을 모두 읽음으로 바꾼다.
	 *
	 * @param userId 알림 주인 회원 ID
	 * @param now 읽은 시각으로 남길 값
	 * @return 바뀐 행 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update Notification x set x.readAt = :now where x.userId = :userId and x.readAt is null")
	int markAllRead(@Param("userId") Long userId, @Param("now") OffsetDateTime now);

	/**
	 * since 뒤에 생긴 안 읽은 알림이 있는지 본다.
	 *
	 * @param userId 알림 주인 회원 ID
	 * @param since 이 시각보다 뒤(포함 안 함)에 생긴 알림만 본다
	 * @return 있으면 true
	 */
	boolean existsByUserIdAndReadAtIsNullAndCreatedAtAfter(Long userId, OffsetDateTime since);

	/**
	 * 그 종류의 알림이 하나라도 있는지 본다.
	 *
	 * @param userId 알림 주인 회원 ID
	 * @param types 찾을 알림 종류들
	 * @return 있으면 true
	 */
	boolean existsByUserIdAndTypeIn(Long userId, Collection<String> types);

	/**
	 * 그 종류의 알림이 since 뒤에 생겼는지 본다.
	 *
	 * @param userId 알림 주인 회원 ID
	 * @param types 찾을 알림 종류들
	 * @param since 이 시각보다 뒤(포함 안 함)에 생긴 알림만 본다
	 * @return 있으면 true
	 */
	boolean existsByUserIdAndTypeInAndCreatedAtAfter(Long userId, Collection<String> types, OffsetDateTime since);

	/**
	 * 사용자의 알림을 모두 지운다(탈퇴).
	 *
	 * @param userId 탈퇴하는 회원 ID
	 * @return 지운 행 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Notification x where x.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

	/**
	 * before보다 오래된 알림을 지운다. 정리 작업이 따로 부르므로 이 메서드가 트랜잭션을 연다.
	 *
	 * @param before 이 시각보다 먼저 생긴 알림을 지운다
	 * @return 지운 행 수
	 */
	@Transactional
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Notification x where x.createdAt < :before")
	int deleteCreatedBefore(@Param("before") OffsetDateTime before);

}
