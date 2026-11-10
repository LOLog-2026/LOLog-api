package com.lolog.api.audio;

import com.lolog.api.common.SafeLog;
import com.lolog.api.storage.ImageStorage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

	private final JdbcTemplate jdbc;
	private final ImageStorage storage;
	// 응답을 붙잡지 않게 따로 자른다. 빈으로 두지 않는다(Spring 기본 작업 실행기를 대신하지 않게).
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	public CardAudioClipper(JdbcTemplate jdbc, ImageStorage storage) {
		this.jdbc = jdbc;
		this.storage = storage;
	}

	@Override
	public void destroy() {
		executor.close();
	}

	private record CardRow(long id, long userId, Long recordingId, long startMs, long endMs, String audioKey) {
	}

	private record AudioFile(int seq, String key, long startMs, long endMs) {
	}

	// 녹음의 카드 중 아직 음성이 없는 카드를 모두 자른다. 트랜잭션 안에서 부르면 커밋된 뒤에 시작한다.
	public void clipRecordingLater(long recordingId) {
		Runnable task = () -> executor.execute(() -> clipRecording(recordingId));
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					task.run();
				}
			});
		}
		else {
			task.run();
		}
	}

	void clipRecording(long recordingId) {
		List<Long> cardIds = jdbc.queryForList(
				"select id from cards where recording_id = ? and audio_key is null order by id", Long.class, recordingId);
		cardIds.forEach(this::clipQuietly);
	}

	void clipQuietly(long cardId) {
		try {
			clip(cardId);
		}
		catch (Exception e) {
			log.warn("카드 {}의 구간 음성을 자르지 못했습니다. 한 시간 뒤 다시 자릅니다.", cardId, SafeLog.withoutMessages(e));
		}
	}

	// 잘랐으면 true. 음성 저장을 끈 녹음이거나, 구간을 덮는 파일이 아직 다 오지 않았거나, 이미 잘랐으면 false.
	boolean clip(long cardId) {
		CardRow card = jdbc.query(
				"select id, user_id, recording_id, start_ms, end_ms, audio_key from cards where id = ?",
				(rs, n) -> new CardRow(rs.getLong("id"), rs.getLong("user_id"), (Long) rs.getObject("recording_id"),
						rs.getLong("start_ms"), rs.getLong("end_ms"), rs.getString("audio_key")),
				cardId).stream().findFirst().orElse(null);
		if (card == null || card.audioKey() != null || card.recordingId() == null) {
			return false;
		}
		Boolean audioSaved = jdbc.query("select audio_saved from recordings where id = ?",
				(rs, n) -> rs.getBoolean("audio_saved"), card.recordingId()).stream().findFirst().orElse(false);
		if (!audioSaved) {
			return false;
		}
		List<AudioFile> files = jdbc.query("""
				select seq, audio_key, start_ms, end_ms from recording_audio_files
				where recording_id = ? order by start_ms, seq
				""", (rs, n) -> new AudioFile(rs.getInt("seq"), rs.getString("audio_key"), rs.getLong("start_ms"),
				rs.getLong("end_ms")), card.recordingId());
		if (files.isEmpty()) {
			return false;
		}

		long from = Math.max(0, card.startMs() - MARGIN_MS);
		long to = card.endMs() + MARGIN_MS;
		// 마지막 묶음까지 받은 녹음이면 뒤 여유가 녹음 끝을 넘지 않게 줄인다. 아니면 뒤 파일이 올 때까지 기다린다.
		boolean closed = Boolean.TRUE.equals(jdbc.queryForObject(
				"select exists (select 1 from recording_parts where recording_id = ? and is_last)", Boolean.class,
				card.recordingId()));
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

		String key = ImageStorage.cardAudioKey(card.userId(), card.id());
		storage.putAac(key, clip.toByteArray());
		int updated = jdbc.update("""
				update cards set audio_key = ?, audio_duration_ms = ?, updated_at = current_timestamp
				where id = ? and audio_key is null
				""", key, Math.round(durationUs / 1000.0), card.id());
		// 그 사이 카드가 지워졌으면(탈퇴 포함) 올린 파일도 지운다. 다른 쪽이 먼저 잘랐으면 같은 키라 그대로 둔다.
		if (updated == 0 && !Boolean.TRUE.equals(
				jdbc.queryForObject("select exists (select 1 from cards where id = ?)", Boolean.class, card.id()))) {
			storage.delete(key);
			return false;
		}
		return updated > 0;
	}

	// [from, to)를 빈틈없이 덮는 파일들. 빈틈이 있으면(아직 안 올라온 파일) null.
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
