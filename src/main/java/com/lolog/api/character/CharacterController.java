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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

// 기본 화자 목록과 내 프로필 캐릭터(characters.md 4장 /api). 모두 로그인이 필요하다.
@RestController
public class CharacterController {

	private final DefaultCharacterService defaults;
	private final ProfileCharacterService profileCharacters;
	private final VoiceCloneService voiceClones;
	private final CurrentUserProvider currentUser;

	public CharacterController(DefaultCharacterService defaults, ProfileCharacterService profileCharacters,
			VoiceCloneService voiceClones, CurrentUserProvider currentUser) {
		this.voiceClones = voiceClones;
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

	// 프로필 캐릭터 목소리 고르기(초안). { voice: 기본 화자 id | "self" | null }
	@PatchMapping("/api/me/profile/character/voice")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void chooseVoice(@RequestBody VoiceRequest request) {
		profileCharacters.chooseVoice(currentUser.currentUserId(), request.voice());
	}

	public record VoiceRequest(String voice) {
	}

	// 내 목소리 녹음 올리기·다시 올리기(초안). multipart audio(wav·m4a) + text(읽은 문장) + consent=true. 응답은 ProfileCharacterResponse
	@PutMapping("/api/me/profile/character/voice-clone")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public ProfileCharacterResponse uploadVoiceClone(@RequestPart(name = "audio", required = false) MultipartFile audio,
			@RequestParam(required = false) String text, @RequestParam(required = false) Boolean consent)
			throws IOException {
		return voiceClones.upload(currentUser.currentUserId(), audio == null ? null : audio.getBytes(), text, consent);
	}

	// 내 목소리 지우기(초안). 없어도 204. 골랐던 목소리가 "self"면 null로
	@DeleteMapping("/api/me/profile/character/voice-clone")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteVoiceClone() {
		voiceClones.delete(currentUser.currentUserId());
	}

	// 친구 쇼츠 동의(③)만 바꾸기(정함 11-14). { shareWithFriends }
	@PatchMapping("/api/me/profile/character/voice-clone")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void shareVoiceClone(@RequestBody ShareRequest request) {
		voiceClones.share(currentUser.currentUserId(), request.shareWithFriends());
	}

	public record ShareRequest(Boolean shareWithFriends) {
	}

	@DeleteMapping("/api/me/profile/character")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete() {
		profileCharacters.delete(currentUser.currentUserId());
	}

}
