package com.lolog.api.domain.recording.service;

import com.lolog.api.domain.job.entity.Job;
import com.lolog.api.domain.job.repository.JobRepository;
import com.lolog.api.domain.recording.dto.RecordingDtos.RecordingListItem;
import com.lolog.api.domain.recording.dto.RecordingDtos.RecordingPage;
import com.lolog.api.domain.recording.dto.RecordingDtos.StatusResponse;
import com.lolog.api.domain.recording.dto.RecordingDtos.UploadRequest;
import com.lolog.api.domain.recording.dto.RecordingDtos.UploadResponse;
import com.lolog.api.domain.recording.entity.Recording;
import com.lolog.api.domain.recording.entity.RecordingPart;
import com.lolog.api.domain.recording.entity.RecordingStatus;
import com.lolog.api.domain.recording.repository.RecordingPartRepository;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.web.PageRequestParams;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

// 녹음 묶음 받기, 녹음 조회, 묶음 감지 작업 넣기.
@Service
public class RecordingService {

	public static final int MAX_SENTENCES = 5000;

	private final RecordingRepository recordings;
	private final RecordingPartRepository parts;
	private final JobRepository jobs;
	private final DetectPublisher publisher;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate tx;

	/** 녹음 처리에 필요한 저장소와 도구를 받는다. */
	public RecordingService(RecordingRepository recordings, RecordingPartRepository parts, JobRepository jobs,
			DetectPublisher publisher, ObjectMapper objectMapper, TransactionTemplate tx) {
		this.recordings = recordings;
		this.parts = parts;
		this.jobs = jobs;
		this.publisher = publisher;
		this.objectMapper = objectMapper;
		this.tx = tx;
	}

	/**
	 * 녹음 묶음을 확인해 저장하고 감지 작업을 큐에 넣는다.
	 *
	 * @param userId 업로드한 회원 ID(토큰의 sub)
	 * @param request 받은 녹음 묶음
	 * @return 녹음 ID와 지금 상태
	 */
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

	/**
	 * 내 녹음 하나의 상태와 카드 수를 돌려준다.
	 *
	 * @param userId 요청한 회원 ID(토큰의 sub)
	 * @param id 녹음 ID
	 * @return 녹음 상태와 카드 수
	 */
	public StatusResponse status(long userId, long id) {
		Recording recording = recordings.findByIdAndUserId(id, userId)
			.orElseThrow(() -> new ApiException(ErrorCode.RECORDING_NOT_FOUND));
		return new StatusResponse(recording.getId(), recording.getStatus(), Math.toIntExact(recordings.countCards(id)));
	}

	/**
	 * 내 녹음 목록을 상태로 걸러 페이지로 돌려준다.
	 *
	 * @param userId 요청한 회원 ID(토큰의 sub)
	 * @param cursor 앞 페이지의 nextCursor. null이면 처음부터
	 * @param size 페이지 크기. null이면 20
	 * @param status 쉼표로 이은 상태 이름. null이거나 비면 전부
	 * @return 녹음 목록과 다음 cursor
	 */
	@Transactional(readOnly = true)
	public RecordingPage list(long userId, String cursor, Integer size, String status) {
		PageRequestParams params = PageRequestParams.of(cursor, size);
		List<Recording> page = recordings.findPage(userId, parseStatuses(status), params.before(),
				Limit.of(params.size() + 1));
		boolean more = page.size() > params.size();
		List<Recording> shown = more ? page.subList(0, params.size()) : page;
		List<Long> ids = shown.stream().map(Recording::getId).toList();
		Map<Long, Long> counts = ids.isEmpty() ? Map.of()
				: recordings.countCardsByRecordingIds(ids).stream()
					.collect(Collectors.toMap(r -> ((Number) r[0]).longValue(), r -> ((Number) r[1]).longValue()));
		List<RecordingListItem> items = shown.stream()
			.map(r -> new RecordingListItem(r.getId(), r.getClientRecordingId(), r.getStatus(), r.getStartedAt(),
					Math.toIntExact(counts.getOrDefault(r.getId(), 0L))))
			.toList();
		return new RecordingPage(items, more ? String.valueOf(shown.getLast().getId()) : null);
	}

	/**
	 * ?status=RECEIVED,DETECTING 처럼 쉼표로 잇는다. 빼거나 비우면 전부. 없는 상태 이름은 400.
	 *
	 * @param status 쉼표로 이은 상태 이름(null 가능)
	 * @return 걸러 볼 상태 집합
	 */
	private static Set<RecordingStatus> parseStatuses(String status) {
		if (status == null || status.isBlank()) {
			return EnumSet.allOf(RecordingStatus.class);
		}
		Set<RecordingStatus> result = EnumSet.noneOf(RecordingStatus.class);
		for (String name : status.split(",")) {
			try {
				result.add(RecordingStatus.valueOf(name.strip()));
			}
			catch (IllegalArgumentException e) {
				throw ApiException.invalid("status");
			}
		}
		return result;
	}

	/**
	 * 앞 묶음의 감지가 끝났을 때 다음 묶음을 넣는다. 워커 결과는 DetectResultService가 같은 트랜잭션 안에서 dispatch를 부른다.
	 *
	 * @param recordingId 다음 묶음을 넣을 녹음 ID
	 */
	public void dispatchNext(long recordingId) {
		List<Long> jobIds = tx.execute(status -> {
			Recording recording = recordings.findByIdForUpdate(recordingId).orElseThrow();
			return dispatch(recording);
		});
		jobIds.forEach(publisher::publishQuietly);
	}

