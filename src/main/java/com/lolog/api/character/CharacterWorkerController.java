package com.lolog.api.character;

import com.lolog.api.storage.ImageStorage.StoredObject;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// 워커·스크립트용 /internal(characters.md 4장). X-Internal-Token으로 막는다(InternalSecurityConfig).
@RestController
public class CharacterWorkerController {

	private final ProfileCharacterService profileCharacters;
	private final DefaultCharacterService defaults;

	public CharacterWorkerController(ProfileCharacterService profileCharacters, DefaultCharacterService defaults) {
		this.profileCharacters = profileCharacters;
		this.defaults = defaults;
	}

	// 원본 사진. Content-Type은 올린 그대로(JPEG·PNG). 사진은 로그에 남기지 않는다.
	@GetMapping("/internal/users/{userId}/character/photo")
	public ResponseEntity<byte[]> photo(@PathVariable Long userId) {
		StoredObject photo = profileCharacters.photo(userId);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(photo.contentType())).body(photo.bytes());
	}

	@PostMapping("/internal/users/{userId}/character/avatar")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void avatar(@PathVariable Long userId, @RequestPart("file") MultipartFile file,
			@RequestParam(required = false) String appearance) throws IOException {
		profileCharacters.avatar(userId, file.getBytes(), appearance);
	}

	// 기본 화자 넣기·바꾸기. image·thumbnail은 이미 저장소에 있으면 빼도 된다.
	@PutMapping("/internal/characters/defaults/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void upsertDefault(@PathVariable String id,
			@RequestPart(name = "image", required = false) MultipartFile image,
			@RequestPart(name = "thumbnail", required = false) MultipartFile thumbnail,
			@RequestParam(required = false) String ageGroup, @RequestParam(required = false) String gender,
			@RequestParam(required = false) Boolean glasses, @RequestParam(required = false) String label,
			@RequestParam(required = false) String appearance, @RequestParam(required = false) Integer sortOrder)
			throws IOException {
		defaults.upsert(id, image == null ? null : image.getBytes(), thumbnail == null ? null : thumbnail.getBytes(),
				ageGroup, gender, glasses, label, appearance, sortOrder);
	}

}
