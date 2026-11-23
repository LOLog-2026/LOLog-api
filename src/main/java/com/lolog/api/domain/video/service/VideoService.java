package com.lolog.api.domain.video.service;

import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.notification.service.PushService;
import com.lolog.api.domain.video.dto.VideoDtos.VideoAccepted;
import com.lolog.api.domain.video.dto.VideoDtos.VideoPage;
import com.lolog.api.domain.video.dto.VideoDtos.VideoRef;
import com.lolog.api.domain.video.dto.VideoDtos.VideoResponse;
import com.lolog.api.domain.video.entity.Video;
import com.lolog.api.domain.video.entity.VideoStatus;
import com.lolog.api.domain.video.repository.VideoRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.transaction.AfterCommit;
import com.lolog.api.global.web.PageRequestParams;
import com.lolog.api.global.web.PageSort;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// 카드 영상 요청·목록·보기·지우기와 영상 작업 실패(video.md 2장). 만화(ComicService)와 같은 규칙이다.
// 카드 하나에 실패가 아닌 영상 2개까지(cards.video_count). 실패하면 횟수가 돌아오고, 지워도 돌아오지 않는다.
@Service
public class VideoService {

	static final int MAX_VIDEOS = 2;

	private final VideoRepository videos;
	private final CardRepository cards;
	private final JobRepository jobs;
	private final VideoPublisher publisher;
	private final ImageStorage storage;
	private final PushService push;
	private final TransactionTemplate tx;

	public VideoService(VideoRepository videos, CardRepository cards, JobRepository jobs, VideoPublisher publisher,
			ImageStorage storage, PushService push, TransactionTemplate tx) {
		this.videos = videos;
		this.cards = cards;
		this.jobs = jobs;
		this.publisher = publisher;
		this.storage = storage;
		this.push = push;
		this.tx = tx;
	}

