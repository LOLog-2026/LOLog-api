package com.lolog.api.domain.video.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.storage.StorageDeletions;
import java.net.URI;
import java.net.URL;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
@DisplayName("VideoService")
class VideoServiceTest {

	private static final long USER_ID = 1L;
	private static final long CARD_ID = 10L;
	private static final long VIDEO_ID = 100L;
	private static final long JOB_ID = 1000L;

	@Mock
	private VideoRepository videos;

	@Mock
	private CardRepository cards;

	@Mock
	private JobRepository jobs;

	@Mock
	private VideoPublisher publisher;

	@Mock
	private ImageStorage storage;

	@Mock
	private StorageDeletions deletions;

	@Mock
	private PushService push;

	@Mock
	private TransactionTemplate tx;

	private VideoService service;

	@BeforeEach
	void setUp() {
		// 트랜잭션 템플릿은 받은 콜백을 바로 실행한다.
		lenient().when(tx.execute(any())).thenAnswer(inv -> inv.<TransactionCallback<?>>getArgument(0).doInTransaction(null));
		lenient().doAnswer(inv -> {
			inv.<Consumer<TransactionStatus>>getArgument(0).accept(null);
			return null;
		}).when(tx).executeWithoutResult(any());
		service = new VideoService(videos, cards, jobs, publisher, storage, deletions, push, tx);
	}

	// ---- request ----

