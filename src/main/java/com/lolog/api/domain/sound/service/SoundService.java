package com.lolog.api.domain.sound.service;

import com.lolog.api.domain.sound.entity.Sound;
import com.lolog.api.domain.sound.repository.SoundRepository;
import com.lolog.api.domain.sound.repository.SoundRepository.SoundMatch;
import com.lolog.api.global.error.ApiException;
import com.lolog.api.global.error.ErrorCode;
import com.lolog.api.global.storage.ImageStorage;
import com.lolog.api.global.storage.StorageDeletions;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// 쇼츠 효과음·배경음(sounds.md 5장, FR-EXT-03). 수집 스크립트가 Freesound CC0 소리를 CLAP 소리 벡터와 함께 넣고,
// 워커(3호)가 콘티의 소리 설명 글("door slamming" 등)을 보내면 그 글 벡터와 가까운 소리를 돌려준다.
// 워커는 그중 하나를 videoId로 고른다.
// 파일은 서비스가 모아 둔 공용 파일이라 기본 화자 그림처럼 10분 서명 주소로 준다.
@Service
public class SoundService {

	public static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
	static final int MAX_TITLE = 200;
	static final int MAX_SOURCE_URL = 500;
	static final Set<String> KINDS = Set.of("SFX", "BGM");
	static final String LICENSE = "CC0";
	// 모을 때 쓴 검색어. 영어 소문자·숫자·_ 1~50자(sounds.md 5장)
	private static final Pattern QUERY = Pattern.compile("[a-z0-9_]{1,50}");
	static final int MAX_TEXT = 200;
	static final int DEFAULT_LIMIT = 5;
	static final int MAX_LIMIT = 10;
	// 코사인 거리는 0~2다. 기준이 없으면 모두 돌려준다.
	static final double NO_MAX_DISTANCE = 2.0;

	private final SoundRepository repository;
	private final ImageStorage storage;
	private final StorageDeletions deletions;
	private final SoundTextEmbedder embedder;
	private final ObjectMapper objectMapper;
	// 효과음과 배경음은 거리 분포가 달라 기준을 따로 둔다(배경음은 서로 가깝게 몰린다).
	private final double sfxMaxDistance;
	private final double bgmMaxDistance;

	/**
	 * 리포지토리, 저장소, 글 벡터 모델과 종류별 거리 기준을 받는다.
	 *
	 * @param repository 소리 리포지토리
	 * @param storage 소리 파일 저장소
	 * @param deletions 저장소 파일 지우기 예약
	 * @param embedder 설명 글을 글 벡터로 바꾸는 모델
	 * @param objectMapper 소리 벡터 JSON을 읽는 도구
	 * @param sfxMaxDistance 효과음 검색의 최대 코사인 거리(lolog.sound.max-distance.sfx). 없으면 2.0(모두)
	 * @param bgmMaxDistance 배경음 검색의 최대 코사인 거리(lolog.sound.max-distance.bgm). 없으면 2.0(모두)
	 */
	public SoundService(SoundRepository repository, ImageStorage storage, StorageDeletions deletions,
			SoundTextEmbedder embedder, ObjectMapper objectMapper,
			@Value("${lolog.sound.max-distance.sfx:#{null}}") Double sfxMaxDistance,
			@Value("${lolog.sound.max-distance.bgm:#{null}}") Double bgmMaxDistance) {
		this.repository = repository;
		this.storage = storage;
		this.deletions = deletions;
		this.embedder = embedder;
		this.objectMapper = objectMapper;
		this.sfxMaxDistance = sfxMaxDistance == null ? NO_MAX_DISTANCE : sfxMaxDistance;
		this.bgmMaxDistance = bgmMaxDistance == null ? NO_MAX_DISTANCE : bgmMaxDistance;
	}

	/**
	 * 소리 목록 응답.
	 * 넣거나 바꾼 소리의 ID.
	 *
	 * @param id 넣거나 바꾼 소리의 ID
	 */
	public record SoundSaved(Long id) {
	}

	/**
	 * 검색 요청(POST /internal/sounds/search).
	 *
	 * @param kind SFX 또는 BGM
	 * @param text 콘티의 소리 설명 글(1~200자)
	 * @param limit 돌려줄 최대 개수(기본 5, 1~10)
	 */
	public record SoundSearch(String kind, String text, Integer limit) {
	}

