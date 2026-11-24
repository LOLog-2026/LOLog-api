package com.lolog.api.domain.room.repository;

import com.lolog.api.domain.room.entity.RoomMember;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 방 멤버 DB 조회.
public interface RoomMemberRepository extends JpaRepository<RoomMember, RoomMember.Key> {

	// 멤버로 넣는다. 들어온 시각은 문장 실행 시각(clock_timestamp)이다.
	// 새 줄만 넣어 이미 읽은 엔티티가 낡지 않으므로 영속성 컨텍스트를 비우지 않는다(뒤에 바꾼 엔티티가 저장되게).
	@Modifying(flushAutomatically = true)
	@Query(value = "insert into room_members (room_id, user_id, created_at) values (:roomId, :userId, clock_timestamp())",
			nativeQuery = true)
	void insert(@Param("roomId") Long roomId, @Param("userId") Long userId);

	// 지금 이 방의 멤버인지 본다.
	boolean existsByRoomIdAndUserId(Long roomId, Long userId);

	// 방의 멤버 수.
	long countByRoomId(Long roomId);

	// 방에서 나 말고 남은 멤버 수.
	long countByRoomIdAndUserIdNot(Long roomId, Long userId);

	// 회원이 들어간 방 수.
	long countByUserId(Long userId);

	// 방의 멤버 ID.
	@Query("select m.userId from RoomMember m where m.roomId = :roomId")
	List<Long> findUserIdsByRoomId(@Param("roomId") Long roomId);

	// 방에서 나 말고 남은 멤버 ID.
	@Query("select m.userId from RoomMember m where m.roomId = :roomId and m.userId <> :me")
	List<Long> findOtherUserIds(@Param("roomId") Long roomId, @Param("me") Long me);

	// 여러 방의 멤버. 들어온 순서대로.
	List<RoomMember> findByRoomIdInOrderByCreatedAtAscUserIdAsc(Collection<Long> roomIds);

	// 들어간 방에 마지막으로 본 뒤 다른 사람이 남긴 대화 줄이 있는지 본다.
	@Query("""
			select case when count(x) > 0 then true else false end from RoomMember m
			join RoomMessage x on x.roomId = m.roomId and x.userId <> m.userId and x.createdAt > m.lastReadAt
			where m.userId = :me
			""")
	boolean existsUnread(@Param("me") Long me);

	// 대화를 지금(문장 실행 시각) 봤다고 남긴다. 대화 줄의 created_at과 같은 시계를 쓴다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query(value = "update room_members set last_read_at = clock_timestamp() where room_id = :roomId and user_id = :userId",
			nativeQuery = true)
	int markRead(@Param("roomId") Long roomId, @Param("userId") Long userId);

	// 멤버 줄을 지운다. 지운 줄 수(0이면 멤버가 아님). 같은 요청이 겹쳐도 오류 없이 지우도록 한 문장으로 지운다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from RoomMember m where m.roomId = :roomId and m.userId = :userId")
	int deleteMember(@Param("roomId") Long roomId, @Param("userId") Long userId);

	// 회원의 멤버 줄을 모두 지운다(탈퇴).
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("delete from RoomMember m where m.userId = :userId")
	int deleteAllByUserId(@Param("userId") Long userId);

	// 방 화면에 보일 사람들을 한 번에 읽는다. roomId가 null이면(방 목록·초대) 방장·멤버는 모두 false다.
	// 정지는 기한이 지나면 정지 아님으로 본다(인증 서비스가 따로 알리지 않음). 프로필이 없는 회원은 빠진다.
	@Query("""
			select new com.lolog.api.domain.room.repository.RoomPersonView(p.userId, p.nickname, p.characterThumbKey,
			    case when exists (select r.id from Room r where r.id = :roomId and r.ownerId = p.userId) then true else false end,
			    case when exists (select m.userId from RoomMember m where m.roomId = :roomId and m.userId = p.userId)
			         then true else false end,
			    (select f.status from Friendship f where f.requesterId = :me and f.addresseeId = p.userId),
			    (select f.status from Friendship f where f.requesterId = p.userId and f.addresseeId = :me),
			    case when exists (select b.blockerId from UserBlock b where b.blockerId = :me and b.blockedId = p.userId)
			         then true else false end,
			    case when exists (select s.userId from UserSuspension s where s.userId = p.userId
			         and (s.suspendedUntil is null or s.suspendedUntil > current_timestamp)) then true else false end)
			from UserProfile p where p.userId in :userIds
			""")
	List<RoomPersonView> findPeople(@Param("me") Long me, @Param("roomId") Long roomId,
			@Param("userIds") Collection<Long> userIds);

}