	/**
	 * 문장 수와 각 문장의 시각을 확인한다.
	 *
	 * @param request 확인할 녹음 묶음
	 */
	private void validate(UploadRequest request) {
		if (request.sentences().size() > MAX_SENTENCES) {
			throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
		}
		for (int i = 0; i < request.sentences().size(); i++) {
			if (request.sentences().get(i).endMs() <= request.sentences().get(i).startMs()) {
				throw ApiException.invalid("sentences[" + i + "].endMs");
			}
		}
	}

	/**
	 * 녹음과 묶음을 저장하고 넣을 감지 작업을 만든다.
	 *
	 * @param userId 업로드한 회원 ID
	 * @param request 받은 녹음 묶음
	 * @return 업로드 응답과 큐에 넣을 작업 ID
	 */
	private Saved save(long userId, UploadRequest request) {
		Recording recording = recordings.findByUserIdAndClientRecordingId(userId, request.clientRecordingId())
			.orElseGet(() -> recordings.saveAndFlush(
					new Recording(userId, request.clientRecordingId(), request.startedAt(),
							Boolean.TRUE.equals(request.audioSaved()))));
		List<RecordingPart> existing = parts.findByRecordingIdOrderByPartNo(recording.getId());

		// 재시도면 저장하지 않고 처음 결과를 돌려준다.
		boolean duplicate = existing.stream().anyMatch(p -> p.getPartNo().equals(request.partNo()));
		if (duplicate) {
			return new Saved(response(recording), List.of());
		}
		if (!existing.isEmpty() && existing.getLast().isLast()) {
			throw new ApiException(ErrorCode.RECORDING_CLOSED);
		}
		if (request.partNo() != existing.size() + 1) {
			throw new ApiException(ErrorCode.PART_OUT_OF_ORDER);
		}
		parts.saveAndFlush(new RecordingPart(recording, request.partNo(), request.isLast(), request.durationSec(),
				objectMapper.writeValueAsString(request.sentences())));
		// 끝 묶음이 오지 않아도 7일을 셀 수 있게 묶음마다 고쳐 적는다.
		long receivedSec = existing.stream().mapToLong(RecordingPart::getDurationSec).sum() + request.durationSec();
		recording.setEndedAt(recording.getStartedAt().plusSeconds(receivedSec));
		List<Long> jobIds = dispatch(recording);
		return new Saved(response(recording), jobIds);
	}

	/**
	 * 앞 묶음이 모두 끝났으면 다음 묶음을 큐에 넣을 작업으로 만든다. 앞 묶음의 carryFromMs를 알아야 해서 한 번에 하나만 넣는다.
	 * 넣을 묶음이 없고 마지막 묶음까지 끝났으면 녹음 상태를 정한다. 부르는 쪽이 녹음 행을 잠근 트랜잭션 안에서 부른다.
	 *
	 * @param recording 잠근 녹음
	 * @return 큐에 넣을 작업 ID(없거나 하나)
	 */
	public List<Long> dispatch(Recording recording) {
		List<RecordingPart> all = parts.findByRecordingIdOrderByPartNo(recording.getId());
		boolean running = all.stream().anyMatch(p -> p.getStatus() == RecordingStatus.DETECTING);
		if (running) {
			return List.of();
		}
		for (RecordingPart part : all) {
			if (part.getStatus() != RecordingStatus.RECEIVED) {
				continue;
			}
			if (publisher.sentencesFor(part, all).isEmpty()) {
				// 새 글도 넘겨받을 글도 없으면 감지할 것이 없다.
				part.setStatus(RecordingStatus.DONE);
				continue;
			}
			part.setStatus(RecordingStatus.DETECTING);
			recording.setStatus(RecordingStatus.DETECTING);
			return List.of(jobs.save(new Job(part.getId(), recording.getUserId())).getId());
		}
		finishIfLastDone(recording, all);
		return List.of();
	}

	/**
	 * 마지막 묶음까지 끝났으면 DONE. 감지한 묶음이 모두 실패했을 때만 FAILED다(하나라도 성공했으면 DONE).
	 *
	 * @param recording 상태를 정할 녹음
	 * @param all 이 녹음의 묶음 전부(partNo 순)
	 */
	private void finishIfLastDone(Recording recording, List<RecordingPart> all) {
		if (all.isEmpty()) {
			return;
		}
		// 마지막 묶음 전에는 다음 묶음을 기다리는 동안에도 DETECTING이다(api-dto.md). 받은 묶음이 모두 빈 묶음이어도 같다.
		if (!all.getLast().isLast()) {
			recording.setStatus(RecordingStatus.DETECTING);
			return;
		}
		boolean allFinished = all.stream()
			.allMatch(p -> p.getStatus() == RecordingStatus.DONE || p.getStatus() == RecordingStatus.FAILED);
		if (!allFinished) {
			return;
		}
		boolean anyFailed = all.stream().anyMatch(p -> p.getStatus() == RecordingStatus.FAILED);
		jobs.flush();
		boolean anyDetected = jobs.countDoneByRecordingId(recording.getId()) > 0;
		recording.setStatus(anyFailed && !anyDetected ? RecordingStatus.FAILED : RecordingStatus.DONE);
	}

	/**
	 * 녹음을 업로드 응답 모양으로 바꾼다.
	 *
	 * @param recording 바꿀 녹음
	 * @return 녹음 ID와 상태
	 */
	private UploadResponse response(Recording recording) {
		return new UploadResponse(recording.getId(), recording.getStatus());
	}

	/**
	 * save 결과. 응답과 큐에 넣을 작업 ID.
	 *
	 * @param response 앱에 돌려줄 업로드 응답
	 * @param jobIds 커밋 뒤 큐에 넣을 작업 ID
	 */
	private record Saved(UploadResponse response, List<Long> jobIds) {
	}

}
