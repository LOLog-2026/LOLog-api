package com.lolog.api.sound;

import com.lolog.api.common.ApiException;
import com.lolog.api.common.SafeLog;
import com.lolog.api.storage.ImageStorage;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

// 쇼츠 효과음·배경음(sounds.md 5장, FR-EXT-03). 수집 스크립트가 Freesound CC0 소리를 넣고,
// 워커(3호)가 콘티가 고른 검색어 값(door_slam, bgm_comic 등)으로 그 소리 목록을 받아 jobId로 하나를 고른다.
// 파일은 서비스가 모아 둔 공용 파일이라 기본 화자 그림처럼 10분 서명 주소로 준다.
@Service
public class SoundService {

	private static final Logger log = LoggerFactory.getLogger(SoundService.class);

	static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
	static final int MAX_TITLE = 200;
	static final int MAX_SOURCE_URL = 500;
	static final Set<String> KINDS = Set.of("SFX", "BGM");
	static final String LICENSE = "CC0";
	// 영어 소문자·숫자·_ 1~30자(sounds.md 5장)
	private static final Pattern QUERY = Pattern.compile("[a-z0-9_]{1,30}");

	private final JdbcTemplate jdbc;
	private final ImageStorage storage;

	public SoundService(JdbcTemplate jdbc, ImageStorage storage) {
		this.jdbc = jdbc;
		this.storage = storage;
	}

	public record SoundItem(Long id, String kind, Integer durationMs, String url) {
	}

	public record SoundList(List<SoundItem> sounds) {
	}

	public record SoundSaved(Long id) {
	}

	// 소리 넣기·바꾸기. 같은 freesoundId면 덮어쓴다. id를 먼저 정해야 파일 키(defaults/sounds/{id}.ogg)가 정해져서
	// 줄을 먼저 넣고 같은 트랜잭션 안에서 파일을 올린다. 파일을 못 올리면 줄도 남지 않는다.
	@Transactional
	public SoundSaved upsert(long freesoundId, byte[] file, String kind, String query, String title, Integer durationMs,
			String sourceUrl, String license) {
		if (freesoundId <= 0) {
			throw ApiException.invalid("freesoundId", "freesoundId는 0보다 커야 해요.");
		}
		if (file == null || file.length == 0) {
			throw ApiException.invalid("file", "소리 파일이 없어요.");
		}
		if (file.length > MAX_FILE_BYTES) {
			throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "소리 파일이 너무 큽니다.");
		}
		if (!isOgg(file)) {
			throw ApiException.invalid("file", "ogg 파일만 받아요.");
		}
		String k = kind(kind);
		String q = query(query);
		String t = title == null || title.isBlank() ? null : title.strip();
		if (t != null && t.codePointCount(0, t.length()) > MAX_TITLE) {
			t = t.substring(0, t.offsetByCodePoints(0, MAX_TITLE));
		}
		if (durationMs == null || durationMs <= 0) {
			throw ApiException.invalid("durationMs", "durationMs는 0보다 커야 해요.");
		}
		String source = sourceUrl == null ? "" : sourceUrl.strip();
		if (source.isEmpty() || source.length() > MAX_SOURCE_URL
				|| !(source.startsWith("https://") || source.startsWith("http://"))) {
			throw ApiException.invalid("sourceUrl", "sourceUrl은 " + MAX_SOURCE_URL + "자 이하 http·https 주소예요.");
		}
		if (license != null && !LICENSE.equals(license.strip())) {
			throw ApiException.invalid("license", "CC0 소리만 받아요.");
		}
		// object_key는 id로 정해지니 넣은 뒤에 채운다(그 전에는 빈 값).
		long id = jdbc.queryForObject("""
				insert into sound_library (kind, query, title, object_key, duration_ms, freesound_id, source_url, license)
				values (?, ?, ?, '', ?, ?, ?, 'CC0')
				on conflict (freesound_id) do update set kind = excluded.kind, query = excluded.query, title = excluded.title,
				    duration_ms = excluded.duration_ms, source_url = excluded.source_url
				returning id
				""", Long.class, k, q, t, durationMs, freesoundId, source);
		String key = ImageStorage.soundKey(id);
		jdbc.update("update sound_library set object_key = ? where id = ?", key, id);
		storage.put(key, file, "audio/ogg");
		return new SoundSaved(id);
	}

	// 어울리지 않는 소리 빼기. 파일도 지운다. 없어도 된다.
	@Transactional
	public void delete(long freesoundId) {
		List<String> keys = jdbc.queryForList("delete from sound_library where freesound_id = ? returning object_key",
				String.class, freesoundId);
		if (!keys.isEmpty()) {
			String key = keys.getFirst();
			afterCommit(() -> {
				try {
					storage.delete(key);
				}
				catch (Exception e) {
					log.warn("소리 파일을 지우지 못했습니다.", SafeLog.withoutMessages(e));
				}
			});
		}
	}

	// 그 검색어로 모은 소리 목록(id 순서). 없으면 빈 목록이다(워커는 그 장면을 소리 없이 만든다).
	@Transactional(readOnly = true)
	public SoundList list(String kind, String query) {
		String k = kind(kind);
		String q = query(query);
		return new SoundList(jdbc.query("""
				select id, kind, duration_ms, object_key from sound_library where kind = ? and query = ? order by id
				""", (rs, n) -> new SoundItem(rs.getLong("id"), rs.getString("kind"), rs.getInt("duration_ms"),
				storage.presignedUrl(rs.getString("object_key")).toString()), k, q));
	}

	private static String kind(String kind) {
		if (kind == null || !KINDS.contains(kind)) {
			throw ApiException.invalid("kind", "kind는 SFX 또는 BGM이에요.");
		}
		return kind;
	}

	private static String query(String query) {
		if (query == null || !QUERY.matcher(query).matches()) {
			throw ApiException.invalid("query", "query는 영어 소문자·숫자·_ 1~30자예요.");
		}
		return query;
	}

	// Ogg 페이지는 "OggS"로 시작한다.
	static boolean isOgg(byte[] bytes) {
		return bytes.length >= 4 && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S';
	}

	private static void afterCommit(Runnable task) {
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

}
