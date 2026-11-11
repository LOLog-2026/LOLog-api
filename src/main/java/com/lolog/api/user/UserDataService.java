package com.lolog.api.user;

import com.lolog.api.room.RoomService;
import com.lolog.api.storage.ImageStorage;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 탈퇴한 사용자의 데이터를 모두 지운다(FR-AUTH-05). lolog-auth가 DELETE /internal/users/{id}로 부른다.
// 같은 요청을 여러 번 불러도 된다. 이미지를 지우지 못하면 예외(500)로 답하고, lolog-auth가 204가 올 때까지 다시 부른다(LOLog-docs PR #28).
@Service
public class UserDataService {

	private final JdbcTemplate jdbc;
	private final ImageStorage storage;
	private final TransactionTemplate tx;

	public UserDataService(JdbcTemplate jdbc, ImageStorage storage, TransactionTemplate tx) {
		this.jdbc = jdbc;
		this.storage = storage;
		this.tx = tx;
	}

	public void delete(long userId) {
		// 한 트랜잭션에서 지운다. 화자·대사·묶음은 DB가 함께 지운다(ON DELETE CASCADE).
		// 진행 중인 감지·만화 작업도 지워지므로 워커의 다음 요청은 404·400이 된다(작업 취소).
		tx.executeWithoutResult(status -> {
			jdbc.update("delete from jobs where user_id = ?", userId);
			jdbc.update("delete from comics where user_id = ?", userId);
			// 카드 영상(video.md 7장). 파일은 아래 users/{userId}/ 아래와 함께 지워진다.
			jdbc.update("delete from videos where user_id = ?", userId);
			jdbc.update("delete from cards where user_id = ?", userId);
			jdbc.update("delete from recordings where user_id = ?", userId);
			jdbc.update("delete from device_tokens where user_id = ?", userId);
			// 알림 목록과 읽음·새 소식 기록(inbox.md 6장)
			jdbc.update("delete from notifications where user_id = ?", userId);
			jdbc.update("delete from notice_reads where user_id = ?", userId);
			jdbc.update("delete from badge_reads where user_id = ?", userId);
			// 보낸 오류 신고·의견은 남기고 보낸 사람만 비운다(admin.md 5장, FR-AUTH-05의 예외). 정지 표시는 지운다.
			jdbc.update("update feedback set user_id = null, updated_at = current_timestamp where user_id = ?", userId);
			jdbc.update("delete from user_suspensions where user_id = ?", userId);
			// 다른 사람 카드에 붙은 이 회원의 프로필 캐릭터를 뺀다(characters.md 3장). 이 회원의 카드는 위에서 지워졌다.
			jdbc.update("update card_speakers set character_type = null, character_id = null, updated_at = current_timestamp "
					+ "where character_type = 'PROFILE' and character_id = ?", String.valueOf(userId));
			// 방(rooms.md): 이 회원이 올린 만화는 위에서 만화를 지울 때 외래 키로 함께 지워졌다(좋아요·대화 줄 포함).
			// 남긴 좋아요·댓글과 멤버 줄을 지우고, 멤버가 남지 않은 방을 지운다.
			jdbc.update("delete from room_post_likes where user_id = ?", userId);
			jdbc.update("delete from room_messages where user_id = ?", userId);
			// 받은 초대를 지우고, 방장이던 방은 가장 먼저 들어온 멤버에게 넘긴다(db.md V9 초안).
			jdbc.update("delete from room_invites where user_id = ?", userId);
			List<Long> owned = jdbc.queryForList("select id from rooms where owner_id = ?", Long.class, userId);
			jdbc.update("delete from room_members where user_id = ?", userId);
			jdbc.update("delete from rooms r where not exists (select 1 from room_members m where m.room_id = r.id)");
			owned.forEach(roomId -> RoomService.removeEmptyOrHandOver(jdbc, roomId));
			// 친구 관계·요청·차단은 이 회원이 어느 쪽이든 지운다. 친구들 목록에서는 이 회원이 빠지기만 한다(friends.md).
			jdbc.update("delete from friendships where requester_id = ? or addressee_id = ?", userId, userId);
			jdbc.update("delete from user_blocks where blocker_id = ? or blocked_id = ?", userId, userId);
			jdbc.update("delete from user_profiles where user_id = ?", userId);
		});
		// DB를 지운 뒤 이미지를 지운다. 실패하면 예외가 그대로 나가 500이 되고, 다시 부르면 남은 이미지만 지운다.
		storage.deleteUser(userId);
	}

}
