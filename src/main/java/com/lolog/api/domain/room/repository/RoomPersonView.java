package com.lolog.api.domain.room.repository;

import com.lolog.api.domain.friend.entity.FriendshipStatus;

// 방 화면에 보이는 사람 한 명(읽기 전용). 프로필, 그 방의 방장·멤버인지, 나와의 친구 요청 상태, 내가 차단했는지, 정지 중인지.
// sentStatus는 내가 보낸 요청, receivedStatus는 내가 받은 요청의 상태이고 없으면 null이다.
public record RoomPersonView(Long userId, String nickname, String thumbKey, boolean owner, boolean member,
		FriendshipStatus sentStatus, FriendshipStatus receivedStatus, boolean blocked, boolean suspended) {
}
