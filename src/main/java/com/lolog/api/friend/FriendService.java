package com.lolog.api.friend;

import com.lolog.api.common.ApiException;
import com.lolog.api.friend.FriendDtos.BlockItem;
import com.lolog.api.friend.FriendDtos.BlockList;
import com.lolog.api.friend.FriendDtos.FriendItem;
import com.lolog.api.friend.FriendDtos.FriendList;
import com.lolog.api.friend.FriendDtos.FriendRequestList;
import com.lolog.api.friend.FriendDtos.FriendRequestResponse;
import com.lolog.api.friend.FriendDtos.FriendUser;
import com.lolog.api.notification.PushService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// 친구 요청·수락, 친구 목록·끊기, 차단(friends.md, DB는 db.md V5).
// 두 사람 사이의 친구 요청과 친구는 friendships 한 줄이다(uk_friendships_pair). 거절·취소·끊기·차단은 줄을 지운다.
// 두 사람의 관계를 바꿀 때는 두 사람의 프로필 줄을 회원 ID 순서로 잠근다. 서로 동시에 요청하거나
// 친구 100명 제한을 동시에 넘기는 경우를 막는다.
@Service
public class FriendService {

	static final int FRIEND_LIMIT = 100;

	private static final String PENDING = "PENDING";
	private static final String ACCEPTED = "ACCEPTED";

	private final JdbcTemplate jdbc;
	private final FriendCodeAttemptLimiter limiter;
	private final PushService push;

	public FriendService(JdbcTemplate jdbc, FriendCodeAttemptLimiter limiter, PushService push) {
		this.jdbc = jdbc;
		this.limiter = limiter;
		this.push = push;
	}

	private record Friendship(long id, long requesterId, long addresseeId, String status, OffsetDateTime createdAt) {
	}