	// 만드는 중인 영상이 먼저다(만화와 같음). 커밋한 뒤 3호 큐(lolog:video)에 넣는다.
	public VideoAccepted request(long userId, long cardId) {
		VideoAccepted[] created = new VideoAccepted[1];
		Long jobId = tx.execute(status -> {
			// 카드 행을 잠가, 같은 카드의 요청·고치기·지우기가 한 번에 하나씩 일어나게 한다.
			Card card = cards.findByIdAndUserIdForUpdate(cardId, userId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "카드를 찾을 수 없습니다."));
			if (videos.existsByCardIdAndStatusIn(card.getId(), VideoStatus.IN_PROGRESS)) {
				throw inProgress();
			}
			if (cards.incrementVideoCount(card.getId()) == 0) {
				throw new ApiException(HttpStatus.CONFLICT, "VIDEO_LIMIT", "이 카드로는 영상을 더 만들 수 없습니다.");
			}
			Video video;
			try {
				video = videos.saveAndFlush(new Video(card.getId(), userId, card.getSummary()));
			}
			catch (DataIntegrityViolationException e) {
				// 부분 유니크(uk_videos_card_id_in_progress)가 막은 경우. 예외로 트랜잭션이 되돌아가 횟수 +1도 취소된다.
				throw inProgress();
			}
			created[0] = new VideoAccepted(video.getId(), video.getStatus().name());
			return jobs.save(Job.video("VIDEO_VOICE", video.getId(), userId)).getId();
		});
		publisher.publishQuietly(jobId);
		return created[0];
	}

	// sort는 만화 목록과 같다(latest 기본, oldest). 커서는 마지막으로 받은 영상 ID라 다음 페이지도 같은 sort로 부른다.
	@Transactional(readOnly = true)
	public VideoPage list(long userId, String cursor, Integer size, String sort) {
		PageSort order = PageSort.of(sort);
		PageRequestParams page = PageRequestParams.of(cursor, size);
		Limit limit = Limit.of(page.size() + 1);
		List<Video> found = order == PageSort.OLDEST ? videos.findPageOldest(userId, page.before(), limit)
				: videos.findPage(userId, page.before(), limit);
		List<VideoResponse> items = new ArrayList<>(found.stream().map(this::response).toList());
		String next = null;
		if (items.size() > page.size()) {
			items = new ArrayList<>(items.subList(0, page.size()));
			next = String.valueOf(items.getLast().id());
		}
		return new VideoPage(items, next);
	}

	@Transactional(readOnly = true)
	public VideoResponse get(long userId, long videoId) {
		return videos.findByIdAndUserId(videoId, userId).map(this::response).orElseThrow(VideoService::notFound);
	}

	// 만드는 중이어도 지울 수 있다. 작업도 같이 지워져 워커의 다음 요청은 404·400이 된다(만화와 같음). 횟수는 돌아오지 않는다.
	@Transactional
	public void delete(long userId, long videoId) {
		Video video = videos.findByIdAndUserId(videoId, userId).orElseThrow(VideoService::notFound);
		videos.delete(video);
		AfterCommit.run(() -> storage.deletePrefix(ImageStorage.videoPrefix(userId, videoId)));
	}

	// 카드 자세히의 videos[]. 최근 것이 앞.
	@Transactional(readOnly = true)
	public List<VideoRef> refs(long cardId) {
		return videos.findByCardIdOrderByIdDesc(cardId)
			.stream()
			.map(v -> new VideoRef(v.getId(), v.getStatus().name()))
			.toList();
	}

	// 이 카드로 더 만들 수 있는 영상 수(0~2). 만화의 comicRemaining처럼 지운 영상도 세고 실패한 영상은 세지 않는다(정함 11-13).
	public int remaining(long cardId) {
		int used = cards.findById(cardId).map(Card::getVideoCount).orElse(0);
		return Math.max(0, MAX_VIDEOS - used);
	}

	public boolean hasRunningBeforeDrawing(long cardId) {
		return videos.existsByCardIdAndStatusIn(cardId, VideoStatus.BEFORE_DRAWING);
	}

	// 워커가 영상 작업(목소리·그림 어느 단계든)의 실패를 알렸을 때. 같은 jobId가 다시 오면 무시한다.
	public void fail(long jobId, String reason) {
		tx.executeWithoutResult(status -> {
			Long videoId = jobs.findVideoIdById(jobId)
				.orElseThrow(() -> ApiException.invalid(null, "작업을 찾을 수 없습니다."));
			// 잠금 순서는 영상 → 작업(VideoWorkerService와 같음)
			Video video = videos.findByIdForUpdate(videoId).orElse(null);
			Job job = jobs.findByIdForUpdate(jobId).orElseThrow();
			if (job.isFinished() || video == null) {
				return;
			}
			job.fail(reason);
			if (!video.isInProgress()) {
				return;
			}
			String audioKey = video.getAudioKey();
			video.fail(reason);
			if (video.getCardId() != null) {
				cards.decrementVideoCount(video.getCardId());
			}
			push.notify(video.getUserId(), PushService.videoFailed(video.getId(), video.getCardId()));
			if (audioKey != null) {
				AfterCommit.run(() -> storage.delete(audioKey));
			}
		});
	}

	private VideoResponse response(Video video) {
		boolean done = video.getStatus() == VideoStatus.DONE;
		String summary = video.getCardSummary();
		return new VideoResponse(video.getId(), video.getCardId(), summary == null ? "" : summary,
				video.getStatus().name(), done ? url(video.getVideoKey()) : null, done ? url(video.getThumbKey()) : null,
				done ? video.getDurationMs() : null, video.getCreatedAt());
	}

	private String url(String key) {
		return key == null ? null : storage.presignedUrl(key).toString();
	}

	private static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "VIDEO_NOT_FOUND", "영상을 찾을 수 없습니다.");
	}

	private static ApiException inProgress() {
		return new ApiException(HttpStatus.CONFLICT, "VIDEO_IN_PROGRESS", "이 카드로 만드는 중인 영상이 있습니다.");
	}

}
