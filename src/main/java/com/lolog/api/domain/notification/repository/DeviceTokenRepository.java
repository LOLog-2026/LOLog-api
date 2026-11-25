package com.lolog.api.domain.notification.repository;

import com.lolog.api.domain.notification.entity.DeviceToken;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// FCM 토큰 DB 조회.
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

	/**
	 * 같은 토큰이 다른 사용자에게 있으면 이 사용자로 옮기고, 없으면 새로 넣는다.
	 * 한 문장이라 동시에 와도 행이 둘 생기지 않는다.
	 *
	 * @param userId 토큰을 등록하는 회원 ID
	 * @param token 폰의 FCM 토큰
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			insert into device_tokens (user_id, token) values (:userId, :token)
			on conflict (token) do update set user_id = excluded.user_id, updated_at = current_timestamp
			""", nativeQuery = true)
	void upsert(@Param("userId") Long userId, @Param("token") String token);

	/**
	 * 이 사용자의 토큰일 때만 지운다. 한 문장이라 같은 토큰을 동시에 지워도 오류가 나지 않는다.
	 *
	 * @param token 지울 FCM 토큰
	 * @param userId 토큰을 지우려는 회원 ID
	 * @return 지운 행 수(0 또는 1)
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from DeviceToken t where t.token = :token and t.userId = :userId")
	int deleteMine(@Param("token") String token, @Param("userId") Long userId);

	/**
	 * 사용자의 모든 폰 토큰(등록한 순서).
	 *
	 * @param userId 토큰 주인 회원 ID
	 * @return FCM 토큰 목록(등록한 순서)
	 */
	@Query("select t.token from DeviceToken t where t.userId = :userId order by t.id")
	List<String> findTokensByUserId(@Param("userId") Long userId);

	/**
	 * 주어진 토큰들을 한 번에 지운다.
	 *
	 * @param tokens 지울 FCM 토큰들
	 * @return 지운 행 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from DeviceToken t where t.token in :tokens")
	int deleteByTokens(@Param("tokens") Collection<String> tokens);

	/**
	 * 사용자의 토큰을 모두 지운다(탈퇴).
	 *
	 * @param userId 탈퇴하는 회원 ID
	 * @return 지운 행 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from DeviceToken t where t.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
