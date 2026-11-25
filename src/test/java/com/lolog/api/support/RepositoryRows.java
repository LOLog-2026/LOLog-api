package com.lolog.api.support;

import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;

// 리포지토리 테스트에서 행을 SQL로 바로 넣는 도우미. 시각을 정해 넣어야 경계를 볼 수 있어 엔티티 대신 SQL을 쓴다.
public final class RepositoryRows {

	// 테스트의 기준 시각. 이 시각에서 몇 분씩 더하거나 빼서 쓴다.
	public static final OffsetDateTime T0 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

	private final JdbcTemplate jdbc;

	public RepositoryRows(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	public static OffsetDateTime at(int minutes) {
		return T0.plusMinutes(minutes);
	}

	public long room(String name, long ownerId, OffsetDateTime createdAt, OffsetDateTime lastActivityAt) {
		return jdbc.queryForObject("""
				insert into rooms (name, created_by, owner_id, created_at, last_activity_at)
				values (?, ?, ?, ?, ?) returning id
				""", Long.class, name, ownerId, ownerId, createdAt, lastActivityAt);
	}

	public void member(long roomId, long userId, OffsetDateTime createdAt, OffsetDateTime lastReadAt) {
		jdbc.update("insert into room_members (room_id, user_id, created_at, last_read_at) values (?, ?, ?, ?)", roomId,
				userId, createdAt, lastReadAt);
	}

	public long message(long roomId, long userId, String type, Long postId, String body, OffsetDateTime createdAt) {
		return jdbc.queryForObject("""
				insert into room_messages (room_id, user_id, message_type, post_id, body, created_at)
				values (?, ?, ?, ?, ?, ?) returning id
				""", Long.class, roomId, userId, type, postId, body, createdAt);
	}

	public long comic(long userId, String cardSummary, String imageKey) {
		return jdbc.queryForObject("""
				insert into comics (user_id, status, card_summary, image_key) values (?, 'DONE', ?, ?) returning id
				""", Long.class, userId, cardSummary, imageKey);
	}

	public long post(long roomId, long comicId, long userId, OffsetDateTime createdAt) {
		return jdbc.queryForObject("""
				insert into room_posts (room_id, comic_id, user_id, created_at) values (?, ?, ?, ?) returning id
				""", Long.class, roomId, comicId, userId, createdAt);
	}

	public void like(long postId, long userId) {
		jdbc.update("insert into room_post_likes (post_id, user_id) values (?, ?)", postId, userId);
	}

	public long invite(long roomId, long userId, long invitedBy, OffsetDateTime createdAt) {
		return jdbc.queryForObject("""
				insert into room_invites (room_id, user_id, invited_by, created_at) values (?, ?, ?, ?) returning id
				""", Long.class, roomId, userId, invitedBy, createdAt);
	}

	public void profile(long userId, String friendCode, String nickname) {
		jdbc.update("insert into user_profiles (user_id, friend_code, nickname) values (?, ?, ?)", userId, friendCode,
				nickname);
	}

	public long friendship(long requesterId, long addresseeId, String status, OffsetDateTime updatedAt) {
		return jdbc.queryForObject("""
				insert into friendships (requester_id, addressee_id, status, updated_at) values (?, ?, ?, ?) returning id
				""", Long.class, requesterId, addresseeId, status, updatedAt);
	}

	public void block(long blockerId, long blockedId, boolean hideInRooms) {
		jdbc.update("insert into user_blocks (blocker_id, blocked_id, hide_in_rooms) values (?, ?, ?)", blockerId,
				blockedId, hideInRooms);
	}

	public void suspension(long userId, OffsetDateTime until) {
		jdbc.update("insert into user_suspensions (user_id, suspended_until) values (?, ?)", userId, until);
	}

	public long notice(String title, OffsetDateTime createdAt) {
		return jdbc.queryForObject("insert into notices (title, body, created_at) values (?, ?, ?) returning id",
				Long.class, title, title + " 본문", createdAt);
	}

	public void noticeRead(long noticeId, long userId) {
		jdbc.update("insert into notice_reads (notice_id, user_id) values (?, ?)", noticeId, userId);
	}

	public long notification(long userId, String type, OffsetDateTime createdAt, OffsetDateTime readAt) {
		return jdbc.queryForObject("""
				insert into notifications (user_id, type, data, created_at, read_at)
				values (?, ?, cast('{}' as jsonb), ?, ?) returning id
				""", Long.class, userId, type, createdAt, readAt);
	}

}
