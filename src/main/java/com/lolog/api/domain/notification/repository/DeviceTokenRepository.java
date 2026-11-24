package com.lolog.api.domain.notification.repository;

import com.lolog.api.domain.notification.entity.DeviceToken;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// FCM 토큰 DB 조회.
public interface DeviceTokenRepository extends JpaRepository<DeviceToken, Long> {

	// 같은 토큰이 다른 사용자에게 있으면 이 사용자로 옮기고, 없으면 새로 넣는다.
	// 한 문장이라 동시에 와도 행이 둘 생기지 않는다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			insert into device_tokens (user_id, token) values (:userId, :token)
			on conflict (token) do update set user_id = excluded.user_id, updated_at = current_timestamp
			""", nativeQuery = true)
	void upsert(@Param("userId") Long userId, @Param("token") String token);

	// 이 사용자의 토큰 하나를 찾는다.
	Optional<DeviceToken> findByTokenAndUserId(String token, Long userId);

	// 사용자의 모든 폰 토큰(등록한 순서).
	@Query("select t.token from DeviceToken t where t.userId = :userId order by t.id")
	List<String> findTokensByUserId(@Param("userId") Long userId);

	// 주어진 토큰들을 한 번에 지운다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from DeviceToken t where t.token in :tokens")
	int deleteByTokens(@Param("tokens") Collection<String> tokens);

	// 사용자의 토큰을 모두 지운다(탈퇴).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from DeviceToken t where t.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