	/**
	 * 검색 결과 소리 한 개. distance는 설명 글과의 코사인 거리(작을수록 가까움)다.
	 *
	 * @param id 소리 ID
	 * @param kind SFX 또는 BGM
	 * @param durationMs 소리 길이(ms)
	 * @param distance 설명 글과의 코사인 거리
	 * @param url 소리 파일의 10분 서명 주소
	 */
	public record SoundMatchItem(Long id, String kind, Integer durationMs, Double distance, String url) {
	}

	/**
	 * 검색 결과.
	 *
	 * @param sounds 가까운 소리 목록(거리 순). 맞는 소리가 없으면 빈 목록
	 */
	public record SoundMatches(List<SoundMatchItem> sounds) {
	}

	/**
	 * 소리 넣기·바꾸기. 같은 freesoundId면 덮어쓴다. id를 먼저 정해야 파일 키(defaults/sounds/{id}.ogg)가 정해져서
	 * 줄을 먼저 넣고 같은 트랜잭션 안에서 파일을 올린다. 파일을 못 올리면 줄도 남지 않는다.
	 *
	 * @param freesoundId Freesound의 소리 ID(1 이상)
	 * @param file Ogg 소리 파일(5MB 이하)
	 * @param kind SFX 또는 BGM
	 * @param query 모을 때 쓴 검색어(영어 소문자·숫자·_ 1~50자)
	 * @param title 소리 제목(null 가능, 200자를 넘으면 자른다)
	 * @param durationMs 소리 길이(ms, 1 이상)
	 * @param sourceUrl Freesound 원본 주소(http·https, 500자 이하)
	 * @param license 라이선스(null 가능, 있으면 CC0만)
	 * @param embedding 소리 벡터(숫자 512개 JSON 배열)
	 * @param embeddingModel 소리 벡터를 만든 모델 이름. api 설정의 모델과 같아야 한다
	 * @return 넣거나 바꾼 소리의 ID
	 */
	@Transactional
	public SoundSaved upsert(long freesoundId, byte[] file, String kind, String query, String title, Integer durationMs,
			String sourceUrl, String license, String embedding, String embeddingModel) {
		if (freesoundId <= 0) {
			throw ApiException.invalid("freesoundId");
		}
		if (file == null || file.length == 0) {
			throw ApiException.invalid("file");
		}
		if (file.length > MAX_FILE_BYTES) {
			throw new ApiException(ErrorCode.PAYLOAD_TOO_LARGE);
		}
		if (!isOgg(file)) {
			throw ApiException.invalid("file");
		}
		String k = kind(kind);
		String q = query(query);
		String t = title == null || title.isBlank() ? null : title.strip();
		if (t != null && t.codePointCount(0, t.length()) > MAX_TITLE) {
			t = t.substring(0, t.offsetByCodePoints(0, MAX_TITLE));
		}
		if (durationMs == null || durationMs <= 0) {
			throw ApiException.invalid("durationMs");
		}
		String source = sourceUrl == null ? "" : sourceUrl.strip();
		if (source.isEmpty() || source.length() > MAX_SOURCE_URL
				|| !(source.startsWith("https://") || source.startsWith("http://"))) {
			throw ApiException.invalid("sourceUrl");
		}
		if (license != null && !LICENSE.equals(license.strip())) {
			throw ApiException.invalid("license");
		}
		String vector = vectorLiteral(parseEmbedding(embedding));
		// 검색은 api 설정의 모델로 만든 벡터만 보니, 다른 모델 벡터는 넣지 않는다.
		if (embeddingModel == null || !embedder.modelName().equals(embeddingModel.strip())) {
			throw ApiException.invalid("embeddingModel");
		}
		// object_key는 id로 정해지니 넣은 뒤에 채운다(그 전에는 빈 값).
		long id = repository.upsert(freesoundId, k, q, t, durationMs, source, vector, embedder.modelName());
		String key = ImageStorage.soundKey(id);
		repository.findById(id).orElseThrow().assignObjectKey(key);
		storage.put(key, file, "audio/ogg");
		return new SoundSaved(id);
	}

