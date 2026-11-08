package com.lolog.api.recording;

import com.lolog.api.job.Job;
import com.lolog.api.job.JobRepository;
import com.lolog.api.card.Card;
import com.lolog.api.card.CardRepository;
import com.lolog.api.common.ApiException;
import com.lolog.api.notification.PushService;
import com.lolog.api.recording.RecordingDtos.Sentence;
import com.lolog.api.recording.WorkerDtos.DetectResult;
import com.lolog.api.recording.WorkerDtos.DetectedCard;
import com.lolog.api.recording.WorkerDtos.DetectedLine;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// 워커가 보낸 감지 결과와 실패를 받는다. 결과를 저장하고, 다 쓴 대화 글을 지우고, 다음 묶음을 큐에 넣는다.
// 업로드와 같은 녹음 행 잠금을 먼저 잡으므로 같은 녹음의 처리는 한 번에 하나씩 일어난다.
@Service
public class DetectResultService {

	private final RecordingRepository recordings;
	private final RecordingPartRepository parts;
	private final JobRepository jobs;
	private final CardRepository cards;
	private final RecordingService recordingService;
	private final DetectPublisher publisher;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;
	private final PushService push;
	private final long latePartMinutes;

	public DetectResultService(RecordingRepository recordings, RecordingPartRepository parts, JobRepository jobs,
			CardRepository cards, RecordingService recordingService, DetectPublisher publisher, ObjectMapper objectMapper,
			TransactionTemplate tx, PushService push,
			@Value("${lolog.detect.late-part-minutes:70}") long latePartMinutes) {
		this.recordings = recordings;
		this.parts = parts;
		this.jobs = jobs;
		this.cards = cards;
		this.recordingService = recordingService;
		this.publisher = publisher;
		this.objectMapper = objectMapper;
		this.tx = tx;
		this.push = push;
		this.latePartMinutes = latePartMinutes;
	}

	public void receive(long recordingId, DetectResult result) {
		List<Long> next = tx.execute(status -> {
			Recording recording = recordings.findByIdForUpdate(recordingId)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RECORDING_NOT_FOUND", "녹음을 찾을 수 없습니다."));
			Job job = jobs.findById(result.jobId()).filter(Job::isDetect)
				.orElseThrow(() -> ApiException.invalid("jobId", "감지 작업을 찾을 수 없습니다."));
			RecordingPart part = parts.findById(job.getRecordingPartId()).orElseThrow();
			if (!part.getRecording().getId().equals(recordingId)) {
				throw ApiException.invalid("jobId", "이 녹음의 감지 작업이 아닙니다.");
			}
			if (!part.getPartNo().equals(result.partNo())) {
				throw ApiException.invalid("partNo", "작업의 묶음 번호와 다릅니다.");
			}
			// 같은 jobId가 다시 오면(워커 재시도) 저장하지 않는다.
			if (job.isFinished()) {
				return List.<Long>of();
			}
			validate(result.cards());
			List<DetectedCard> fresh = fresh(recordingId, result.cards());
			fresh.forEach(card -> cards.save(toCard(recording, card)));
			// 새 카드가 1개 이상이면 묶음마다 한 번 알린다(FR-NOTI-01). 버린 카드는 세지 않는다.
			// 같은 jobId가 다시 오면 위에서 끝나 두 번 가지 않는다.
			if (!fresh.isEmpty()) {
				push.notify(recording.getUserId(), PushService.cardsCreated(recordingId, fresh.size()));
			}

			boolean carryOnly = jobs.existsByRecordingPartIdAndStatusAndIdNot(part.getId(), "DONE", job.getId());
			job.complete();
			// 마지막 묶음이나 넘겨 둔 부분만 처리한 작업은 더 넘길 것이 없다.
			Long carry = carryOnly || part.isLast() ? null : result.carryFromMs();
			part.setCarryFromMs(carry);
			part.setStatus(RecordingStatus.DONE);
			pruneUpTo(part, carry);
			return recordingService.dispatch(recording);
		});
		next.forEach(publisher::publishQuietly);
	}

	public void fail(long jobId, String reason) {
		List<Long> next = tx.execute(status -> {
			Long recordingId = jobs.findRecordingIdByJobId(jobId)
				.orElseThrow(() -> ApiException.invalid(null, "감지 작업을 찾을 수 없습니다."));
			Recording recording = recordings.findByIdForUpdate(recordingId).orElseThrow();
			Job job = jobs.findById(jobId).orElseThrow();
			if (job.isFinished()) {
				return List.<Long>of();
			}
			RecordingPart part = parts.findById(job.getRecordingPartId()).orElseThrow();
			boolean carryOnly = jobs.existsByRecordingPartIdAndStatusAndIdNot(part.getId(), "DONE", job.getId());
			job.fail(reason);
			// 넘겨 둔 부분만 처리하던 작업이면 묶음 감지는 이미 성공했으므로 DONE으로 둔다.
			part.setStatus(carryOnly ? RecordingStatus.DONE : RecordingStatus.FAILED);
			part.setCarryFromMs(null);
			pruneUpTo(part, null);
			return recordingService.dispatch(recording);
		});
		next.forEach(publisher::publishQuietly);
	}