	// 친구 코드로 요청한다. 상대가 이미 나에게 요청해 뒀으면 새 줄 없이 그 줄을 ACCEPTED로 바꾼다(푸시 없음).
	@Transactional
	public FriendRequestResponse sendRequest(long me, String friendCode) {
		String code = friendCode == null ? "" : friendCode.strip().toUpperCase(Locale.ROOT);
		if (code.length() != ProfileService.CODE_LENGTH) {
			throw ApiException.invalid("friendCode", "친구 코드 8자리를 입력해 주세요.");
		}
		requireNickname(me);
		limiter.check(me);
		long target = jdbc.queryForList("select user_id from user_profiles where friend_code = ?", Long.class, code)
			.stream()
			.findFirst()
			.orElseThrow(FriendService::codeNotFound);
		if (target == me) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_FRIEND_SELF", "내 친구 코드는 넣을 수 없어요.");
		}
		lockPair(me, target);
		// 상대가 나를 차단했으면 없는 코드와 똑같이 답한다. 차단됐다는 걸 알 수 없게 한다.
		if (blocked(target, me)) {
			throw codeNotFound();
		}
		if (blocked(me, target)) {
			throw new ApiException(HttpStatus.CONFLICT, "USER_BLOCKED", "차단한 사람이에요. 차단을 풀고 요청해 주세요.");
		}
		Optional<Friendship> existing = findPair(me, target);
		if (existing.isPresent() && ACCEPTED.equals(existing.get().status())) {
			throw new ApiException(HttpStatus.CONFLICT, "ALREADY_FRIENDS", "이미 친구예요.");
		}
		if (existing.isPresent() && existing.get().requesterId() == me) {
			throw new ApiException(HttpStatus.CONFLICT, "FRIEND_REQUEST_EXISTS", "이미 요청을 보냈어요.");
		}
		checkLimit(me, target);
		FriendUser user = friendUser(target);
		if (existing.isPresent()) {
			Friendship received = existing.get();
			accept(received.id());
			return new FriendRequestResponse(received.id(), "SENT", user, ACCEPTED, received.createdAt());
		}
		Friendship created = jdbc.queryForObject("""
				insert into friendships (requester_id, addressee_id) values (?, ?)
				returning id, requester_id, addressee_id, status, created_at
				""", (rs, n) -> friendship(rs), me, target);
		push.notify(target, PushService.friendRequest(created.id()));
		return new FriendRequestResponse(created.id(), "SENT", user, PENDING, created.createdAt());
	}

	// direction은 received(내가 받음)·sent(내가 보냄). 최신순.
	@Transactional(readOnly = true)
	public FriendRequestList requests(long me, String direction) {
		boolean received;
		if ("received".equals(direction)) {
			received = true;
		}
		else if ("sent".equals(direction)) {
			received = false;
		}
		else {
			throw ApiException.invalid("direction", "direction은 received 또는 sent예요.");
		}
		String mine = received ? "addressee_id" : "requester_id";
		String other = received ? "requester_id" : "addressee_id";
		List<FriendRequestResponse> list = jdbc.query("""
				select f.id, f.created_at, p.user_id, p.nickname, p.friend_code
				from friendships f join user_profiles p on p.user_id = f.%s
				where f.%s = ? and f.status = 'PENDING'
				order by f.created_at desc, f.id desc
				""".formatted(other, mine),
				(rs, n) -> new FriendRequestResponse(rs.getLong("id"), received ? "RECEIVED" : "SENT",
						new FriendUser(rs.getLong("user_id"), rs.getString("nickname"), rs.getString("friend_code")),
						PENDING, rs.getObject("created_at", OffsetDateTime.class)),
				me);
		return new FriendRequestList(list);
	}

	// 내가 받은 요청만 수락할 수 있다. 보낸 사람이 수락하려 하면 없는 요청과 같은 404다.
	@Transactional
	public FriendItem acceptRequest(long me, long requestId) {
		long requester = receivedRequest(me, requestId).requesterId();
		requireNickname(me);
		lockPair(me, requester);
		// 잠그기 전에 상대가 취소했을 수 있어 다시 읽는다.
		receivedRequest(me, requestId);
		checkLimit(me, requester);
		accept(requestId);
		return friendItems(me, "and f.id = ?", requestId).getFirst();
	}

	// 받은 요청 거절, 보낸 요청 취소. 상대에게 알리지 않는다.
	@Transactional
	public void deleteRequest(long me, long requestId) {
		int deleted = jdbc.update(
				"delete from friendships where id = ? and status = 'PENDING' and (requester_id = ? or addressee_id = ?)",
				requestId, me, me);
		if (deleted == 0) {
			throw requestNotFound();
		}
	}

	// 최근에 친구가 된 사람이 위. 친구가 100명까지라 한 번에 다 준다.
	@Transactional(readOnly = true)
	public FriendList friends(long me) {
		return new FriendList(friendItems(me, "", null));
	}

	// 친구가 아니어도 204다.
	@Transactional
	public void unfriend(long me, long userId) {
		jdbc.update("""
				delete from friendships where status = 'ACCEPTED'
				and ((requester_id = ? and addressee_id = ?) or (requester_id = ? and addressee_id = ?))
				""", me, userId, userId, me);
	}

	// 친구이거나 요청이 오간 사람만 차단할 수 있다. 차단하면 두 사람 사이의 친구 관계와 요청을 지운다.
	// 이미 차단했으면 그대로 204다.
	@Transactional
	public void block(long me, long userId) {
		if (userId == me) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "CANNOT_FRIEND_SELF", "나는 차단할 수 없어요.");
		}
		lockPair(me, userId);
		if (blocked(me, userId)) {
			return;
		}
		Friendship pair = findPair(me, userId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "차단할 수 있는 사람이 아니에요."));
		jdbc.update("delete from friendships where id = ?", pair.id());
		jdbc.update("insert into user_blocks (blocker_id, blocked_id) values (?, ?) on conflict do nothing", me, userId);
	}

	// 차단한 사람 목록(최신순).
	@Transactional(readOnly = true)
	public BlockList blocks(long me) {
		return new BlockList(jdbc.query("""
				select b.blocked_id, b.created_at, p.nickname, p.friend_code
				from user_blocks b join user_profiles p on p.user_id = b.blocked_id
				where b.blocker_id = ?
				order by b.created_at desc, b.blocked_id desc
				""", (rs, n) -> new BlockItem(rs.getLong("blocked_id"), rs.getString("nickname"),
				rs.getString("friend_code"), rs.getObject("created_at", OffsetDateTime.class)), me));
	}

	// 차단을 풀어도 친구로 돌아가지 않는다. 차단한 적 없어도 204다.
	@Transactional
	public void unblock(long me, long userId) {
		jdbc.update("delete from user_blocks where blocker_id = ? and blocked_id = ?", me, userId);
	}

	// 닉네임이 없으면(프로필이 없는 경우 포함) 요청을 보내거나 수락할 수 없다.
	private void requireNickname(long userId) {
		List<String> nickname = jdbc.queryForList("select nickname from user_profiles where user_id = ?", String.class,
				userId);
		if (nickname.isEmpty() || nickname.getFirst() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "PROFILE_REQUIRED", "닉네임을 먼저 정해 주세요.");
		}
	}

	// 두 사람의 프로필 줄을 회원 ID 순서로 잠근다(교착 막기). 프로필이 없는 쪽은 잠그지 않는다.
	private void lockPair(long a, long b) {
		jdbc.queryForList("select user_id from user_profiles where user_id in (?, ?) order by user_id for update",
				Long.class, Math.min(a, b), Math.max(a, b));
	}

	private boolean blocked(long blocker, long blockedUser) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists (select 1 from user_blocks where blocker_id = ? and blocked_id = ?)", Boolean.class,
				blocker, blockedUser));
	}

	private Optional<Friendship> findPair(long a, long b) {
		return jdbc.query("""
				select id, requester_id, addressee_id, status, created_at from friendships
				where (requester_id = ? and addressee_id = ?) or (requester_id = ? and addressee_id = ?)
				""", (rs, n) -> friendship(rs), a, b, b, a).stream().findFirst();
	}

	private Friendship receivedRequest(long me, long requestId) {
		return jdbc.query("""
				select id, requester_id, addressee_id, status, created_at from friendships
				where id = ? and addressee_id = ? and status = 'PENDING'
				""", (rs, n) -> friendship(rs), requestId, me)
			.stream()
			.findFirst()
			.orElseThrow(FriendService::requestNotFound);
	}

	// 나나 상대의 친구가 이미 100명이면 409.
	private void checkLimit(long a, long b) {
		for (long userId : new long[] { a, b }) {
			Long count = jdbc.queryForObject(
					"select count(*) from friendships where status = 'ACCEPTED' and (requester_id = ? or addressee_id = ?)",
					Long.class, userId, userId);
			if (count != null && count >= FRIEND_LIMIT) {
				throw new ApiException(HttpStatus.CONFLICT, "FRIEND_LIMIT", "친구는 100명까지예요.");
			}
		}
	}

	// 친구가 된 시각은 ACCEPTED로 바꾼 updated_at이다.
	private void accept(long friendshipId) {
		jdbc.update("update friendships set status = 'ACCEPTED', updated_at = current_timestamp where id = ?",
				friendshipId);
	}

	private FriendUser friendUser(long userId) {
		return jdbc.queryForObject("select user_id, nickname, friend_code from user_profiles where user_id = ?",
				(rs, n) -> new FriendUser(rs.getLong("user_id"), rs.getString("nickname"), rs.getString("friend_code")),
				userId);
	}

	// 내 친구 목록. extra는 더 거를 조건(예: 친구 관계 ID 하나).
	private List<FriendItem> friendItems(long me, String extra, Long arg) {
		String sql = """
				select f.updated_at, p.user_id, p.nickname, p.friend_code
				from friendships f
				join user_profiles p on p.user_id = case when f.requester_id = ? then f.addressee_id else f.requester_id end
				where f.status = 'ACCEPTED' and (f.requester_id = ? or f.addressee_id = ?) %s
				order by f.updated_at desc, f.id desc
				""".formatted(extra);
		Object[] args = arg == null ? new Object[] { me, me, me } : new Object[] { me, me, me, arg };
		return jdbc.query(sql, (rs, n) -> new FriendItem(rs.getLong("user_id"), rs.getString("nickname"),
				rs.getString("friend_code"), rs.getObject("updated_at", OffsetDateTime.class), null), args);
	}

	private static Friendship friendship(java.sql.ResultSet rs) throws java.sql.SQLException {
		return new Friendship(rs.getLong("id"), rs.getLong("requester_id"), rs.getLong("addressee_id"),
				rs.getString("status"), rs.getObject("created_at", OffsetDateTime.class));
	}

	private static ApiException codeNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "FRIEND_CODE_NOT_FOUND", "친구 코드를 찾을 수 없어요.");
	}

	private static ApiException requestNotFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "FRIEND_REQUEST_NOT_FOUND", "친구 요청을 찾을 수 없어요.");
	}

}
