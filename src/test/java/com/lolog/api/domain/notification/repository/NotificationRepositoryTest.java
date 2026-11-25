package com.lolog.api.domain.notification.repository;

import static com.lolog.api.support.RepositoryRows.at;
import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.domain.notification.entity.Notification;
import com.lolog.api.support.RepositoryRows;
import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("NotificationRepository")
class NotificationRepositoryTest extends RepositoryTest {

	private static final long ME = 1L;
	private static final long OTHER = 2L;

	@Autowired
	private NotificationRepository notifications;

	@Autowired
	private JdbcTemplate jdbc;

	private RepositoryRows rows;

	@BeforeEach
	void setUp() {
		rows = new RepositoryRows(jdbc);
	}

	@Test
	@DisplayName("알림을 남기면 값이 JSON으로 저장되고 시각이 채워진다")
	void 알림을_남긴다() {
		// when
		notifications.insert(ME, "COMIC_DONE", "{\"comicId\": 7}");

		// then
		List<Notification> saved = notifications.findAll();
		assertThat(saved).singleElement().satisfies(n -> {
			assertThat(n.getType()).isEqualTo("COMIC_DONE");
			assertThat(n.getCreatedAt()).isNotNull();
			assertThat(n.getReadAt()).isNull();
		});
		assertThat(jdbc.queryForObject("select data ->> 'comicId' from notifications", String.class)).isEqualTo("7");
	}

	@Test
	@DisplayName("모두 읽음은 내 안 읽은 알림만 바꾸고, 이미 읽은 알림의 읽은 시각은 그대로 둔다")
	void 모두_읽음은_안_읽은_알림만_바꾼다() {
		// given
		long unread1 = rows.notification(ME, "COMIC_DONE", at(0), null);
		long unread2 = rows.notification(ME, "COMIC_DONE", at(1), null);
		long alreadyRead = rows.notification(ME, "COMIC_DONE", at(2), at(3));
		long others = rows.notification(OTHER, "COMIC_DONE", at(0), null);
		OffsetDateTime now = at(10);

		// when
		int changed = notifications.markAllRead(ME, now);

		// then
		assertThat(changed).isEqualTo(2);
		assertThat(readAt(unread1)).isAtSameInstantAs(now);
		assertThat(readAt(unread2)).isAtSameInstantAs(now);
		assertThat(readAt(alreadyRead)).isAtSameInstantAs(at(3));
		assertThat(readAt(others)).isNull();
	}

	@Test
	@DisplayName("새 알림은 since보다 뒤에 생긴 안 읽은 것만이고, since와 같은 시각은 뺀다")
	void since와_같은_시각의_알림은_새_알림이_아니다() {
		// given
		rows.notification(ME, "COMIC_DONE", at(5), null);
		rows.notification(ME, "COMIC_DONE", at(6), at(7));

		// when & then
		assertThat(notifications.existsByUserIdAndReadAtIsNullAndCreatedAtAfter(ME, at(5))).isFalse();
		assertThat(notifications.existsByUserIdAndReadAtIsNullAndCreatedAtAfter(ME, at(4))).isTrue();
		assertThat(notifications.existsByUserIdAndReadAtIsNullAndCreatedAtAfter(OTHER, at(0))).isFalse();
	}

	@Test
	@DisplayName("종류로 찾을 때 since와 같은 시각의 알림은 뺀다")
	void 종류로_찾을_때_since와_같은_시각은_뺀다() {
		// given
		rows.notification(ME, "COMIC_DONE", at(5), null);

		// when & then
		assertThat(notifications.existsByUserIdAndTypeIn(ME, List.of("COMIC_DONE", "FRIEND"))).isTrue();
		assertThat(notifications.existsByUserIdAndTypeIn(ME, List.of("FRIEND"))).isFalse();
		assertThat(notifications.existsByUserIdAndTypeInAndCreatedAtAfter(ME, List.of("COMIC_DONE"), at(5))).isFalse();
		assertThat(notifications.existsByUserIdAndTypeInAndCreatedAtAfter(ME, List.of("COMIC_DONE"), at(4))).isTrue();
	}

	@Test
	@DisplayName("before보다 먼저 생긴 알림만 지우고 before와 같은 시각은 남긴다")
	void before와_같은_시각의_알림은_남긴다() {
		// given
		rows.notification(ME, "COMIC_DONE", at(4), null);
		long same = rows.notification(OTHER, "COMIC_DONE", at(5), null);
		long later = rows.notification(ME, "COMIC_DONE", at(6), null);

		// when
		int deleted = notifications.deleteCreatedBefore(at(5));

		// then
		assertThat(deleted).isEqualTo(1);
		assertThat(notifications.findAll()).extracting(Notification::getId).containsExactlyInAnyOrder(same, later);
	}

	@Test
	@DisplayName("탈퇴하면 그 회원의 알림만 지운다")
	void 탈퇴하면_그_회원의_알림만_지운다() {
		// given
		rows.notification(ME, "COMIC_DONE", at(0), null);
		long others = rows.notification(OTHER, "COMIC_DONE", at(0), null);

		// when & then
		assertThat(notifications.deleteAllByUserId(ME)).isEqualTo(1);
		assertThat(notifications.findAll()).extracting(Notification::getId).containsExactly(others);
	}

	private OffsetDateTime readAt(long id) {
		return jdbc.queryForObject("select read_at from notifications where id = ?", OffsetDateTime.class, id);
	}

}