	/**
	 * 어울리지 않는 소리 빼기. 파일도 지운다. 없어도 된다.
	 *
	 * @param freesoundId 뺄 소리의 Freesound ID
	 */
	@Transactional
	public void delete(long freesoundId) {
		repository.deleteByFreesoundIdReturningKey(freesoundId).ifPresent(deletions::schedule);
	}

	/**
	 * 설명 글과 가까운 소리를 거리 순으로 준다. 맞는 소리가 없으면 빈 목록이다(워커는 그 장면을 소리 없이 만든다).
	 * 글 벡터 모델을 못 읽으면 503 EMBEDDING_UNAVAILABLE이다.
	 *
	 * @param request 워커가 보낸 검색 요청
	 * @return 가까운 소리와 서명 주소
	 */
	@Transactional(readOnly = true)
	public SoundMatches search(SoundSearch request) {
		if (request == null) {
			throw ApiException.invalid("kind");
		}
		String k = kind(request.kind());
		String text = request.text() == null ? "" : request.text().strip();
		if (text.isEmpty() || text.codePointCount(0, text.length()) > MAX_TEXT) {
			throw ApiException.invalid("text");
		}
		int limit = request.limit() == null ? DEFAULT_LIMIT : request.limit();
		if (limit < 1 || limit > MAX_LIMIT) {
			throw ApiException.invalid("limit");
		}
		String vector = vectorLiteral(embedder.embed(text));
		List<SoundMatch> found = repository.searchNearest(k, vector, embedder.modelName(),
				"BGM".equals(k) ? bgmMaxDistance : sfxMaxDistance, limit);
		return new SoundMatches(found.stream()
			.map(s -> new SoundMatchItem(s.getId(), s.getKind(), s.getDurationMs(), s.getDistance(),
					storage.presignedUrl(s.getObjectKey()).toString()))
			.toList());
	}

	/**
	 * 소리 벡터(숫자 512개 JSON 배열)를 읽는다. 길이가 다르거나 숫자가 아니거나 모두 0이면 400이다.
	 *
	 * @param embedding 소리 벡터 JSON 배열 글
	 * @return 읽은 벡터
	 */
	private float[] parseEmbedding(String embedding) {
		if (embedding == null || embedding.isBlank()) {
			throw ApiException.invalid("embedding");
		}
		double[] values;
		try {
			values = objectMapper.readValue(embedding, double[].class);
		}
		catch (JacksonException e) {
			throw ApiException.invalid("embedding");
		}
		if (values == null || values.length != ClapTextEmbedder.DIMENSIONS) {
			throw ApiException.invalid("embedding");
		}
		float[] out = new float[values.length];
		boolean nonZero = false;
		for (int i = 0; i < values.length; i++) {
			if (!Double.isFinite(values[i])) {
				throw ApiException.invalid("embedding");
			}
			out[i] = (float) values[i];
			nonZero |= out[i] != 0;
		}
		if (!nonZero) {
			throw ApiException.invalid("embedding");
		}
		return out;
	}

	/**
	 * pgvector가 읽는 '[0.1,0.2,...]' 모양 글로 바꾼다.
	 *
	 * @param vector 바꿀 벡터
	 * @return '[0.1,0.2,...]' 모양 글
	 */
	static String vectorLiteral(float[] vector) {
		StringBuilder sb = new StringBuilder(vector.length * 12).append('[');
		for (int i = 0; i < vector.length; i++) {
			if (i > 0) {
				sb.append(',');
			}
			sb.append(vector[i]);
		}
		return sb.append(']').toString();
	}

	/**
	 * SFX·BGM만 받는다.
	 *
	 * @param kind 확인할 소리 종류
	 * @return 받은 kind 그대로
	 */
	private static String kind(String kind) {
		if (kind == null || !KINDS.contains(kind)) {
			throw ApiException.invalid("kind");
		}
		return kind;
	}

	/**
	 * 검색어 모양을 확인한다.
	 *
	 * @param query 확인할 검색어
	 * @return 받은 query 그대로
	 */
	private static String query(String query) {
		if (query == null || !QUERY.matcher(query).matches()) {
			throw ApiException.invalid("query");
		}
		return query;
	}

	/**
	 * Ogg 페이지는 "OggS"로 시작한다.
	 *
	 * @param bytes 확인할 파일 내용
	 * @return OggS로 시작하면 true
	 */
	static boolean isOgg(byte[] bytes) {
		return bytes.length >= 4 && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S';
	}

}
