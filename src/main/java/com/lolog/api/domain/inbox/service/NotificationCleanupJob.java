package com.lolog.api.domain.inbox.service;

import com.lolog.api.domain.notification.repository.NotificationRepository;
import com.lolog.api.global.util.SafeLog;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// 한 시간마다 30일이 지난 알림을 지운다(inbox.md 1장). 지우기 전에도 목록에는 30일 안의 것만 보인다.
@Component
public class NotificationCleanupJob {

	private static final Logger log = LoggerFactory.getLogger(NotificationCleanupJob.class);

	private final NotificationRepository notifications;

	// 알림 리포지토리를 받는다.
	public NotificationCleanupJob(NotificationRepository notifications) {
		this.notifications = notifications;
	}

	// 지난 알림을 지우고, 실패하면 로그만 남긴다.
	@Scheduled(fixedDelayString = "${lolog.inbox.cleanup-delay-ms:3600000}",
			initialDelayString = "${lolog.inbox.cleanup-initial-delay-ms:60000}")
	public void run() {
		try {
			int deleted = notifications.deleteCreatedBefore(OffsetDateTime.now().minusDays(InboxService.KEEP_DAYS));
			if (deleted > 0) {
				log.info("30일 지난 알림 {}개를 지웠습니다.", deleted);
			}
		}
		catch (Exception e) {
			log.warn("지난 알림을 지우지 못했습니다. 다음 시간에 다시 합니다.", SafeLog.withoutMessages(e));
		}
	}

}
