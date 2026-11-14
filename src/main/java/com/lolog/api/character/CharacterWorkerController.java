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
	private final VoiceCloneService voiceClones;

	public CharacterWorkerController(ProfileCharacterService profileCharacters, DefaultCharacterService defaults,
			VoiceCloneService voiceClones) {
		this.voiceClones = voiceClones;
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

	// 내 목소리 원본 녹음(초안). 내 목소리를 만들 때와 쇼츠에서 "self"를 읽을 때 쓴다. Content-Type은 wav(audio/wav) 또는 m4a(audio/mp4)
	@GetMapping("/internal/users/{userId}/voice-clone/sample")
	public ResponseEntity<byte[]> voiceCloneSample(@PathVariable Long userId) {
		StoredObject sample = voiceClones.sample(userId);
		return ResponseEntity.ok().contentType(MediaType.parseMediaType(sample.contentType())).body(sample.bytes());
	}

	// 워커가 만든 미리 듣기 wav(초안). 받으면 READY, 푸시
	@PutMapping("/internal/users/{userId}/voice-clone/preview")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void voiceClonePreview(@PathVariable Long userId, @RequestPart("file") MultipartFile file) throws IOException {
		voiceClones.preview(userId, file.getBytes());
	}

	// 기본 화자 넣기·바꾸기. image·thumbnail은 이미 저장소에 있으면 빼도 된다. voice(wav)는 선택이다.
	@PutMapping("/internal/characters/defaults/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void upsertDefault(@PathVariable String id,
			@RequestPart(name = "image", required = false) MultipartFile image,
			@RequestPart(name = "thumbnail", required = false) MultipartFile thumbnail,
			@RequestPart(name = "voice", required = false) MultipartFile voice,
			@RequestParam(required = false) String ageGroup, @RequestParam(required = false) String gender,
			@RequestParam(required = false) Boolean glasses, @RequestParam(required = false) String label,
			@RequestParam(required = false) String appearance, @RequestParam(required = false) Integer sortOrder)
			throws IOException {
		defaults.upsert(id, image == null ? null : image.getBytes(), thumbnail == null ? null : thumbnail.getBytes(),
				voice == null ? null : voice.getBytes(), ageGroup, gender, glasses, label, appearance, sortOrder);
	}

}
