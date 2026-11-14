package com.lolog.api.sound;

import com.lolog.api.sound.SoundService.SoundList;
import com.lolog.api.sound.SoundService.SoundSaved;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
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

	public SoundWorkerController(SoundService sounds) {
		this.sounds = sounds;
	}

	@PutMapping("/internal/sounds/{freesoundId}")
	public SoundSaved upsert(@PathVariable Long freesoundId,
			@RequestPart(name = "file", required = false) MultipartFile file,
			@RequestParam(required = false) String kind, @RequestParam(required = false) String query,
			@RequestParam(required = false) String title, @RequestParam(required = false) Integer durationMs,
			@RequestParam(required = false) String sourceUrl, @RequestParam(required = false) String license)
			throws IOException {
		return sounds.upsert(freesoundId, file == null ? null : file.getBytes(), kind, query, title, durationMs, sourceUrl,
				license);
	}

	@DeleteMapping("/internal/sounds/{freesoundId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long freesoundId) {
		sounds.delete(freesoundId);
	}

	@GetMapping("/internal/sounds")
	public SoundList list(@RequestParam(required = false) String kind, @RequestParam(required = false) String query) {
		return sounds.list(kind, query);
	}

}
