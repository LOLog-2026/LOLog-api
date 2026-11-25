package com.lolog.api.domain.job.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.lolog.api.support.RepositoryTest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@DisplayName("JobRepository")
class JobRepositoryTest extends RepositoryTest {

	private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 1, 12, 0, 0, 0, ZoneOffset.UTC);

	@Autowired
	private JobRepository jobRepository;

	@Autowired
	private JdbcTemplate jdbc;

	private long recording(long userId) {
		return jdbc.queryForObject("""
				insert into recordings (user_id, client_recording_id, started_at) values (?, gen_random_uuid()::text, ?) returning id
				""", Long.class, userId, T0);
	}

	private long part(long recordingId, int partNo) {
		return jdbc.queryForObject("insert into recording_parts (recording_id, part_no, duration_sec) values (?, ?, 60) returning id",
				Long.class, recordingId, partNo);
	}

	private long comic(long userId) {
		return jdbc.queryForObject("insert into comics (user_id) values (?) returning id", Long.class, userId);
	}

	private long video(long userId) {
		return jdbc.queryForObject("insert into videos (user_id) values (?) returning id", Long.class, userId);
	}

	private long detectJob(long userId, long partId, String status) {
		return jdbc.queryForObject("""
				insert into jobs (user_id, job_type, recording_part_id, status) values (?, 'DETECT', ?, ?) returning id
				""", Long.class, userId, partId, status);
	}

	private long comicJob(long comicId, String status, int attempts, OffsetDateTime createdAt) {
		return jdbc.queryForObject("""
				insert into jobs (user_id, job_type, comic_id, status, attempts, created_at) values (1, 'COMIC', ?, ?, ?, ?) returning id
				""", Long.class, comicId, status, attempts, createdAt);
	}

	private long videoJob(long videoId, String type, String status) {
		return jdbc.queryForObject("""
				insert into jobs (user_id, job_type, video_id, status) values (1, ?, ?, ?) returning id
				""", Long.class, type, videoId, status);
	}

	// 대상 없는 작업(AVATAR, VOICE_CLONE).
	private long userJob(long userId, String type, String status, String failReason, OffsetDateTime createdAt) {
		return jdbc.queryForObject("""
				insert into jobs (user_id, job_type, status, fail_reason, created_at) values (?, ?, ?, ?, ?) returning id
				""", Long.class, userId, type, status, failReason, createdAt);
	}

	private Map<String, Object> row(long jobId) {
		return jdbc.queryForMap("select status, fail_reason from jobs where id = ?", jobId);
	}

	@Test
	@DisplayName("findUnpublishedIds는 그 종류의 QUEUED이고 시도 0번이며 before보다 먼저 만든 작업만 ID순으로 고른다")
	void findUnpublishedIds_조건에_맞는_작업만_고른다() {
		// given
		OffsetDateTime before = T0.plusMinutes(5);
		long a = comicJob(comic(1L), "QUEUED", 0, T0);
		long b = comicJob(comic(1L), "QUEUED", 0, T0.plusMinutes(1));
		comicJob(comic(1L), "QUEUED", 1, T0);
		comicJob(comic(1L), "RUNNING", 0, T0);
		comicJob(comic(1L), "QUEUED", 0, before);
		detectJob(1L, part(recording(1L), 1), "QUEUED");

		// when & then
		assertThat(jobRepository.findUnpublishedIds("COMIC", before)).containsExactly(a, b);
	}

	@Test
	@DisplayName("findComicIdById는 작업의 만화 ID를 꺼내고, 작업이 없거나 만화가 없는 작업이면 비어 있다")
	void findComicIdById_만화_ID를_꺼낸다() {
		// given
		long comicId = comic(1L);
		long jobId = comicJob(comicId, "QUEUED", 0, T0);
		long avatar = userJob(1L, "AVATAR", "QUEUED", null, T0);

		// when & then
		assertThat(jobRepository.findComicIdById(jobId)).contains(comicId);
		assertThat(jobRepository.findComicIdById(avatar)).isEmpty();
		assertThat(jobRepository.findComicIdById(-1L)).isEmpty();
	}

	@Test
	@DisplayName("findVideoIdById와 findTypeById는 작업의 영상 ID와 종류를 꺼낸다")
	void findVideoIdById_영상_ID와_종류를_꺼낸다() {
		// given
		long videoId = video(1L);
		long jobId = videoJob(videoId, "VIDEO_DRAW", "QUEUED");

		// when & then
		assertThat(jobRepository.findVideoIdById(jobId)).contains(videoId);
		assertThat(jobRepository.findTypeById(jobId)).contains("VIDEO_DRAW");
		assertThat(jobRepository.findTypeById(-1L)).isEmpty();
	}

	@Test
	@DisplayName("existsByRecordingPartIdAndStatusAndIdNot은 자기 자신은 빼고 같은 묶음의 다른 작업만 본다")
	void existsByRecordingPartIdAndStatusAndIdNot_자기_자신은_뺀다() {
		// given
		long partId = part(recording(1L), 1);
		long done = detectJob(1L, partId, "DONE");

		// when & then
		assertThat(jobRepository.existsByRecordingPartIdAndStatusAndIdNot(partId, "DONE", done)).isFalse();

		// given
		long other = detectJob(1L, partId, "QUEUED");

		// when & then
		assertThat(jobRepository.existsByRecordingPartIdAndStatusAndIdNot(partId, "DONE", other)).isTrue();
	}

	@Test
	@DisplayName("countDoneByRecordingId는 그 녹음의 묶음 작업 중 DONE만 센다")
	void countDoneByRecordingId_그_녹음의_DONE만_센다() {
		// given
		long recordingId = recording(1L);
		long p1 = part(recordingId, 1);
		long p2 = part(recordingId, 2);
		detectJob(1L, p1, "DONE");
		detectJob(1L, p2, "DONE");
		detectJob(1L, p2, "FAILED");
		detectJob(1L, part(recording(1L), 1), "DONE");

		// when & then
		assertThat(jobRepository.countDoneByRecordingId(recordingId)).isEqualTo(2);
	}

	@Test
	@DisplayName("findRecordingIdByJobId는 감지 작업의 녹음 ID를 꺼내고, 묶음이 없는 작업이면 비어 있다")
	void findRecordingIdByJobId_감지_작업의_녹음_ID를_꺼낸다() {
		// given
		long recordingId = recording(1L);
		long jobId = detectJob(1L, part(recordingId, 1), "QUEUED");
		long avatar = userJob(1L, "AVATAR", "QUEUED", null, T0);

		// when & then
		assertThat(jobRepository.findRecordingIdByJobId(jobId)).contains(recordingId);
		assertThat(jobRepository.findRecordingIdByJobId(avatar)).isEmpty();
	}

	@Test
	@DisplayName("countAvatarJobsSince는 since 이후(같은 시각 포함) 이 회원의 AVATAR 작업을 세고, 실패는 지워서 멈춘 것만 센다")
	void countAvatarJobsSince_실패는_지워서_멈춘_것만_센다() {
		// given
		userJob(1L, "AVATAR", "QUEUED", null, T0);
		userJob(1L, "AVATAR", "DONE", null, T0.plusMinutes(1));
		userJob(1L, "AVATAR", "FAILED", "캐릭터를 지움", T0.plusMinutes(2));
		userJob(1L, "AVATAR", "FAILED", "그림 실패", T0.plusMinutes(3));
		userJob(1L, "AVATAR", "DONE", null, T0.minusNanos(1000));
		userJob(1L, "VOICE_CLONE", "DONE", null, T0.plusMinutes(1));
		userJob(2L, "AVATAR", "DONE", null, T0.plusMinutes(1));

		// when & then
		assertThat(jobRepository.countAvatarJobsSince(1L, T0)).isEqualTo(3);
	}

	@Test
	@DisplayName("completeQueued는 이 회원의 그 종류 QUEUED 작업만 DONE으로 바꾼다")
	void completeQueued_QUEUED만_DONE으로_바꾼다() {
		// given
		long queued = userJob(1L, "AVATAR", "QUEUED", null, T0);
		long running = userJob(1L, "AVATAR", "RUNNING", null, T0);
		long otherType = userJob(1L, "VOICE_CLONE", "QUEUED", null, T0);
		long otherUser = userJob(2L, "AVATAR", "QUEUED", null, T0);

		// when & then
		assertThat(jobRepository.completeQueued(1L, "AVATAR")).isEqualTo(1);

		// when & then
		assertThat(row(queued).get("status")).isEqualTo("DONE");
		assertThat(row(running).get("status")).isEqualTo("RUNNING");
		assertThat(row(otherType).get("status")).isEqualTo("QUEUED");
		assertThat(row(otherUser).get("status")).isEqualTo("QUEUED");
	}

	@Test
	@DisplayName("failQueued는 이 회원의 그 종류 QUEUED 작업만 FAILED로 바꾸고 이유를 남긴다")
	void failQueued_QUEUED만_FAILED로_바꾸고_이유를_남긴다() {
		// given
		long queued = userJob(1L, "AVATAR", "QUEUED", null, T0);
		long done = userJob(1L, "AVATAR", "DONE", null, T0);

		// when & then
		assertThat(jobRepository.failQueued(1L, "AVATAR", "캐릭터를 지움")).isEqualTo(1);

		// when & then
		assertThat(row(queued)).containsEntry("status", "FAILED").containsEntry("fail_reason", "캐릭터를 지움");
		assertThat(row(done)).containsEntry("status", "DONE").containsEntry("fail_reason", null);
	}

	@Test
	@DisplayName("completeVideoStep은 그 영상 그 단계의 끝나지 않은 작업만 DONE으로 바꾼다")
	void completeVideoStep_끝나지_않은_작업만_DONE으로_바꾼다() {
		// given
		long videoId = video(1L);
		long queued = videoJob(videoId, "VIDEO_VOICE", "QUEUED");
		long running = videoJob(videoId, "VIDEO_VOICE", "RUNNING");
		long failed = videoJob(videoId, "VIDEO_VOICE", "FAILED");
		long draw = videoJob(videoId, "VIDEO_DRAW", "QUEUED");
		long otherVideo = videoJob(video(1L), "VIDEO_VOICE", "QUEUED");

		// when & then
		assertThat(jobRepository.completeVideoStep(videoId, "VIDEO_VOICE")).isEqualTo(2);

		// when & then
		assertThat(row(queued).get("status")).isEqualTo("DONE");
		assertThat(row(running).get("status")).isEqualTo("DONE");
		assertThat(row(failed).get("status")).isEqualTo("FAILED");
		assertThat(row(draw).get("status")).isEqualTo("QUEUED");
		assertThat(row(otherVideo).get("status")).isEqualTo("QUEUED");
	}

	@Test
	@DisplayName("deleteAllByUserId는 그 회원의 작업만 지운다")
	void deleteAllByUserId_그_회원의_작업만_지운다() {
		// given
		userJob(1L, "AVATAR", "QUEUED", null, T0);
		userJob(1L, "VOICE_CLONE", "DONE", null, T0);
		long other = userJob(2L, "AVATAR", "QUEUED", null, T0);

		// when & then
		assertThat(jobRepository.deleteAllByUserId(1L)).isEqualTo(2);

		// when & then
		assertThat(jdbc.queryForList("select id from jobs where user_id in (1, 2)", Long.class)).containsExactly(other);
	}

}