	@Test
	@DisplayName("내 카드가 아니면 CARD_NOT_FOUND이고 영상·작업을 만들지 않는다")
	void 내_카드가_아니면_만들지_않는다() {
		// given
		given(cards.findByIdAndUserIdForUpdate(CARD_ID, USER_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.request(USER_ID, CARD_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CARD_NOT_FOUND);
		verify(videos, never()).saveAndFlush(any());
		verify(jobs, never()).save(any());
		verifyNoInteractions(publisher);
	}

	@Test
	@DisplayName("만드는 중인 영상이 있으면 VIDEO_IN_PROGRESS이고 횟수를 올리지 않는다")
	void 만드는_중이면_횟수를_올리지_않는다() {
		// given
		given(cards.findByIdAndUserIdForUpdate(CARD_ID, USER_ID)).willReturn(Optional.of(card()));
		given(videos.existsByCardIdAndStatusIn(CARD_ID, VideoStatus.IN_PROGRESS)).willReturn(true);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.request(USER_ID, CARD_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VIDEO_IN_PROGRESS);
		verify(cards, never()).incrementVideoCount(any());
		verifyNoInteractions(publisher);
	}

	@Test
	@DisplayName("횟수를 올리지 못하면 VIDEO_LIMIT이고 영상을 저장하지 않는다")
	void 횟수가_다_찼으면_VIDEO_LIMIT() {
		// given
		given(cards.findByIdAndUserIdForUpdate(CARD_ID, USER_ID)).willReturn(Optional.of(card()));
		given(cards.incrementVideoCount(CARD_ID)).willReturn(0);

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.request(USER_ID, CARD_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VIDEO_LIMIT);
		verify(videos, never()).saveAndFlush(any());
		verifyNoInteractions(publisher);
	}

	@Test
	@DisplayName("저장이 부분 유니크에 걸리면 VIDEO_IN_PROGRESS이고 작업을 만들지 않는다")
	void 부분_유니크에_걸리면_VIDEO_IN_PROGRESS() {
		// given
		given(cards.findByIdAndUserIdForUpdate(CARD_ID, USER_ID)).willReturn(Optional.of(card()));
		given(cards.incrementVideoCount(CARD_ID)).willReturn(1);
		given(videos.saveAndFlush(any())).willThrow(new DataIntegrityViolationException("uk"));

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.request(USER_ID, CARD_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VIDEO_IN_PROGRESS);
		verify(jobs, never()).save(any());
		verifyNoInteractions(publisher);
	}

	@Test
	@DisplayName("요청하면 카드 요약을 복사한 영상과 VIDEO_VOICE 작업을 만들고 그 작업을 큐에 넣는다")
	void 요청하면_영상과_작업을_만들고_큐에_넣는다() {
		// given
		given(cards.findByIdAndUserIdForUpdate(CARD_ID, USER_ID)).willReturn(Optional.of(card()));
		given(cards.incrementVideoCount(CARD_ID)).willReturn(1);
		given(videos.saveAndFlush(any())).willAnswer(inv -> {
			Video v = inv.getArgument(0);
			ReflectionTestUtils.setField(v, "id", VIDEO_ID);
			return v;
		});
		given(jobs.save(any())).willAnswer(inv -> {
			Job j = inv.getArgument(0);
			ReflectionTestUtils.setField(j, "id", JOB_ID);
			return j;
		});

		// when
		VideoAccepted accepted = service.request(USER_ID, CARD_ID);

		// then
		assertThat(accepted).isEqualTo(new VideoAccepted(VIDEO_ID, "QUEUED"));
		ArgumentCaptor<Video> video = ArgumentCaptor.forClass(Video.class);
		verify(videos).saveAndFlush(video.capture());
		assertThat(video.getValue().getCardId()).isEqualTo(CARD_ID);
		assertThat(video.getValue().getUserId()).isEqualTo(USER_ID);
		assertThat(video.getValue().getCardSummary()).isEqualTo("요약");
		ArgumentCaptor<Job> job = ArgumentCaptor.forClass(Job.class);
		verify(jobs).save(job.capture());
		assertThat(job.getValue().getJobType()).isEqualTo("VIDEO_VOICE");
		assertThat(job.getValue().getVideoId()).isEqualTo(VIDEO_ID);
		verify(publisher).publishQuietly(JOB_ID);
	}

	// ---- list ----

	@Test
	@DisplayName("정렬이 없으면 최신순으로 size+1개를 읽고, 더 있으면 마지막 영상 ID가 nextCursor다")
	void 더_있으면_마지막_영상_ID가_다음_커서다() {
		// given
		given(videos.findPage(USER_ID, null, Limit.of(3)))
			.willReturn(List.of(video(30L, VideoStatus.QUEUED), video(20L, VideoStatus.QUEUED), video(10L, VideoStatus.QUEUED)));

		// when
		VideoPage page = service.list(USER_ID, null, 2, null);

		// then
		assertThat(page.videos()).extracting(VideoResponse::id).containsExactly(30L, 20L);
		assertThat(page.nextCursor()).isEqualTo("20");
		verify(videos, never()).findPageOldest(anyLong(), any(), any());
	}

	@Test
	@DisplayName("oldest면 오래된순으로 읽고, 더 없으면 nextCursor가 null이다")
	void oldest면_오래된순으로_읽는다() {
		// given
		given(videos.findPageOldest(USER_ID, 5L, Limit.of(3))).willReturn(List.of(video(6L, VideoStatus.QUEUED)));

		// when
		VideoPage page = service.list(USER_ID, "5", 2, "oldest");

		// then
		assertThat(page.videos()).extracting(VideoResponse::id).containsExactly(6L);
		assertThat(page.nextCursor()).isNull();
		verify(videos, never()).findPage(anyLong(), any(), any());
	}

	@Test
	@DisplayName("모르는 정렬이면 field가 sort인 INVALID_REQUEST이고 저장소를 읽지 않는다")
	void 모르는_정렬이면_읽지_않는다() {
		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.list(USER_ID, null, null, "new"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isEqualTo("sort");
		verifyNoInteractions(videos);
	}

	// ---- get ----

	@Test
	@DisplayName("내 영상이 없으면 VIDEO_NOT_FOUND다")
	void 내_영상이_없으면_VIDEO_NOT_FOUND() {
		// given
		given(videos.findByIdAndUserId(VIDEO_ID, USER_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.get(USER_ID, VIDEO_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VIDEO_NOT_FOUND);
	}

	@Test
	@DisplayName("DONE인 영상은 영상·썸네일 서명 주소와 길이를 준다")
	void DONE이면_주소와_길이를_준다() throws Exception {
		// given
		Video video = video(VIDEO_ID, VideoStatus.QUEUED);
		video.finish("v.mp4", "t.jpg", 1234L);
		given(videos.findByIdAndUserId(VIDEO_ID, USER_ID)).willReturn(Optional.of(video));
		given(storage.presignedUrl("v.mp4")).willReturn(url("v"));
		given(storage.presignedUrl("t.jpg")).willReturn(url("t"));

		// when
		VideoResponse response = service.get(USER_ID, VIDEO_ID);

		// then
		assertThat(response.status()).isEqualTo("DONE");
		assertThat(response.videoUrl()).isEqualTo("https://files.test/v");
		assertThat(response.thumbnailUrl()).isEqualTo("https://files.test/t");
		assertThat(response.durationMs()).isEqualTo(1234L);
	}

	@Test
	@DisplayName("DONE이 아니면 주소·길이는 null이고 서명 주소를 만들지 않는다. 요약이 없으면 빈 글이다")
	void DONE이_아니면_주소가_없다() {
		// given
		Video video = new Video(null, USER_ID, null);
		ReflectionTestUtils.setField(video, "id", VIDEO_ID);
		given(videos.findByIdAndUserId(VIDEO_ID, USER_ID)).willReturn(Optional.of(video));

		// when
		VideoResponse response = service.get(USER_ID, VIDEO_ID);

		// then
		assertThat(response.status()).isEqualTo("QUEUED");
		assertThat(response.cardId()).isNull();
		assertThat(response.cardSummary()).isEmpty();
		assertThat(response.videoUrl()).isNull();
		assertThat(response.thumbnailUrl()).isNull();
		assertThat(response.durationMs()).isNull();
		verifyNoInteractions(storage);
	}

	// ---- delete ----

	@Test
	@DisplayName("내 영상이 없으면 VIDEO_NOT_FOUND이고 아무것도 지우지 않는다")
	void 없는_영상은_지우지_않는다() {
		// given
		given(videos.findByIdAndUserId(VIDEO_ID, USER_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.delete(USER_ID, VIDEO_ID));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.VIDEO_NOT_FOUND);
		verify(videos, never()).delete(any());
		verifyNoInteractions(deletions);
	}

	@Test
	@DisplayName("지우면 영상 행을 지우고 영상 폴더를 지울 줄에 넣으며 횟수는 돌려주지 않는다")
	void 지우면_폴더를_지울_줄에_넣는다() {
		// given
		Video video = video(VIDEO_ID, VideoStatus.DRAWING);
		given(videos.findByIdAndUserId(VIDEO_ID, USER_ID)).willReturn(Optional.of(video));

		// when
		service.delete(USER_ID, VIDEO_ID);

		// then
		verify(videos).delete(video);
		verify(deletions).schedulePrefix(ImageStorage.videoPrefix(USER_ID, VIDEO_ID));
		verify(cards, never()).decrementVideoCount(any());
	}

	// ---- refs·remaining·hasRunningBeforeDrawing ----

	@Test
	@DisplayName("refs는 저장소가 준 순서대로 ID와 상태 이름을 준다")
	void refs는_ID와_상태를_준다() {
		// given
		given(videos.findByCardIdOrderByIdDesc(CARD_ID))
			.willReturn(List.of(video(2L, VideoStatus.FAILED), video(1L, VideoStatus.DONE)));

		// when
		List<VideoRef> refs = service.refs(CARD_ID);

		// then
		assertThat(refs).containsExactly(new VideoRef(2L, "FAILED"), new VideoRef(1L, "DONE"));
	}

	@Test
	@DisplayName("카드가 없으면 남은 횟수는 2다")
	void 카드가_없으면_남은_횟수는_2() {
		// given
		given(cards.findById(CARD_ID)).willReturn(Optional.empty());

		// when & then
		assertThat(service.remaining(CARD_ID)).isEqualTo(2);
	}

	@Test
	@DisplayName("1번 썼으면 남은 횟수는 1이다")
	void 한_번_썼으면_1() {
		// given
		Card card = card();
		ReflectionTestUtils.setField(card, "videoCount", 1);
		given(cards.findById(CARD_ID)).willReturn(Optional.of(card));

		// when & then
		assertThat(service.remaining(CARD_ID)).isEqualTo(1);
	}

	@Test
	@DisplayName("쓴 횟수가 2를 넘어도 남은 횟수는 0이다")
	void 넘게_썼어도_0() {
		// given
		Card card = card();
		ReflectionTestUtils.setField(card, "videoCount", 3);
		given(cards.findById(CARD_ID)).willReturn(Optional.of(card));

		// when & then
		assertThat(service.remaining(CARD_ID)).isZero();
	}

	@Test
	@DisplayName("그림 전 단계를 볼 때 QUEUED·VOICE 상태로 묻는다")
	void 그림_전_단계로_묻는다() {
		// given
		given(videos.existsByCardIdAndStatusIn(CARD_ID, VideoStatus.BEFORE_DRAWING)).willReturn(true);

		// when & then
		assertThat(service.hasRunningBeforeDrawing(CARD_ID)).isTrue();
	}

	// ---- fail ----

	@Test
	@DisplayName("영상 작업이 아닌 jobId면 field 없는 INVALID_REQUEST다")
	void 영상_작업이_아니면_INVALID_REQUEST() {
		// given
		given(jobs.findVideoIdById(JOB_ID)).willReturn(Optional.empty());

		// when
		ApiException e = catchThrowableOfType(ApiException.class, () -> service.fail(JOB_ID, "x"));

		// then
		assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_REQUEST);
		assertThat(e.getField()).isNull();
		verifyNoInteractions(push, deletions);
	}

	@Test
	@DisplayName("이미 끝난 작업이면 아무것도 바꾸지 않는다")
	void 끝난_작업이면_무시한다() {
		// given
		Video video = video(VIDEO_ID, VideoStatus.VOICE);
		Job job = job();
		job.complete();
		givenFail(video, job);

		// when
		service.fail(JOB_ID, "x");

		// then
		assertThat(job.getStatus()).isEqualTo("DONE");
		assertThat(video.getStatus()).isEqualTo(VideoStatus.VOICE);
		verify(cards, never()).decrementVideoCount(any());
		verifyNoInteractions(push, deletions);
	}

	@Test
	@DisplayName("영상이 이미 지워졌으면 작업도 실패로 바꾸지 않는다")
	void 영상이_지워졌으면_무시한다() {
		// given
		Job job = job();
		given(jobs.findVideoIdById(JOB_ID)).willReturn(Optional.of(VIDEO_ID));
		given(videos.findByIdForUpdate(VIDEO_ID)).willReturn(Optional.empty());
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));

		// when
		service.fail(JOB_ID, "x");

		// then
		assertThat(job.getStatus()).isEqualTo("QUEUED");
		verifyNoInteractions(push, deletions);
	}

	@Test
	@DisplayName("영상이 이미 끝났으면 작업만 실패로 바꾸고 영상·횟수·푸시는 그대로다")
	void 끝난_영상이면_작업만_실패다() {
		// given
		Video video = video(VIDEO_ID, VideoStatus.DONE);
		Job job = job();
		givenFail(video, job);

		// when
		service.fail(JOB_ID, "x");

		// then
		assertThat(job.getStatus()).isEqualTo("FAILED");
		assertThat(video.getStatus()).isEqualTo(VideoStatus.DONE);
		verify(cards, never()).decrementVideoCount(any());
		verifyNoInteractions(push, deletions);
	}

	@Test
	@DisplayName("만드는 중이면 영상을 실패로 바꾸고 횟수를 돌려주고 푸시를 보내고 음성 파일을 지울 줄에 넣는다")
	void 만드는_중이면_실패로_바꾸고_정리한다() {
		// given
		Video video = video(VIDEO_ID, VideoStatus.DRAWING);
		ReflectionTestUtils.setField(video, "audioKey", "a.wav");
		Job job = job();
		givenFail(video, job);

		// when
		service.fail(JOB_ID, "그림 실패");

		// then
		assertThat(job.getStatus()).isEqualTo("FAILED");
		assertThat(video.getStatus()).isEqualTo(VideoStatus.FAILED);
		assertThat(video.getFailReason()).isEqualTo("그림 실패");
		assertThat(video.getAudioKey()).isNull();
		verify(cards).decrementVideoCount(CARD_ID);
		verify(push).notify(USER_ID, PushService.videoFailed(VIDEO_ID, CARD_ID));
		verify(deletions).schedule("a.wav");
	}

	@Test
	@DisplayName("카드가 지워졌고 음성이 없으면 횟수·파일은 건드리지 않고 푸시만 보낸다")
	void 카드와_음성이_없으면_푸시만_보낸다() {
		// given
		Video video = new Video(null, USER_ID, "요약");
		ReflectionTestUtils.setField(video, "id", VIDEO_ID);
		Job job = job();
		givenFail(video, job);

		// when
		service.fail(JOB_ID, "x");

		// then
		assertThat(video.getStatus()).isEqualTo(VideoStatus.FAILED);
		verify(cards, never()).decrementVideoCount(any());
		verify(push).notify(eq(USER_ID), eq(PushService.videoFailed(VIDEO_ID, null)));
		verifyNoInteractions(deletions);
	}

	private void givenFail(Video video, Job job) {
		given(jobs.findVideoIdById(JOB_ID)).willReturn(Optional.of(VIDEO_ID));
		given(videos.findByIdForUpdate(VIDEO_ID)).willReturn(Optional.of(video));
		given(jobs.findByIdForUpdate(JOB_ID)).willReturn(Optional.of(job));
	}

	private static Card card() {
		Card card = new Card(5L, USER_ID, "웃김", "요약", 0, 1000, OffsetDateTime.now());
		ReflectionTestUtils.setField(card, "id", CARD_ID);
		return card;
	}

	private static Video video(long id, VideoStatus status) {
		Video video = new Video(CARD_ID, USER_ID, "요약");
		ReflectionTestUtils.setField(video, "id", id);
		ReflectionTestUtils.setField(video, "status", status);
		return video;
	}

	private static Job job() {
		Job job = Job.video("VIDEO_DRAW", VIDEO_ID, USER_ID);
		ReflectionTestUtils.setField(job, "id", JOB_ID);
		return job;
	}

	private static URL url(String path) throws Exception {
		return URI.create("https://files.test/" + path).toURL();
	}

}
