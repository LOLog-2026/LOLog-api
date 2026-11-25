package com.lolog.api.internal.comic;

import com.lolog.api.internal.comic.ComicWorkerDtos.ComicSource;
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

	/** 쓰는 서비스를 받는다. */
	public ComicWorkerController(ComicWorkerService service) {
		this.service = service;
	}

	/**
	 * 만화를 만들 카드 내용을 준다.
	 *
	 * @param id 만화 ID(경로)
	 * @return 카드 내용과 화자마다 그림
	 */
	@GetMapping("/source")
	public ComicSource source(@PathVariable Long id) {
		return service.source(id);
	}

	/**
	 * 워커가 만든 콘티를 받는다.
	 *
	 * @param id 만화 ID(경로)
	 * @param body 콘티 JSON(version 1, 이 만화의 comicId·cardId, panels 4개)
	 */
	@PostMapping("/storyboard")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void storyboard(@PathVariable Long id, @RequestBody JsonNode body) {
		service.storyboard(id, body);
	}

	/**
	 * multipart/form-data, 파트 이름 file, image/png, 20MB 이하(spring.servlet.multipart)
	 *
	 * @param id 만화 ID(경로)
	 * @param file 완성 이미지 파트 file(800×3152 PNG)
	 */
	@PostMapping(path = "/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void image(@PathVariable Long id, @RequestPart("file") MultipartFile file) throws IOException {
		service.image(id, file.getBytes());
	}

}
