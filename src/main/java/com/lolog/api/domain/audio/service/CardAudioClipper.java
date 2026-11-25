package com.lolog.api.domain.audio.service;

import com.lolog.api.domain.audio.repository.RecordingAudioFileRepository;
import com.lolog.api.domain.card.entity.Card;
import com.lolog.api.domain.card.repository.CardRepository;
import com.lolog.api.domain.recording.entity.Recording;
import com.lolog.api.domain.recording.repository.RecordingPartRepository;
import com.lolog.api.domain.recording.repository.RecordingRepository;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.storage.StorageDeletions;
import com.lolog.api.global.transaction.AfterCommit;
import com.lolog.api.global.util.SafeLog;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

// 카드 구간 음성을 전체 녹음 파일에서 잘라 따로 둔다(audio.md 4장). 전체 파일은 7일 뒤 지워지기 때문이다.
// 카드가 생겼을 때와 음성 파일이 올라왔을 때 모두 부르고, 둘 다 갖춰진 쪽에서 잘린다.
// 요청에 답한 뒤 따로 자른다. 실패하면 로그만 남기고, 한 시간마다 도는 AudioRetentionJob이 다시 자른다.
@Service
public class CardAudioClipper implements DisposableBean {

	private static final Logger log = LoggerFactory.getLogger(CardAudioClipper.class);

	// 카드 구간 앞뒤 여유
	static final long MARGIN_MS = 500;
	// 파일 경계가 앱 계산 오차로 조금 어긋나도 이어진 것으로 본다(조각 하나 길이).
	private static final long GAP_TOLERANCE_MS = 64;

	private final CardRepository cards;
	private final RecordingRepository recordings;
	private final RecordingPartRepository parts;
	private final RecordingAudioFileRepository audioFiles;
	private final ImageStorage storage;
	private final StorageDeletions deletions;
	// 응답을 붙잡지 않게 따로 자른다. 빈으로 두지 않는다(Spring 기본 작업 실행기를 대신하지 않게).
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	/** 쓰는 저장소와 도구를 받는다. */
	public CardAudioClipper(CardRepository cards, RecordingRepository recordings, RecordingPartRepository parts,
			RecordingAudioFileRepository audioFiles, ImageStorage storage, StorageDeletions deletions) {
		this.cards = cards;
		this.recordings = recordings;
		this.parts = parts;
		this.audioFiles = audioFiles;
		this.storage = storage;
		this.deletions = deletions;
	}

	// 서버가 꺼질 때 자르기 작업 실행기를 닫는다.
	@Override
	public void destroy() {
		executor.close();
	}

	/**
	 * 녹음 음성 파일 하나의 키와 구간.
	 *
	 * @param seq 음성 파일 번호
	 * @param key 저장소의 음성 파일 키
	 * @param startMs 파일이 시작하는 녹음 시작부터의 시각(ms)
	 * @param endMs 파일이 끝나는 녹음 시작부터의 시각(ms)
	 */
	private record AudioFile(int seq, String key, long startMs, long endMs) {
	}

	/**
	 * 녹음의 카드 중 아직 음성이 없는 카드를 모두 자른다. 트랜잭션 안에서 부르면 커밋된 뒤에 시작한다.
	 *
	 * @param recordingId 카드를 자를 녹음 ID
	 */
	public void clipRecordingLater(long recordingId) {
		AfterCommit.run(() -> executor.execute(() -> clipRecording(recordingId)));
	}

	/**
	 * 녹음의 카드 중 음성이 없는 카드를 바로 자른다.
	 *
	 * @param recordingId 카드를 자를 녹음 ID
	 */
	void clipRecording(long recordingId) {
		List<Long> cardIds = cards.findIdsWithoutAudio(recordingId);
		cardIds.forEach(this::clipQuietly);
	}

	/**
	 * 카드 하나를 자르고, 실패하면 로그만 남긴다.
	 *
	 * @param cardId 구간 음성을 자를 카드 ID
	 */
	void clipQuietly(long cardId) {
		try {
			clip(cardId);
		}
		catch (Exception e) {
			log.warn("카드 {}의 구간 음성을 자르지 못했습니다. 한 시간 뒤 다시 자릅니다.", cardId, SafeLog.withoutMessages(e));
		}
	}

