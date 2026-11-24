package com.lolog.api.global.storage;

import com.lolog.api.global.storage.entity.StorageDeletion;
import com.lolog.api.global.storage.repository.StorageDeletionRepository;
import com.lolog.api.global.transaction.AfterCommit;
import com.lolog.api.global.util.SafeLog;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.awscore.exception.AwsServiceException;

// 저장소 파일 지우기(storage_deletions). 데이터를 바꾸는 트랜잭션 안에서 부르면 지울 줄이 그 변경과 함께 커밋·되돌림된다.
// 커밋 뒤에 바로 한 번 지우고, 실패하면 줄이 남아 주기 작업이 다시 지운다. 없는 파일을 지워도 성공이다.
// 정해진 키(users/{userId}/profile/photo 등)에 파일을 다시 올리는 쪽은 올리기 전에 cancel로 그 키의 지우기를 뺀다.
@Component
public class StorageDeletions {

	private static final Logger log = LoggerFactory.getLogger(StorageDeletions.class);

	// 한 번에 다시 지울 줄 수. 남은 줄은 다음 차례에 지운다.
	static final int RETRY_BATCH = 100;

	private final StorageDeletionRepository deletions;
	private final ImageStorage storage;
	// 커밋 뒤에는 끝난 트랜잭션에 끼지 않게 새 트랜잭션으로 지운다.
	private final TransactionTemplate tx;

	public StorageDeletions(StorageDeletionRepository deletions, ImageStorage storage,
			PlatformTransactionManager transactionManager) {
		this.deletions = deletions;
		this.storage = storage;
		this.tx = new TransactionTemplate(transactionManager);
		this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	// 키 하나를 지운다. 부르는 쪽의 트랜잭션이 커밋되면 지운다(트랜잭션 밖이면 바로).
	@Transactional
	public void schedule(String key) {
		add(key, false);
	}

	// prefix 아래를 모두 지운다. 부르는 쪽의 트랜잭션이 커밋되면 지운다(트랜잭션 밖이면 바로).
	@Transactional
	public void schedulePrefix(String prefix) {
		add(prefix, true);
	}

	// 그 키에 파일을 다시 올리기 전에 부른다. 아직 못 지운 예전 지우기가 새 파일을 지우지 않게 뺀다.
	// 지우는 중인 줄이면 끝날 때까지 기다린 뒤에 돌아와서, 그 뒤에 올린 파일은 지워지지 않는다. prefix 줄은 보지 않는다.
	@Transactional
	public void cancel(String key) {
		List<StorageDeletion> pending = deletions.findKeyForUpdate(key);
		if (!pending.isEmpty()) {
			deletions.deleteAll(pending);
		}
	}

	// 못 지운 줄을 다시 지운다.
	@Scheduled(fixedDelayString = "${lolog.storage.deletion-retry-delay-ms:600000}",
			initialDelayString = "${lolog.storage.deletion-retry-initial-delay-ms:60000}")
	public void retry() {
		try {
			deletions.findRetryIds(Limit.of(RETRY_BATCH)).forEach(this::attempt);
		}
		catch (Exception e) {
			log.warn("저장소 파일 다시 지우기를 하지 못했습니다. 다음 차례에 다시 합니다.", SafeLog.withoutMessages(e));
		}
	}

	// 줄을 넣고 커밋 뒤 지우기를 맡긴다.
	private void add(String key, boolean prefix) {
		long id = deletions.save(new StorageDeletion(key, prefix)).getId();
		AfterCommit.run(() -> attempt(id));
	}

	// 줄 하나를 지운다. 지웠으면 줄을 빼고 true, 실패하면 횟수와 이유를 남기고 false.
	// 다른 쪽이 지우는 중이거나 이미 지웠으면 false.
	boolean attempt(long id) {
		Boolean deleted = tx.execute(status -> {
			StorageDeletion deletion = deletions.findByIdForUpdateSkipLocked(id).orElse(null);
			if (deletion == null) {
				return false;
			}
			try {
				if (deletion.isPrefix()) {
					storage.deletePrefix(deletion.getObjectKey());
				}
				else {
					storage.delete(deletion.getObjectKey());
				}
			}
			catch (RuntimeException e) {
				deletion.failed(reason(e));
				log.warn("저장소 파일을 지우지 못했습니다. 나중에 다시 지웁니다. id={}", id, SafeLog.withoutMessages(e));
				return false;
			}
			deletions.delete(deletion);
			return true;
		});
		return Boolean.TRUE.equals(deleted);
	}

	// 실패 이유. 예외 메시지는 남기지 않고 예외 종류와 저장소 오류 코드만 남긴다.
	static String reason(RuntimeException e) {
		if (e instanceof AwsServiceException aws) {
			String code = aws.awsErrorDetails() == null ? null : aws.awsErrorDetails().errorCode();
			return e.getClass().getSimpleName() + " " + aws.statusCode() + (code == null ? "" : " " + code);
		}
		return e.getClass().getSimpleName();
	}

}
