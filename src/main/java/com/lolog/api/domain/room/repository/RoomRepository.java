package com.lolog.api.domain.room.repository;

import com.lolog.api.domain.room.entity.Room;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 방 DB 조회.
public interface RoomRepository extends JpaRepository<Room, Long> {

	// 같은 방의 인원을 동시에 바꾸지 않게 방 행을 잠그고 찾는다.
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select r from Room r where r.id = :id")
	Optional<Room> findByIdForUpdate(@Param("id") Long id);

	// 내가 들어간 방 ID. 마지막 소식이 최근인 방이 위(소식이 없으면 만든 시각).
	@Query("""
			select r.id from Room r join RoomMember m on m.roomId = r.id
			where m.userId = :me
			order by coalesce(r.lastActivityAt, r.createdAt) desc, r.id desc
			""")
	List<Long> findIdsOfMember(@Param("me") Long me);

	// 방과 마지막 대화 줄, 내 받지 않은 글 수(마지막으로 본 뒤 다른 사람이 남긴 대화 줄 수). 순서는 정하지 않는다.
	@Query("""
			select new com.lolog.api.domain.room.repository.RoomItemView(r.id, r.name, r.ownerId, r.createdAt,
			  x.messageType, x.userId, x.createdAt, x.body,
			  (select count(u) from RoomMember m join RoomMessage u
			     on u.roomId = m.roomId and u.userId <> m.userId and u.createdAt > m.lastReadAt
			   where m.roomId = r.id and m.userId = :me))
			from Room r left join RoomMessage x on x.roomId = r.id
			  and x.id = (select max(y.id) from RoomMessage y where y.roomId = r.id)
			where r.id in :roomIds
			""")
	List<RoomItemView> findItems(@Param("me") Long me, @Param("roomIds") Collection<Long> roomIds);

	// 마지막 소식 시각을 지금(문장 실행 시각)으로 바꾼다. 대화 줄의 created_at과 같은 시계를 쓴다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			update rooms set last_activity_at = clock_timestamp(), updated_at = current_timestamp where id = :id
			""", nativeQuery = true)
	int touchActivity(@Param("id") Long id);

	// 멤버가 없는 방이면 지운다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Room r where r.id = :id and not exists (select 1 from RoomMember m where m.roomId = r.id)")
	int deleteIfEmpty(@Param("id") Long id);

	// 방장이 멤버가 아니면 가장 먼저 들어온 멤버에게 넘긴다. 조건과 새 방장 고르기를 한 문장으로 해서 native query로 둔다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = """
			update rooms r set owner_id = (select m.user_id from room_members m where m.room_id = r.id
			  order by m.created_at, m.user_id limit 1), updated_at = current_timestamp
			where r.id = :id and not exists (select 1 from room_members m where m.room_id = r.id and m.user_id = r.owner_id)
			""", nativeQuery = true)
	int handOverIfOwnerLeft(@Param("id") Long id);

	// 회원이 방장인 방 ID.
	@Query("select r.id from Room r where r.ownerId = :ownerId")
	List<Long> findIdsByOwnerId(@Param("ownerId") Long ownerId);

	// 멤버가 없는 방을 모두 지운다(탈퇴).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from Room r where not exists (select 1 from RoomMember m where m.roomId = r.id)")
	int deleteAllEmpty();

}
