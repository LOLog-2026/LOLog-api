package com.lolog.api.domain.room.repository;

import com.lolog.api.domain.room.entity.RoomMessage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 방 대화 DB 조회.
public interface RoomMessageRepository extends JpaRepository<RoomMessage, Long> {

	// 대화 줄을 넣고 ID를 돌려준다. 시각은 트랜잭션 시작 시각이 아니라 문장 실행 시각(clock_timestamp)이다
	// (멤버가 대화를 본 시각 last_read_at과 같은 시계).
	@Query(value = """
			insert into room_messages (room_id, user_id, message_type, post_id, body, created_at)
			values (:roomId, :userId, :type, :postId, :body, clock_timestamp()) returning id
			""", nativeQuery = true)
	Long insert(@Param("roomId") Long roomId, @Param("userId") Long userId, @Param("type") String type,
			@Param("postId") Long postId, @Param("body") String body);

	// 방 대화 한 쪽(최신순)과 만화에 단 줄이면 그 만화의 제목·그림. before가 null이면 처음부터.
	@Query("""
			select new com.lolog.api.domain.room.repository.RoomMessageView(x.id, x.userId, x.messageType, x.postId, x.body,
			  x.createdAt, c.id, c.cardSummary, c.imageKey)
			from RoomMessage x left join RoomPost p on p.id = x.postId left join Comic c on c.id = p.comicId
			where x.roomId = :roomId and (:before is null or x.id < :before) order by x.id desc
			""")
	List<RoomMessageView> findPage(@Param("roomId") Long roomId, @Param("before") Long before, Limit limit);

	// 대화 한 줄과 만화에 단 줄이면 그 만화의 제목·그림.
	@Query("""
			select new com.lolog.api.domain.room.repository.RoomMessageView(x.id, x.userId, x.messageType, x.postId, x.body,
			  x.createdAt, c.id, c.cardSummary, c.imageKey)
			from RoomMessage x left join RoomPost p on p.id = x.postId left join Comic c on c.id = p.comicId
			where x.id = :id
			""")
	Optional<RoomMessageView> findView(@Param("id") Long id);

	// 내가 그 만화에 누른 좋아요 줄을 뺀다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from RoomMessage x where x.postId = :postId and x.userId = :userId and x.messageType = 'LIKED'")
	int deleteLiked(@Param("postId") Long postId, @Param("userId") Long userId);

	// 회원이 남긴 대화 줄을 모두 지운다(탈퇴).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from RoomMessage x where x.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
