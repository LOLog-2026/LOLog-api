package com.lolog.api.comic;

import com.lolog.api.card.Card;
import com.lolog.api.card.CardRepository;
import com.lolog.api.common.ApiException;
import com.lolog.api.job.Job;
import com.lolog.api.job.JobRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

// 만화 요청과 만화 작업 실패. 둘 다 카드의 만들기 횟수(comic_count)를 바꾼다.
@Service
public class ComicService {

	private final CardRepository cards;
	private final ComicRepository comics;
	private final JobRepository jobs;
	private final ComicPublisher publisher;
	private final TransactionTemplate tx;

	public ComicService(CardRepository cards, ComicRepository comics, JobRepository jobs, ComicPublisher publisher,
			TransactionTemplate tx) {
		this.cards = cards;
		this.comics = comics;
		this.jobs = jobs;
		this.publisher = publisher;
		this.tx = tx;
	}

	public record Created(Long comicId, ComicStatus status) {
	}

	public Created request(long userId, long cardId) {
		Created[] created = new Created[1];
		Long jobId = tx.execute(status -> {
			// 카드 행을 잠가, 같은 카드의 요청·고치기·지우기가 한 번에 하나씩 일어나게 한다.
			Card card = cards.findByIdAndUserIdForUpdate(cardId, userId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "카드를 찾을 수 없습니다."));
			// 만드는 중인 만화가 먼저다. 횟수가 다 찼어도 그 만화가 실패하면 다시 만들 수 있기 때문이다(LOLog-docs PR #22).
			if (comics.existsByCardIdAndStatusIn(card.getId(), ComicStatus.IN_PROGRESS)) {
				throw inProgress();
			}
			if (cards.incrementComicCount(card.getId()) == 0) {
				throw new ApiException(HttpStatus.CONFLICT, "COMIC_LIMIT", "이 카드로는 더 만들 수 없습니다.");
			}
			Comic comic;
			try {
				comic = comics.saveAndFlush(new Comic(card.getId(), userId, card.getSummary()));
			}
			catch (DataIntegrityViolationException e) {
				// 부분 유니크(uk_comics_card_id_in_progress)가 막은 경우. 예외로 트랜잭션이 되돌아가 횟수 +1도 취소된다.
				throw inProgress();
			}
			created[0] = new Created(comic.getId(), comic.getStatus());
			return jobs.save(Job.comic(comic.getId(), userId)).getId();
		});
		publisher.publishQuietly(jobId);
		return created[0];
	}

	// 워커가 만화 작업의 실패를 알렸을 때. 같은 jobId가 다시 오면 무시하므로 횟수를 두 번 돌려주지 않는다.
	public void fail(long jobId, String reason) {
		tx.executeWithoutResult(status -> {
			Long comicId = jobs.findComicIdById(jobId)
				.orElseThrow(() -> ApiException.invalid(null, "작업을 찾을 수 없습니다."));
			// 잠금 순서는 이미지 받기(ComicWorkerService)와 같게 만화 → 작업이다. 순서가 다르면 둘이 겹칠 때 교착 상태가 된다.
			Comic comic = comics.findByIdForUpdate(comicId).orElseThrow();
			Job job = jobs.findByIdForUpdate(jobId).orElseThrow();
			if (job.isFinished()) {
				return;
			}
			job.fail(reason);
			comic.fail(reason);
			// 카드를 지웠으면(card_id가 null) 돌려줄 횟수가 없다.
			if (comic.getCardId() != null) {
				cards.decrementComicCount(comic.getCardId());
			}
			// 실패 푸시(COMIC_FAILED)는 알림 이슈(#8)에서 붙인다.
		});
	}

	private static ApiException inProgress() {
		return new ApiException(HttpStatus.CONFLICT, "COMIC_IN_PROGRESS", "이 카드로 만드는 중인 만화가 있습니다.");
	}

}
