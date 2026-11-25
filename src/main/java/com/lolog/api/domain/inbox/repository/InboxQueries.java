package com.lolog.api.domain.inbox.repository;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

// 알림 목록 한 쪽. 공지와 알림을 한 줄로 이어(union all) 시각·종류·ID 순서로 자르는 쿼리라 JPQL로 쓰지 않고 여기서만 SQL을 쓴다.
// 탭에 따라 한쪽만 읽고 커서가 있을 때만 조건을 붙여 문장이 바뀐다.
@Repository
public class InboxQueries {

	/**
	 * 목록 한 줄. kind는 NOTICE·NOTIFICATION, 공지는 title·body, 알림은 type·data(JSON)가 있다.
	 *
	 * @param kind 항목 종류(NOTICE 또는 NOTIFICATION)
	 * @param id 공지 ID 또는 알림 ID
	 * @param createdAt 공지·알림이 생긴 시각
	 * @param read 이 회원이 읽었으면 true
	 * @param title 공지 제목(알림이면 null)
	 * @param body 공지 본문(알림이면 null)
	 * @param type 알림 종류(공지면 null)
	 * @param data 알림 값 JSON 문자열(공지면 null)
	 */
	public record Row(String kind, long id, OffsetDateTime createdAt, boolean read, String title, String body,
			String type, String data) {
	}

	/**
	 * 커서: 마지막으로 받은 항목의 시각·종류·ID
	 *
	 * @param createdAt 마지막으로 받은 항목의 시각
	 * @param kind 마지막으로 받은 항목의 종류(NOTICE 또는 NOTIFICATION)
	 * @param id 마지막으로 받은 항목의 ID
	 */
	public record Cursor(OffsetDateTime createdAt, String kind, long id) {
	}

	private final NamedParameterJdbcTemplate jdbc;

	/** DB를 받는다. */
	public InboxQueries(NamedParameterJdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 최근 것이 위. 같은 시각이면 알림, 공지사항 순이다. 알림은 since 뒤의 것만 읽는다.
	 *
	 * @param userId 목록을 보는 회원 ID
	 * @param notices 공지를 넣으면 true
	 * @param notifications 알림을 넣으면 true
	 * @param since 이 시각보다 뒤에 생긴 알림만 읽는다(공지에는 쓰지 않음)
	 * @param cursor 이 항목보다 뒤의 것부터 읽는다(null이면 처음부터)
	 * @param limit 읽을 최대 줄 수
	 * @return 목록 줄(최근 것이 위)
	 */
	public List<Row> page(long userId, boolean notices, boolean notifications, OffsetDateTime since, Cursor cursor,
			int limit) {
		MapSqlParameterSource args = new MapSqlParameterSource("me", userId).addValue("limit", limit)
			.addValue("since", since);
		String after = "";
		if (cursor != null) {
			args.addValue("cAt", cursor.createdAt()).addValue("cKind", cursor.kind()).addValue("cId", cursor.id());
			after = "where (t.created_at, t.kind, t.id) < (:cAt, :cKind, :cId)";
		}
		List<String> parts = new ArrayList<>();
		if (notices) {
			parts.add("""
					select 'NOTICE' as kind, n.id, n.created_at,
					       exists (select 1 from notice_reads r where r.notice_id = n.id and r.user_id = :me) as is_read,
					       n.title, n.body, null as type, null as data
					from notices n
					""");
		}
		if (notifications) {
			parts.add("""
					select 'NOTIFICATION' as kind, x.id, x.created_at, x.read_at is not null as is_read,
					       null as title, null as body, x.type, x.data::text as data
					from notifications x where x.user_id = :me and x.created_at > :since
					""");
		}
		String sql = "select * from (" + String.join(" union all ", parts) + ") t " + after
				+ " order by t.created_at desc, t.kind desc, t.id desc limit :limit";
		return jdbc.query(sql, args, (rs, n) -> new Row(rs.getString("kind"), rs.getLong("id"),
				rs.getObject("created_at", OffsetDateTime.class), rs.getBoolean("is_read"), rs.getString("title"),
				rs.getString("body"), rs.getString("type"), rs.getString("data")));
	}

}
