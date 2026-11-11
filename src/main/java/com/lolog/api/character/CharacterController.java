package com.lolog.api.character;

import com.lolog.api.auth.CurrentUserProvider;
import com.lolog.api.character.DefaultCharacterService.DefaultCharacterList;
import com.lolog.api.character.ProfileCharacterService.ProfileCharacterResponse;
import java.io.IOException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// 기본 화자 목록과 내 프로필 캐릭터(characters.md 4장 /api). 모두 로그인이 필요하다.
@RestController
public class CharacterController {

	private final DefaultCharacterService defaults;
	private final ProfileCharacterService profileCharacters;
	private final CurrentUserProvider currentUser;

	public CharacterController(DefaultCharacterService defaults, ProfileCharacterService profileCharacters,
			CurrentUserProvider currentUser) {
		this.defaults = defaults;
		this.profileCharacters = profileCharacters;
		this.currentUser = currentUser;
	}

	@GetMapping("/api/characters/defaults")
	public DefaultCharacterList defaults() {
		return defaults.list();
	}

	@PutMapping("/api/me/profile/character")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public ProfileCharacterResponse upload(@RequestPart("photo") MultipartFile photo) throws IOException {
		return profileCharacters.upload(currentUser.currentUserId(), photo.getBytes());
	}

	// 프로필 캐릭터 목소리 고르기(초안). { voice: 기본 화자 id | null }
	@PatchMapping("/api/me/profile/character/voice")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void chooseVoice(@RequestBody VoiceRequest request) {
		profileCharacters.chooseVoice(currentUser.currentUserId(), request.voice());
	}

	public record VoiceRequest(String voice) {
	}

	@DeleteMapping("/api/me/profile/character")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete() {
		profileCharacters.delete(currentUser.currentUserId());
	}

}
