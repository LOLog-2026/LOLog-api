package com.lolog.api.video;

import com.lolog.api.video.VideoDtos.VideoSource;
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

	public VideoWorkerController(VideoWorkerService service) {
		this.service = service;
	}

	@GetMapping("/source")
	public VideoSource source(@PathVariable Long id) {
		return service.source(id);
	}

	@PostMapping("/storyboard")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void saveStoryboard(@PathVariable Long id, @RequestBody JsonNode body) {
		service.saveStoryboard(id, body);
	}

	@GetMapping("/storyboard")
	public JsonNode storyboard(@PathVariable Long id) {
		return service.storyboard(id);
	}

	@PostMapping(path = "/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void saveAudio(@PathVariable Long id, @RequestPart("file") MultipartFile file) throws IOException {
		service.saveAudio(id, file.getBytes());
	}

	@GetMapping("/audio")
	public ResponseEntity<byte[]> audio(@PathVariable Long id) {
		return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav")).body(service.audio(id));
	}

	@PostMapping(path = "/video", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void saveVideo(@PathVariable Long id, @RequestPart("file") MultipartFile file,
			@RequestPart("thumbnail") MultipartFile thumbnail, @RequestParam(required = false) Long durationMs)
			throws IOException {
		service.saveVideo(id, file.getBytes(), thumbnail.getBytes(), durationMs);
	}

}
