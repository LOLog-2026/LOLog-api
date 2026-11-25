package com.lolog.api.domain.character.controller;

import com.lolog.api.domain.character.service.DefaultCharacterService.DefaultCharacterList;
import com.lolog.api.domain.character.service.DefaultCharacterService;
import com.lolog.api.domain.character.service.ProfileCharacterService.ProfileCharacterResponse;
import com.lolog.api.domain.character.service.ProfileCharacterService;
import com.lolog.api.domain.character.service.VoiceCloneService;
import com.lolog.api.global.auth.CurrentUserProvider;
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

	/** 캐릭터 서비스들과 현재 사용자 정보를 받는다. */
	public CharacterController(DefaultCharacterService defaults, ProfileCharacterService profileCharacters,
			VoiceCloneService voiceClones, CurrentUserProvider currentUser) {
		this.voiceClones = voiceClones;
		this.defaults = defaults;
		this.profileCharacters = profileCharacters;
		this.currentUser = currentUser;
	}

	// 기본 화자 목록을 돌려준다.
	@GetMapping("/api/characters/defaults")
	public DefaultCharacterList defaults() {
		return defaults.list();
	}

	/**
	 * 사진을 올려 프로필 캐릭터를 만들거나 다시 만든다.
	 *
	 * @param photo multipart photo 파트의 사진(JPEG 또는 PNG, 5MB 이하)
	 * @return 만들기를 받은 뒤의 프로필 캐릭터(202)
	 */
	@PutMapping("/api/me/profile/character")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public ProfileCharacterResponse upload(@RequestPart("photo") MultipartFile photo) throws IOException {
		return profileCharacters.upload(currentUser.currentUserId(), photo.getBytes());
	}

	/**
	 * 프로필 캐릭터 목소리 고르기(초안). { voice: 기본 화자 id | "self" | null }
	 *
	 * @param request 고를 목소리를 담은 요청 본문
	 */
	@PatchMapping("/api/me/profile/character/voice")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void chooseVoice(@RequestBody VoiceRequest request) {
		profileCharacters.chooseVoice(currentUser.currentUserId(), request.voice());
	}

	/**
	 * 목소리 고르기 요청.
	 *
	 * @param voice 기본 화자 id, 내 목소리면 "self", 지우려면 null
	 */
	public record VoiceRequest(String voice) {
	}

	/**
	 * 내 목소리 녹음 올리기·다시 올리기(초안). multipart audio(wav·m4a) + consent=true. 응답은 ProfileCharacterResponse
	 * 녹음 문장은 하나로 정해져 있어 앱이 보내지 않는다(서버가 VoiceCloneService.RECORD_SENTENCE를 저장)
	 *
	 * @param audio multipart audio 파트의 녹음(wav 또는 m4a, 없으면 400)
	 * @param consent 쿼리·폼의 필수 동의(true가 아니면 400)
	 * @return 녹음을 받은 뒤의 프로필 캐릭터(202)
	 */
	@PutMapping("/api/me/profile/character/voice-clone")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public ProfileCharacterResponse uploadVoiceClone(@RequestPart(name = "audio", required = false) MultipartFile audio,
			@RequestParam(required = false) Boolean consent) throws IOException {
		return voiceClones.upload(currentUser.currentUserId(), audio == null ? null : audio.getBytes(), consent);
	}

	// 내 목소리 지우기(초안). 없어도 204. 골랐던 목소리가 "self"면 null로
	@DeleteMapping("/api/me/profile/character/voice-clone")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void deleteVoiceClone() {
		voiceClones.delete(currentUser.currentUserId());
	}

	/**
	 * 친구 쇼츠 동의(③)만 바꾸기(정함 11-14). { shareWithFriends }
	 *
	 * @param request 친구 쇼츠 동의를 담은 요청 본문
	 */
	@PatchMapping("/api/me/profile/character/voice-clone")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void shareVoiceClone(@RequestBody ShareRequest request) {
		voiceClones.share(currentUser.currentUserId(), request.shareWithFriends());
	}

	/**
	 * 친구 쇼츠 동의 바꾸기 요청.
	 *
	 * @param shareWithFriends 친구 쇼츠에 내 목소리를 써도 되면 true(null이면 400)
	 */
	public record ShareRequest(Boolean shareWithFriends) {
	}

	// 내 프로필 캐릭터를 지운다.
	@DeleteMapping("/api/me/profile/character")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete() {
		profileCharacters.delete(currentUser.currentUserId());
	}

}
