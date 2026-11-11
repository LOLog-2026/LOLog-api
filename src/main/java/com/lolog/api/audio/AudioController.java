package com.lolog.api.audio;

import com.lolog.api.auth.CurrentUserProvider;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// 녹음 음성 파일 올리기(audio.md 6장). multipart file(audio/aac), startMs, endMs. 204.
@RestController
public class AudioController {

	private final AudioUploadService audio;
	private final CurrentUserProvider currentUser;

	public AudioController(AudioUploadService audio, CurrentUserProvider currentUser) {
		this.audio = audio;
		this.currentUser = currentUser;
	}

	// startMs·endMs가 빠졌을 때도 INVALID_REQUEST(field)로 답하려고 서비스에서 확인한다.
	@PutMapping("/api/recordings/{id}/audio/{seq}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void upload(@PathVariable Long id, @PathVariable Integer seq, @RequestPart("file") MultipartFile file,
			@RequestParam(required = false) Long startMs, @RequestParam(required = false) Long endMs)
			throws IOException {
		audio.upload(currentUser.currentUserId(), id, seq, file.getBytes(), startMs, endMs);
	}

}
