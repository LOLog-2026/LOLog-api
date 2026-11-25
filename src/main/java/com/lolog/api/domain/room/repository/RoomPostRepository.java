package com.lolog.api.domain.room.repository;

import com.lolog.api.domain.room.entity.RoomPost;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 방에 올린 만화 DB 조회.
public interface RoomPostRepository extends JpaRepository<RoomPost, Long> {

	/**
	 * 처음 올리는 만화면 넣고 ID를 돌려준다. 이 방에 이미 올렸으면 비어 있다.
	 *
	 * @param roomId 방 ID
	 * @param comicId 올릴 만화 ID
	 * @param userId 올리는 회원 ID
	 * @return 새 올린 만화 ID(이미 올렸으면 빈 값)
	 */
	@Query(value = """
			insert into room_posts (room_id, comic_id, user_id) values (:roomId, :comicId, :userId)
			on conflict (room_id, comic_id) do nothing returning id
			""", nativeQuery = true)
	Optional<Long> insertNew(@Param("roomId") Long roomId, @Param("comicId") Long comicId, @Param("userId") Long userId);

	/**
	 * 이 방에 올린 만화 하나.
	 *
	 * @param id 올린 만화 ID
	 * @param roomId 방 ID
	 * @return 올린 만화(없거나 다른 방이면 빈 값)
	 */
	Optional<RoomPost> findByIdAndRoomId(Long id, Long roomId);

	/**
	 * 방에 올린 만화와 만화 제목·그림, 좋아요 수, 내가 눌렀는지, 댓글 수. postId가 있으면 그 하나만. 최근에 올린 것이 앞.
	 * 만화를 지우면 올린 기록도 DB가 지우므로 만화가 없는 줄은 없지만, 그래도 있으면 뺀다(join).
	 *
	 * @param me 보는 회원 ID(나)
	 * @param roomId 방 ID
	 * @param postId 이 하나만 읽을 올린 만화 ID(null이면 모두)
	 * @return 올린 만화(최근에 올린 것이 앞)
	 */
	@Query("""
			select new com.lolog.api.domain.room.repository.RoomPostView(p.id, p.comicId, p.userId, p.createdAt,
			  c.cardSummary, c.imageKey,
			  (select count(l) from RoomPostLike l where l.postId = p.id),
			  case when exists (select 1 from RoomPostLike ml where ml.postId = p.id and ml.userId = :me) then true else false end,
			  (select count(x) from RoomMessage x where x.postId = p.id and x.messageType = 'COMMENT'))
			from RoomPost p join Comic c on c.id = p.comicId
			where p.roomId = :roomId and (:postId is null or p.id = :postId)
			order by p.createdAt desc, p.id desc
			""")
	List<RoomPostView> findViews(@Param("me") Long me, @Param("roomId") Long roomId, @Param("postId") Long postId);

	/**
	 * 올린 만화를 내린다. 좋아요·대화 줄은 DB가 함께 지운다. 그 사이 지워졌어도 오류 없이 지나가도록 한 문장으로 지운다.
	 *
	 * @param id 올린 만화 ID
	 * @return 지운 줄 수
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from RoomPost p where p.id = :id")
	int deletePost(@Param("id") Long id);

}