	@Scheduled(fixedDelayString = "${lolog.detect.late-check-delay-ms:60000}")
	void dispatchLateCarries() {
		dispatchLateCarries(OffsetDateTime.now().minusMinutes(latePartMinutes));
	}

	// 다음 묶음이 오지 않은 채 before보다 오래된 묶음의 넘겨 둔 부분만 따로 감지한다(늦게 온 묶음 규칙).
	void dispatchLateCarries(OffsetDateTime before) {
		for (Long recordingId : parts.findRecordingIdsWaitingTooLong(before).stream().distinct().toList()) {
			List<Long> jobIds = tx.execute(status -> {
				Recording recording = recordings.findByIdForUpdate(recordingId).orElseThrow();
				List<RecordingPart> all = parts.findByRecordingIdOrderByPartNo(recordingId);
				RecordingPart last = all.getLast();
				boolean waiting = last.getStatus() == RecordingStatus.DONE && last.getCarryFromMs() != null
						&& !last.isLast() && last.getCreatedAt().isBefore(before);
				if (!waiting) {
					return List.<Long>of();
				}
				last.setStatus(RecordingStatus.DETECTING);
				recording.setStatus(RecordingStatus.DETECTING);
				return List.of(jobs.save(new Job(last.getId(), recording.getUserId())).getId());
			});
			jobIds.forEach(publisher::publishQuietly);
		}
	}

	// 명세의 제한 중 어노테이션으로 못 보는 것. 틀리면 400 INVALID_REQUEST와 틀린 칸의 경로.
	private void validate(List<DetectedCard> detected) {
		for (int i = 0; i < detected.size(); i++) {
			DetectedCard card = detected.get(i);
			String at = "cards[" + i + "]";
			if (card.endMs() <= card.startMs()) {
				throw ApiException.invalid(at + ".endMs", "카드의 끝은 시작보다 뒤여야 합니다.");
			}
			for (int k = 0; k < card.speakers().size(); k++) {
				if (!card.speakers().get(k).equals("화자" + (k + 1))) {
					throw ApiException.invalid(at + ".speakers[" + k + "]", "화자는 화자1부터 빠짐없이 써야 합니다.");
				}
			}
			for (int j = 0; j < card.lines().size(); j++) {
				DetectedLine line = card.lines().get(j);
				if (line.lineNo() != j + 1) {
					throw ApiException.invalid(at + ".lines[" + j + "].lineNo", "대사 번호는 1부터 차례로 써야 합니다.");
				}
				if (!card.speakers().contains(line.speaker())) {
					throw ApiException.invalid(at + ".lines[" + j + "].speaker", "speakers에 없는 화자입니다.");
				}
			}
		}
	}

	// 앞 묶음에서 이미 카드가 된 이야기는 버린다(기능 명세 "묶음 경계" ③, API DTO 명세 DetectResult).
	// 넘긴 글에 그 이야기가 섞여 다시 감지된 경우라, 같은 녹음의 카드와 시간 범위가 겹치면 같은 이야기로 본다.
	// 이번 결과 안의 카드끼리는 비교하지 않는다(워커가 겹치지 않게 고른다).
	private List<DetectedCard> fresh(long recordingId, List<DetectedCard> detected) {
		List<Card> existing = cards.findByRecordingIdOrderById(recordingId);
		return detected.stream()
			.filter(d -> existing.stream().noneMatch(c -> d.startMs() < c.getEndMs() && c.getStartMs() < d.endMs()))
			.toList();
	}

	private Card toCard(Recording recording, DetectedCard detected) {
		Card card = new Card(recording.getId(), recording.getUserId(), detected.kind(), detected.summary(),
				detected.startMs(), detected.endMs(), recording.getStartedAt().plusNanos(detected.startMs() * 1_000_000));
		detected.speakers().forEach(card::addSpeaker);
		detected.lines()
			.forEach(l -> card.addLine(l.lineNo(), l.speaker(), l.text(), l.startMs(), l.endMs()));
		return card;
	}

	// 감지가 끝난 대화 글을 지운다(FR-CARD-04). part까지의 묶음에서 carry 이후 구간만 다음 묶음을 위해 남긴다.
	private void pruneUpTo(RecordingPart part, Long carry) {
		for (RecordingPart p : parts.findByRecordingIdOrderByPartNo(part.getRecording().getId())) {
			if (p.getPartNo() > part.getPartNo() || p.getSentences() == null) {
				continue;
			}
			List<Sentence> kept = carry == null ? List.of()
					: publisher.read(p).stream().filter(s -> s.startMs() >= carry).toList();
			p.setSentences(kept.isEmpty() ? null : objectMapper.writeValueAsString(kept));
		}
	}

}
