package com.lolog.api.domain.audio.controller;

import com.lolog.api.domain.audio.service.AudioUploadService;
import com.lolog.api.global.auth.CurrentUserProvider;
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

	/** 쓰는 서비스와 현재 회원 도구를 받는다. */
	public AudioController(AudioUploadService audio, CurrentUserProvider currentUser) {
		this.audio = audio;
		this.currentUser = currentUser;
	}

	/**
	 * startMs·endMs가 빠졌을 때도 INVALID_REQUEST(field)로 답하려고 서비스에서 확인한다.
	 *
	 * @param id 음성을 올릴 내 녹음 ID
	 * @param seq 음성 파일 번호(1부터, 10분 단위)
	 * @param file 음성 파일(audio/aac, ADTS). 5MB까지
	 * @param startMs 이 파일이 시작하는 녹음 시작부터의 시각(ms, 0 이상). 빠지면 서비스가 INVALID_REQUEST
	 * @param endMs 이 파일이 끝나는 녹음 시작부터의 시각(ms, startMs보다 큼). 빠지면 서비스가 INVALID_REQUEST
	 */
	@PutMapping("/api/recordings/{id}/audio/{seq}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void upload(@PathVariable Long id, @PathVariable Integer seq, @RequestPart("file") MultipartFile file,
			@RequestParam(required = false) Long startMs, @RequestParam(required = false) Long endMs)
			throws IOException {
		audio.upload(currentUser.currentUserId(), id, seq, file.getBytes(), startMs, endMs);
	}

}
