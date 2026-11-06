package com.lolog.api.recording;

import com.lolog.api.common.ApiException;
import com.lolog.api.recording.RecordingDtos.StatusResponse;
import com.lolog.api.recording.RecordingDtos.UploadRequest;
import com.lolog.api.recording.RecordingDtos.UploadResponse;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@Service
public class RecordingService {

	static final int MAX_SENTENCES = 5000;

	private final RecordingRepository recordings;
	private final RecordingPartRepository parts;
	private final DetectJobRepository jobs;
	private final DetectPublisher publisher;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;

	public RecordingService(RecordingRepository recordings, RecordingPartRepository parts, DetectJobRepository jobs,
			DetectPublisher publisher, ObjectMapper objectMapper, TransactionTemplate tx) {
		this.recordings = recordings;
		this.parts = parts;
		this.jobs = jobs;
		this.publisher = publisher;
		this.objectMapper = objectMapper;
		this.tx = tx;
	}

	public UploadResponse upload(long userId, UploadRequest request) {
		validate(request);
		Saved saved;
		try {
			saved = tx.execute(status -> save(userId, request));
		}
		catch (DataIntegrityViolationException e) {
			// 같은 녹음의 첫 묶음이 동시에 와서 UNIQUE에 걸린 경우. 이제 행이 있으므로 다시 한 번만 시도한다.
			saved = tx.execute(status -> save(userId, request));
		}
		saved.jobIds().forEach(publisher::publishQuietly);
		return saved.response();
	}

	public StatusResponse status(long userId, long id) {
		Recording recording = recordings.findByIdAndUserId(id, userId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "RECORDING_NOT_FOUND", "녹음을 찾을 수 없습니다."));
		return new StatusResponse(recording.getId(), recording.getStatus(), Math.toIntExact(recordings.countCards(id)));
	}

	// 앞 묶음의 감지가 끝났을 때 부른다. 워커 결과 API(#3)에서 쓴다.
	public void dispatchNext(long recordingId) {
		List<Long> jobIds = tx.execute(status -> {
			Recording recording = recordings.findByIdForUpdate(recordingId).orElseThrow();
			return dispatch(recording);
		});
		jobIds.forEach(publisher::publishQuietly);
	}

	private void validate(UploadRequest request) {
		if (request.sentences().size() > MAX_SENTENCES) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "묶음의 구간이 너무 많습니다.");
		}
		boolean badTime = request.sentences().stream().anyMatch(s -> s.endMs() <= s.startMs());
		if (badTime) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "구간의 끝은 시작보다 뒤여야 합니다.");
		}
	}

	private Saved save(long userId, UploadRequest request) {
		Recording recording = recordings.findByUserIdAndClientRecordingId(userId, request.clientRecordingId())
			.orElseGet(() -> recordings.saveAndFlush(
					new Recording(userId, request.clientRecordingId(), request.startedAt())));
		List<RecordingPart> existing = parts.findByRecordingIdOrderByPartNo(recording.getId());

		// 재시도면 저장하지 않고 처음 결과를 돌려준다.
		boolean duplicate = existing.stream().anyMatch(p -> p.getPartNo().equals(request.partNo()));
		if (duplicate) {
			return new Saved(response(recording), List.of());
		}
		if (!existing.isEmpty() && existing.getLast().isLast()) {
			throw new ApiException(HttpStatus.CONFLICT, "RECORDING_CLOSED", "이미 끝난 녹음입니다.");
		}
		if (request.partNo() != existing.size() + 1) {
			throw new ApiException(HttpStatus.CONFLICT, "PART_OUT_OF_ORDER", "다음 묶음 번호는 " + (existing.size() + 1) + "입니다.");
		}
		parts.saveAndFlush(new RecordingPart(recording, request.partNo(), request.isLast(), request.durationSec(),
				objectMapper.writeValueAsString(request.sentences())));
		List<Long> jobIds = dispatch(recording);
		return new Saved(response(recording), jobIds);
	}

	// 앞 묶음이 모두 끝났으면 다음 묶음을 큐에 넣을 작업으로 만든다. 앞 묶음의 carryFromMs를 알아야 해서 한 번에 하나만 넣는다.
	private List<Long> dispatch(Recording recording) {
		List<RecordingPart> all = parts.findByRecordingIdOrderByPartNo(recording.getId());
		boolean running = all.stream().anyMatch(p -> p.getStatus() == RecordingStatus.DETECTING);
		if (running) {
			return List.of();
		}
		List<Long> jobIds = new ArrayList<>();
		for (RecordingPart part : all) {
			if (part.getStatus() != RecordingStatus.RECEIVED) {
				continue;
			}
			if (publisher.sentencesFor(part, all).isEmpty()) {
				// 새 글도 넘겨받을 글도 없으면 감지할 것이 없다.
				part.setStatus(RecordingStatus.DONE);
				if (part.isLast()) {
					recording.setStatus(RecordingStatus.DONE);
				}
				continue;
			}
			part.setStatus(RecordingStatus.DETECTING);
			recording.setStatus(RecordingStatus.DETECTING);
			jobIds.add(jobs.save(new DetectJob(part.getId(), recording.getUserId())).getId());
			break;
		}
		return jobIds;
	}

	private UploadResponse response(Recording recording) {
		return new UploadResponse(recording.getId(), recording.getStatus());
	}

	private record Saved(UploadResponse response, List<Long> jobIds) {
	}

}
