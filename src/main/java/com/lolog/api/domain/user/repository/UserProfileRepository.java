package com.lolog.api.domain.user.repository;

import com.lolog.api.domain.user.entity.UserProfile;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 회원 프로필 DB 조회.
public interface UserProfileRepository extends JpaRepository<UserProfile, Long> {

	/**
	 * 프로필 줄이 없으면 넣는다. 이미 있거나 친구 코드가 겹치면 아무것도 하지 않는다(바뀐 줄 0).
	 * 같은 사용자가 동시에 불러도 줄은 하나다(user_profiles_pk).
	 * 새 줄만 넣어 이미 읽은 엔티티가 낡지 않으므로 영속성 컨텍스트를 비우지 않는다(뒤에 바꾼 엔티티가 저장되게).
	 *
	 * @param userId 프로필을 만들 회원 ID
	 * @param friendCode 새로 뽑은 친구 코드(8자 이하)
	 * @return 넣은 줄 수(0 또는 1)
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = "insert into user_profiles (user_id, friend_code) values (:userId, :friendCode) on conflict do nothing",
			nativeQuery = true)
	int insertIfAbsent(@Param("userId") Long userId, @Param("friendCode") String friendCode);

	/**
	 * 프로필 행을 잠그고 찾는다.
	 *
	 * @param userId 프로필 주인 회원 ID
	 * @return 잠근 프로필. 없으면 빈 값
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from UserProfile p where p.userId = :userId")
	Optional<UserProfile> findByIdForUpdate(@Param("userId") Long userId);

	/**
	 * 여러 프로필 행을 회원 ID 순서로 잠그고 찾는다(교착 막기). 없는 회원은 빠진다.
	 *
	 * @param userIds 잠글 프로필의 회원 ID들
	 * @return 잠근 프로필 목록(회원 ID 순)
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from UserProfile p where p.userId in :userIds order by p.userId")
	List<UserProfile> findAllByIdForUpdate(@Param("userIds") Collection<Long> userIds);

	/**
	 * 닉네임을 정한 회원을 친구 코드로 찾는다.
	 *
	 * @param friendCode 찾을 친구 코드
	 * @return 닉네임을 정한 회원의 프로필. 없으면 빈 값
	 */
	Optional<UserProfile> findByFriendCodeAndNicknameIsNotNull(String friendCode);

	/**
	 * 회원의 프로필을 지운다(탈퇴).
	 *
	 * @param userId 탈퇴하는 회원 ID
	 * @return 지운 줄 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from UserProfile p where p.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
