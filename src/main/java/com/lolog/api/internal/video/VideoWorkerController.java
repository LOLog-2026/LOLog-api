package com.lolog.api.internal.video;

import com.lolog.api.internal.video.VideoWorkerDtos.VideoSource;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

// 워커(Jetson 3호·2호)가 영상을 만드는 단계를 보낸다(video.md 3장). X-Internal-Token 검사는 InternalSecurityConfig가 한다.
@RestController
@RequestMapping("/internal/videos/{id}")
public class VideoWorkerController {

	private final VideoWorkerService service;

	/** 쓰는 서비스를 받는다. */
	public VideoWorkerController(VideoWorkerService service) {
		this.service = service;
	}

	/**
	 * 영상을 만들 카드 내용을 준다.
	 *
	 * @param id 영상 ID(경로)
	 * @return 카드 내용과 화자마다 그림·목소리
	 */
	@GetMapping("/source")
	public VideoSource source(@PathVariable Long id) {
		return service.source(id);
	}

	/**
	 * 3호가 만든 콘티를 받는다.
	 *
	 * @param id 영상 ID(경로)
	 * @param body 콘티 JSON(version 1, 이 영상의 videoId·cardId)
	 */
	@PostMapping("/storyboard")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void saveStoryboard(@PathVariable Long id, @RequestBody JsonNode body) {
		service.saveStoryboard(id, body);
	}

	/**
	 * 저장한 콘티를 준다.
	 *
	 * @param id 영상 ID(경로)
	 * @return 저장한 콘티(그림이 있는 인물에 imageUrl을 붙임)
	 */
	@GetMapping("/storyboard")
	public JsonNode storyboard(@PathVariable Long id) {
		return service.storyboard(id);
	}

	/**
	 * 3호가 만든 대사 음성을 받는다.
	 *
	 * @param id 영상 ID(경로)
	 * @param file 대사 음성 파트 file(wav)
	 */
	@PostMapping(path = "/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void saveAudio(@PathVariable Long id, @RequestPart("file") MultipartFile file) throws IOException {
		service.saveAudio(id, file.getBytes());
	}

	/**
	 * 저장한 대사 음성을 준다.
	 *
	 * @param id 영상 ID(경로)
	 * @return 대사 음성(audio/wav)
	 */
	@GetMapping("/audio")
	public ResponseEntity<byte[]> audio(@PathVariable Long id) {
		return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(service.audio(id));
	}

	/**
	 * 2호가 만든 완성 영상과 썸네일을 받는다.
	 *
	 * @param id 영상 ID(경로)
	 * @param file 완성 영상 파트 file(mp4)
	 * @param thumbnail 목록용 그림 파트 thumbnail(JPEG)
	 * @param durationMs 영상 길이(ms, 1 이상), 빠지면 400
	 */
	@PostMapping(path = "/video", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void saveVideo(@PathVariable Long id, @RequestPart("file") MultipartFile file,
			@RequestPart("thumbnail") MultipartFile thumbnail, @RequestParam(required = false) Long durationMs)
			throws IOException {
		service.saveVideo(id, file.getBytes(), thumbnail.getBytes(), durationMs);
	}

}
