package com.lolog.api.domain.room.repository;

import com.lolog.api.domain.room.entity.RoomPostLike;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 좋아요 DB 조회.
public interface RoomPostLikeRepository extends JpaRepository<RoomPostLike, RoomPostLike.Key> {

	/**
	 * 좋아요를 넣는다. 이미 눌렀으면 그대로 둔다. 넣은 줄 수(0이면 이미 누름).
	 * 새 줄만 넣어 이미 읽은 엔티티가 낡지 않으므로 영속성 컨텍스트를 비우지 않는다(뒤에 바꾼 엔티티가 저장되게).
	 *
	 * @param postId 올린 만화 ID
	 * @param userId 좋아요를 누른 회원 ID
	 * @return 넣은 줄 수(0 또는 1)
	 */
	@Modifying(flushAutomatically = true)
	@Query(value = "insert into room_post_likes (post_id, user_id) values (:postId, :userId) on conflict do nothing",
			nativeQuery = true)
	int insertIfAbsent(@Param("postId") Long postId, @Param("userId") Long userId);

	/**
	 * 좋아요를 뺀다. 뺀 줄 수(0이면 안 누름).
	 *
	 * @param postId 올린 만화 ID
	 * @param userId 좋아요를 취소한 회원 ID
	 * @return 뺀 줄 수(0 또는 1)
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from RoomPostLike l where l.postId = :postId and l.userId = :userId")
	int deleteLike(@Param("postId") Long postId, @Param("userId") Long userId);

	/**
	 * 회원이 남긴 좋아요를 모두 지운다(탈퇴).
	 *
	 * @param userId 탈퇴하는 회원 ID
	 * @return 지운 줄 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from RoomPostLike l where l.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
