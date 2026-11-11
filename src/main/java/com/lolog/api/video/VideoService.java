package com.lolog.api.video;

import com.lolog.api.common.ApiException;
import com.lolog.api.common.PageRequestParams;
import com.lolog.api.common.PageSort;
import com.lolog.api.common.SafeLog;
import com.lolog.api.job.Job;
import com.lolog.api.job.JobRepository;
import com.lolog.api.notification.PushService;
import com.lolog.api.storage.ImageStorage;
import com.lolog.api.video.VideoDtos.VideoAccepted;
import com.lolog.api.video.VideoDtos.VideoPage;
import com.lolog.api.video.VideoDtos.VideoRef;
import com.lolog.api.video.VideoDtos.VideoResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

// 카드 영상 요청·목록·보기·지우기와 영상 작업 실패(video.md 2장). 만화(ComicService)와 같은 규칙이다.
// 카드 하나에 실패가 아닌 영상 2개까지(cards.video_count). 실패하면 횟수가 돌아오고, 지워도 돌아오지 않는다.
@Service
public class VideoService {

	static final int MAX_VIDEOS = 2;
	static final Set<String> IN_PROGRESS = Set.of("QUEUED", "VOICE", "DRAWING");

	private static final Logger log = LoggerFactory.getLogger(VideoService.class);

	private final JdbcTemplate jdbc;
	private final JobRepository jobs;
	private final VideoPublisher publisher;
	private final ImageStorage storage;
	private final PushService push;
	private final TransactionTemplate tx;