	/**
	 * 잘랐으면 true. 음성 저장을 끈 녹음이거나, 구간을 덮는 파일이 아직 다 오지 않았거나, 이미 잘랐으면 false.
	 *
	 * @param cardId 구간 음성을 자를 카드 ID
	 * @return 잘라서 카드에 붙였으면 true
	 */
	boolean clip(long cardId) {
		Card card = cards.findById(cardId).orElse(null);
		if (card == null || card.getAudioKey() != null || card.getRecordingId() == null) {
			return false;
		}
		boolean audioSaved = recordings.findById(card.getRecordingId()).map(Recording::isAudioSaved).orElse(false);
		if (!audioSaved) {
			return false;
		}
		List<AudioFile> files = audioFiles.findByRecordingIdOrderByStartMsAscSeqAsc(card.getRecordingId()).stream()
			.map(f -> new AudioFile(f.getSeq(), f.getAudioKey(), f.getStartMs(), f.getEndMs()))
			.toList();
		if (files.isEmpty()) {
			return false;
		}

		long from = Math.max(0, card.getStartMs() - MARGIN_MS);
		long to = card.getEndMs() + MARGIN_MS;
		// 마지막 묶음까지 받은 녹음이면 뒤 여유가 녹음 끝을 넘지 않게 줄인다. 아니면 뒤 파일이 올 때까지 기다린다.
		boolean closed = parts.existsLast(card.getRecordingId());
		if (closed) {
			to = Math.min(to, files.stream().mapToLong(AudioFile::endMs).max().orElseThrow());
		}
		if (to <= from) {
			return false;
		}
		List<AudioFile> covering = covering(files, from, to);
		if (covering == null) {
			return false;
		}

		ByteArrayOutputStream clip = new ByteArrayOutputStream();
		long durationUs = 0;
		// 앞 파일에서 넣은 마지막 조각의 끝(마이크로초). 파일 경계가 겹치면 같은 소리를 두 번 넣지 않는다.
		long writtenUntilUs = Long.MIN_VALUE;
		for (AudioFile file : covering) {
			byte[] data = storage.get(file.key());
			long frameStartUs = file.startMs() * 1000;
			for (Adts.Frame frame : Adts.frames(data)) {
				long frameEndUs = frameStartUs + frame.durationUs();
				boolean inRange = frameStartUs < to * 1000 && frameEndUs > from * 1000;
				boolean fresh = frameStartUs + frame.durationUs() / 2 >= writtenUntilUs;
				if (inRange && fresh) {
					clip.write(data, frame.offset(), frame.length());
					durationUs += frame.durationUs();
					writtenUntilUs = frameEndUs;
				}
				frameStartUs = frameEndUs;
			}
		}
		if (clip.size() == 0) {
			return false;
		}

		String key = ImageStorage.cardAudioKey(card.getUserId(), card.getId());
		storage.putAac(key, clip.toByteArray());
		int updated = cards.attachAudio(card.getId(), key, Math.round(durationUs / 1000.0));
		// 그 사이 카드가 지워졌으면(탈퇴 포함) 올린 파일도 지운다. 다른 쪽이 먼저 잘랐으면 같은 키라 그대로 둔다.
		// 카드가 없어 다시 자르기가 이 카드를 찾지 않으니, 못 지우면 지울 줄로 남겨 다시 지운다.
		if (updated == 0 && !cards.existsById(card.getId())) {
			deletions.schedule(key);
			return false;
		}
		return updated > 0;
	}

	/**
	 * [from, to)를 빈틈없이 덮는 파일들. 빈틈이 있으면(아직 안 올라온 파일) null.
	 *
	 * @param files 녹음의 음성 파일(시작 시각 오름차순)
	 * @param from 덮을 구간 시작(ms, 포함)
	 * @param to 덮을 구간 끝(ms, 미포함)
	 * @return 구간을 덮는 파일들. 빈틈이 있으면 null
	 */
	private static List<AudioFile> covering(List<AudioFile> files, long from, long to) {
		long cursor = from;
		List<AudioFile> result = new java.util.ArrayList<>();
		for (AudioFile file : files) {
			if (cursor >= to) {
				break;
			}
			if (file.endMs() <= cursor) {
				continue;
			}
			if (file.startMs() > cursor + GAP_TOLERANCE_MS) {
				return null;
			}
			result.add(file);
			cursor = file.endMs();
		}
		return cursor >= to ? result : null;
	}

}
