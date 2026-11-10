package com.lolog.api.user;

import com.lolog.api.storage.ImageStorage;
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
			jdbc.update("delete from cards where user_id = ?", userId);
			jdbc.update("delete from recordings where user_id = ?", userId);
			jdbc.update("delete from device_tokens where user_id = ?", userId);
			// 친구 관계·요청·차단은 이 회원이 어느 쪽이든 지운다. 친구들 목록에서는 이 회원이 빠지기만 한다(friends.md).
			jdbc.update("delete from friendships where requester_id = ? or addressee_id = ?", userId, userId);
			jdbc.update("delete from user_blocks where blocker_id = ? or blocked_id = ?", userId, userId);
			jdbc.update("delete from user_profiles where user_id = ?", userId);
		});
		// DB를 지운 뒤 이미지를 지운다. 실패하면 예외가 그대로 나가 500이 되고, 다시 부르면 남은 이미지만 지운다.
		storage.deleteUser(userId);
	}

}
