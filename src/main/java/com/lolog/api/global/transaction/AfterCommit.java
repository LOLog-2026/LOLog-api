package com.lolog.api.global.transaction;

import com.lolog.api.global.util.SafeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 트랜잭션이 커밋된 뒤에 할 일(저장소 파일 지우기 등). 트랜잭션 밖이면 바로 한다.
// 이미 커밋된 뒤라 실패해도 요청을 실패로 돌리지 않고 로그만 남긴다.
public final class AfterCommit {

	private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

	private AfterCommit() {
	}

	// 커밋 뒤에 할 일을 등록한다.
	public static void run(Runnable work) {
		Runnable quiet = () -> {
			try {
				work.run();
			}
			catch (Exception e) {
				log.warn("커밋 뒤 작업을 하지 못했습니다.", SafeLog.withoutMessages(e));
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					quiet.run();
				}
			});
		}
		else {
			quiet.run();
		}
	}

}
