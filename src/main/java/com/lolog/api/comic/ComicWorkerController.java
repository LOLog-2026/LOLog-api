package com.lolog.api.comic;

import com.lolog.api.comic.ComicWorkerDtos.ComicSource;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;

// Jetson 워커가 만화를 만들 때 부르는 내부 API. X-Internal-Token 검사는 InternalSecurityConfig가 한다.
@RestController
@RequestMapping("/internal/comics/{id}")
public class ComicWorkerController {

	private final ComicWorkerService service;

	public ComicWorkerController(ComicWorkerService service) {
		this.service = service;
	}

	@GetMapping("/source")
	public ComicSource source(@PathVariable Long id) {
		return service.source(id);
	}

	@PostMapping("/storyboard")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void storyboard(@PathVariable Long id, @RequestBody JsonNode body) {
		service.storyboard(id, body);
	}

	// multipart/form-data, 파트 이름 file, image/png, 20MB 이하(spring.servlet.multipart)
	@PostMapping(path = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void image(@PathVariable Long id, @RequestPart("file") MultipartFile file) throws IOException {
		service.image(id, file.getBytes());
	}

}
