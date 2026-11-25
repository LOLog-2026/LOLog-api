package com.lolog.api.global.transaction;

import com.lolog.api.global.util.SafeLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 트랜잭션이 커밋된 뒤에 할 일(큐에 넣기, 푸시, 구간 음성 자르기, 저장소 파일 지우기). 트랜잭션 밖이면 바로 한다.
// 커밋 뒤 할 일은 모두 여기로 맡긴다. 트랜잭션 동기화를 직접 쓰는 곳은 이 클래스 하나다.
// 이미 커밋된 뒤라 실패해도 요청을 실패로 돌리지 않고 로그만 남긴다(500을 주면 앱이 다시 보내 같은 일이 두 번 생긴다).
// 그래서 꼭 해야 하는 일은 따로 다시 하는 길이 있어야 한다(큐는 republishPending, 자르기는 AudioRetentionJob, 지우기는 StorageDeletions).
// 커밋 뒤에 DB를 쓰려면 새 트랜잭션(REQUIRES_NEW)으로 해야 한다. 같은 트랜잭션(REQUIRED)은 이미 끝난 트랜잭션에 끼어 저장되지 않는다.
public final class AfterCommit {

	private static final Logger log = LoggerFactory.getLogger(AfterCommit.class);

	private AfterCommit() {
	}

	/**
	 * 커밋 뒤에 할 일을 등록한다.
	 *
	 * @param work 커밋 뒤에 할 일. 실패해도 예외를 밖으로 던지지 않고 로그만 남긴다
	 */
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