	public VideoService(JdbcTemplate jdbc, JobRepository jobs, VideoPublisher publisher, ImageStorage storage,
			PushService push, TransactionTemplate tx) {
		this.jdbc = jdbc;
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
			List<String> card = jdbc.queryForList("select summary from cards where id = ? and user_id = ? for update",
					String.class, cardId, userId);
			if (card.isEmpty()) {
				throw new ApiException(HttpStatus.NOT_FOUND, "CARD_NOT_FOUND", "카드를 찾을 수 없습니다.");
			}
			Boolean running = jdbc.queryForObject(
					"select exists (select 1 from videos where card_id = ? and status in ('QUEUED', 'VOICE', 'DRAWING'))",
					Boolean.class, cardId);
			if (Boolean.TRUE.equals(running)) {
				throw inProgress();
			}
			if (jdbc.update("update cards set video_count = video_count + 1 where id = ? and video_count < ?", cardId,
					MAX_VIDEOS) == 0) {
				throw new ApiException(HttpStatus.CONFLICT, "VIDEO_LIMIT", "이 카드로는 영상을 더 만들 수 없습니다.");
			}
			long videoId;
			try {
				videoId = jdbc.queryForObject(
						"insert into videos (card_id, user_id, card_summary) values (?, ?, ?) returning id", Long.class, cardId,
						userId, card.getFirst());
			}
			catch (DataIntegrityViolationException e) {
				throw inProgress();
			}
			created[0] = new VideoAccepted(videoId, "QUEUED");
			return jobs.save(Job.video("VIDEO_VOICE", videoId, userId)).getId();
		});
		publisher.publishQuietly(jobId);
		return created[0];
	}

	// sort는 만화 목록과 같다(latest 기본, oldest). 커서는 마지막으로 받은 영상 ID라 다음 페이지도 같은 sort로 부른다.
	@Transactional(readOnly = true)
	public VideoPage list(long userId, String cursor, Integer size, String sort) {
		PageSort order = PageSort.of(sort);
		PageRequestParams page = PageRequestParams.of(cursor, size);
		String sql = order == PageSort.OLDEST
				? "select * from videos where user_id = ? and (?::bigint is null or id > ?) order by id asc limit ?"
				: "select * from videos where user_id = ? and (?::bigint is null or id < ?) order by id desc limit ?";
		List<VideoResponse> items = new ArrayList<>(jdbc.query(sql, (rs, n) -> response(rs), userId, page.before(),
				page.before(), page.size() + 1));
		String next = null;
		if (items.size() > page.size()) {
			items = new ArrayList<>(items.subList(0, page.size()));
			next = String.valueOf(items.getLast().id());
		}
		return new VideoPage(items, next);
	}

	@Transactional(readOnly = true)
	public VideoResponse get(long userId, long videoId) {
		return jdbc.query("select * from videos where id = ? and user_id = ?", (rs, n) -> response(rs), videoId, userId)
			.stream()
			.findFirst()
			.orElseThrow(VideoService::notFound);
	}

	// 만드는 중이어도 지울 수 있다. 작업도 같이 지워져 워커의 다음 요청은 404·400이 된다(만화와 같음). 횟수는 돌아오지 않는다.
	@Transactional
	public void delete(long userId, long videoId) {
		if (jdbc.update("delete from videos where id = ? and user_id = ?", videoId, userId) == 0) {
			throw notFound();
		}
		afterCommit(() -> storage.deletePrefix(ImageStorage.videoPrefix(userId, videoId)));
	}

	// 카드 자세히의 videos[]. 최근 것이 앞.
	@Transactional(readOnly = true)
	public List<VideoRef> refs(long cardId) {
		return jdbc.query("select id, status from videos where card_id = ? order by id desc",
				(rs, n) -> new VideoRef(rs.getLong("id"), rs.getString("status")), cardId);
	}

	public boolean hasRunningBeforeDrawing(long cardId) {
		return Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists (select 1 from videos where card_id = ? and status in ('QUEUED', 'VOICE'))", Boolean.class,
				cardId));
	}

	// 워커가 영상 작업(목소리·그림 어느 단계든)의 실패를 알렸을 때. 같은 jobId가 다시 오면 무시한다.
	public void fail(long jobId, String reason) {
		tx.executeWithoutResult(status -> {
			Long videoId = jdbc.queryForList("select video_id from jobs where id = ?", Long.class, jobId)
				.stream()
				.findFirst()
				.orElseThrow(() -> ApiException.invalid(null, "작업을 찾을 수 없습니다."));
			// 잠금 순서는 영상 → 작업(VideoWorkerService와 같음)
			List<Video> found = jdbc.query("select * from videos where id = ? for update", (rs, n) -> Video.of(rs), videoId);
			Job job = jobs.findByIdForUpdate(jobId).orElseThrow();
			if (job.isFinished() || found.isEmpty()) {
				return;
			}
			job.fail(reason);
			Video video = found.getFirst();
			if (!IN_PROGRESS.contains(video.status())) {
				return;
			}
			jdbc.update("""
					update videos set status = 'FAILED', fail_reason = ?, audio_key = null, updated_at = current_timestamp
					where id = ?
					""", reason, videoId);
			if (video.cardId() != null) {
				jdbc.update("update cards set video_count = video_count - 1 where id = ? and video_count > 0", video.cardId());
			}
			push.notify(video.userId(), PushService.videoFailed(videoId, video.cardId()));
			if (video.audioKey() != null) {
				afterCommit(() -> storage.delete(video.audioKey()));
			}
		});
	}

	record Video(long id, Long cardId, long userId, String status, String audioKey) {

		static Video of(ResultSet rs) throws SQLException {
			return new Video(rs.getLong("id"), (Long) rs.getObject("card_id"), rs.getLong("user_id"), rs.getString("status"),
					rs.getString("audio_key"));
		}

	}

	private VideoResponse response(ResultSet rs) throws SQLException {
		boolean done = "DONE".equals(rs.getString("status"));
		String summary = rs.getString("card_summary");
		return new VideoResponse(rs.getLong("id"), (Long) rs.getObject("card_id"), summary == null ? "" : summary,
				rs.getString("status"),
				done ? url(rs.getString("video_key")) : null, done ? url(rs.getString("thumb_key")) : null,
				done ? (Long) rs.getObject("duration_ms") : null, rs.getObject("created_at", OffsetDateTime.class));
	}

	private String url(String key) {
		return key == null ? null : storage.presignedUrl(key).toString();
	}

	// 커밋된 뒤에 파일을 지운다. 못 지우면 로그만 남긴다(탈퇴할 때 users/{id}/ 아래가 다시 지워진다).
	static void afterCommit(Runnable work) {
		Runnable quiet = () -> {
			try {
				work.run();
			}
			catch (Exception e) {
				log.warn("영상 파일을 지우지 못했습니다.", SafeLog.withoutMessages(e));
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

	static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "VIDEO_NOT_FOUND", "영상을 찾을 수 없습니다.");
	}

	private static ApiException inProgress() {
		return new ApiException(HttpStatus.CONFLICT, "VIDEO_IN_PROGRESS", "이 카드로 만드는 중인 영상이 있습니다.");
	}

}
