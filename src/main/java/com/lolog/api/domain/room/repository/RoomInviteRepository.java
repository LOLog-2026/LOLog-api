package com.lolog.api.domain.room.repository;

import com.lolog.api.domain.room.entity.RoomInvite;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 방 초대 DB 조회.
public interface RoomInviteRepository extends JpaRepository<RoomInvite, Long> {

	// 초대 줄을 넣고 ID를 돌려준다. 초대한 시각은 문장 실행 시각(clock_timestamp)이다.
	@Query(value = """
			insert into room_invites (room_id, user_id, invited_by, created_at) values (:roomId, :userId, :invitedBy, clock_timestamp())
			returning id
			""", nativeQuery = true)
	Long insert(@Param("roomId") Long roomId, @Param("userId") Long userId, @Param("invitedBy") Long invitedBy);

	// 내가 받은 초대의 방 ID.
	@Query("select i.roomId from RoomInvite i where i.id = :id and i.userId = :userId")
	Optional<Long> findRoomIdByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

	// 이 방에 답을 기다리는 초대가 있는지 본다.
	boolean existsByRoomIdAndUserId(Long roomId, Long userId);

	// 받은 초대가 있는지 본다.
	boolean existsByUserId(Long userId);

	// 방의 답을 기다리는 초대 수.
	long countByRoomId(Long roomId);

	// 내가 받은 초대와 방 이름, 그 방에 내가 차단한 멤버가 있는지(최신순).
	@Query("""
			select new com.lolog.api.domain.room.repository.ReceivedInviteView(i.id, i.roomId, r.name, i.invitedBy, i.createdAt,
			  case when exists (select 1 from RoomMember m join UserBlock b on b.blockedId = m.userId and b.blockerId = i.userId
			                    where m.roomId = i.roomId) then true else false end)
			from RoomInvite i join Room r on r.id = i.roomId
			where i.userId = :userId order by i.createdAt desc, i.id desc
			""")
	List<ReceivedInviteView> findReceived(@Param("userId") Long userId);

	// 내가 방장인 방들의 답을 기다리는 초대와 방 이름(오래된순).
	@Query("""
			select new com.lolog.api.domain.room.repository.SentInviteView(i.id, i.roomId, r.name, i.userId, i.createdAt)
			from RoomInvite i join Room r on r.id = i.roomId
			where i.roomId in :roomIds and r.ownerId = :owner order by i.createdAt, i.id
			""")
	List<SentInviteView> findSent(@Param("roomIds") Collection<Long> roomIds, @Param("owner") Long owner);

	// 초대 줄을 지운다. 그 사이 지워졌어도 오류 없이 지나가도록 한 문장으로 지운다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from RoomInvite i where i.id = :id")
	int deleteInvite(@Param("id") Long id);

	// 방의 초대 줄을 지운다. 지운 줄 수(0이면 없는 초대).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from RoomInvite i where i.id = :id and i.roomId = :roomId")
	int deleteByIdAndRoomId(@Param("id") Long id, @Param("roomId") Long roomId);

	// 내가 받은 초대 줄을 지운다. 지운 줄 수(0이면 없는 초대).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from RoomInvite i where i.id = :id and i.userId = :userId")
	int deleteByIdAndUserId(@Param("id") Long id, @Param("userId") Long userId);

	// 회원이 받은 초대를 모두 지운다(탈퇴).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from RoomInvite i where i.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

}
