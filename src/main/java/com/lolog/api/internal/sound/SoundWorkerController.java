package com.lolog.api.internal.sound;

import com.lolog.api.domain.sound.service.SoundService.SoundMatches;
import com.lolog.api.domain.sound.service.SoundService.SoundSearch;
import com.lolog.api.domain.sound.service.SoundService.SoundSaved;
import com.lolog.api.domain.sound.service.SoundService;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// 효과음·배경음 /internal(sounds.md 5장). 수집 스크립트와 워커(3호)가 X-Internal-Token으로 부른다. 앱용 /api는 없다.
@RestController
public class SoundWorkerController {

	private final SoundService sounds;

	/** 쓰는 서비스를 받는다. */
	public SoundWorkerController(SoundService sounds) {
		this.sounds = sounds;
	}

	/**
	 * 효과음·배경음을 넣거나 바꾼다.
	 *
	 * @param freesoundId Freesound 소리 ID(경로, 1 이상). 같으면 덮어쓴다
	 * @param file 소리 파일 파트 file(Ogg, 5MB 이하), 빠지면 400
	 * @param kind 종류(SFX 또는 BGM)
	 * @param query 모을 때 쓴 검색어(영어 소문자·숫자·_ 1~50자)
	 * @param title 소리 제목, 빼도 된다(200자를 넘으면 자른다)
	 * @param durationMs 소리 길이(ms, 1 이상)
	 * @param sourceUrl 소리 출처 주소(http·https, 500자 이하)
	 * @param license 라이선스(CC0만), 빼도 된다
	 * @param embedding CLAP 소리 벡터(숫자 512개 JSON 배열)
	 * @param embeddingModel 벡터를 만든 모델 이름(api 설정 모델과 같아야 한다)
	 * @return 넣거나 바꾼 소리 ID
	 */
	@PutMapping("/internal/sounds/{freesoundId}")
	public SoundSaved upsert(@PathVariable Long freesoundId,
			@RequestPart(name = "file", required = false) MultipartFile file,
			@RequestParam(required = false) String kind, @RequestParam(required = false) String query,
			@RequestParam(required = false) String title, @RequestParam(required = false) Integer durationMs,
			@RequestParam(required = false) String sourceUrl, @RequestParam(required = false) String license,
			@RequestParam(required = false) String embedding, @RequestParam(required = false) String embeddingModel)
			throws IOException {
		return sounds.upsert(freesoundId, file == null ? null : file.getBytes(), kind, query, title, durationMs, sourceUrl,
				license, embedding, embeddingModel);
	}

	/**
	 * 효과음·배경음을 지운다.
	 *
	 * @param freesoundId 뺄 소리의 Freesound ID(경로)
	 */
	@DeleteMapping("/internal/sounds/{freesoundId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long freesoundId) {
		sounds.delete(freesoundId);
	}

	/**
	 * 설명 글과 가까운 효과음·배경음을 찾는다.
	 *
	 * @param request 본문 { kind, text, limit }. kind는 SFX·BGM, text는 설명 글(200자 이하), limit는 기본 5·최대 10
	 * @return 가까운 순 소리 목록(파일은 10분 서명 주소)
	 */
	@PostMapping("/internal/sounds/search")
	public SoundMatches search(@RequestBody(required = false) SoundSearch request) {
		return sounds.search(request);
	}

}
