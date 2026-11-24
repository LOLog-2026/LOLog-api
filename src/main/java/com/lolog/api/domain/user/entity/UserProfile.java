package com.lolog.api.domain.user.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

// 회원 프로필 한 줄(V5·V7·V13·V18): 닉네임과 친구 코드, 프로필 캐릭터, 내 목소리.
// 줄은 친구 코드가 겹치면 다시 뽑는 insert ... on conflict do nothing으로 만들어서 리포지토리의 native query로 넣는다.
// 닉네임·캐릭터·내 목소리를 서로 다른 요청이 따로 바꾸므로, 바뀐 열만 UPDATE한다(@DynamicUpdate).
@Entity
@Table(name = "user_profiles")
@Getter
@NoArgsConstructor
@DynamicUpdate
public class UserProfile {

	public static final String CHARACTER_PROCESSING = "PROCESSING";
	public static final String CHARACTER_READY = "READY";
	public static final String CHARACTER_FAILED = "FAILED";
	// 내 목소리를 고른 값(정함 11-14)
	public static final String SELF_VOICE = "self";

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Column(length = 10)
	private String nickname;

	@Column(name = "friend_code", nullable = false, length = 8, updatable = false)
	private String friendCode;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private OffsetDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private OffsetDateTime updatedAt;

	// ---- 프로필 캐릭터(V7, V13) ----

	// PROCESSING, READY, FAILED. 만든 적 없거나 지웠으면 null
	@Column(name = "character_status", length = 12)
	private String characterStatus;

	@Column(name = "character_key", length = 255)
	private String characterKey;

	@Column(name = "character_thumb_key", length = 255)
	private String characterThumbKey;

	@Column(name = "character_appearance", length = 200)
	private String characterAppearance;

	@Column(name = "character_updated_at")
	private OffsetDateTime characterUpdatedAt;

	// 고른 목소리. 기본 화자 id 또는 "self", 안 골랐으면 null
	@Column(name = "character_voice", length = 20)
	private String characterVoice;

	// ---- 내 목소리(V18) ----

	@Column(name = "voice_clone_status", length = 12)
	private String voiceCloneStatus;

	@Column(name = "voice_clone_key", length = 255)
	private String voiceCloneKey;

	@Column(name = "voice_clone_preview_key", length = 255)
	private String voiceClonePreviewKey;

	@Column(name = "voice_clone_consented_at")
	private OffsetDateTime voiceCloneConsentedAt;

	@Column(name = "voice_clone_fail_reason", length = 200)
	private String voiceCloneFailReason;

	@Column(name = "voice_clone_share_friends", nullable = false)
	private boolean voiceCloneShareFriends;

	@Column(name = "voice_clone_text", length = 200)
	private String voiceCloneText;

	@Column(name = "voice_clone_updated_at")
	private OffsetDateTime voiceCloneUpdatedAt;

	// 닉네임을 바꾼다.
	public void changeNickname(String nickname) {
		this.nickname = nickname;
	}

	// ---- 프로필 캐릭터 ----

	// 사진을 받아 만드는 중으로 둔다. 예전 그림은 그대로 둔다.
	public void startCharacter() {
		characterStatus = CHARACTER_PROCESSING;
		characterUpdatedAt = OffsetDateTime.now();
	}

	// 워커가 보낸 그림으로 READY가 된다.
	public void characterReady(String key, String thumbKey, String appearance) {
		characterStatus = CHARACTER_READY;
		characterKey = key;
		characterThumbKey = thumbKey;
		characterAppearance = appearance;
		characterUpdatedAt = OffsetDateTime.now();
	}

	// 만들기에 실패했다. 예전 그림은 그대로 둔다.
	public void characterFailed() {
		characterStatus = CHARACTER_FAILED;
		characterUpdatedAt = OffsetDateTime.now();
	}

	// 프로필 캐릭터와 고른 목소리를 지운다.
	public void removeCharacter() {
		characterStatus = null;
		characterKey = null;
		characterThumbKey = null;
		characterAppearance = null;
		characterVoice = null;
		characterUpdatedAt = OffsetDateTime.now();
	}

	// 목소리를 고른다. null이면 지운다.
	public void chooseVoice(String voice) {
		characterVoice = voice;
	}

	// ---- 내 목소리 ----

	// 녹음을 받아 만드는 중으로 둔다. 미리 듣기와 실패 이유는 비운다.
	public void startVoiceClone(String sampleKey, String text) {
		OffsetDateTime now = OffsetDateTime.now();
		voiceCloneStatus = CHARACTER_PROCESSING;
		voiceCloneKey = sampleKey;
		voiceClonePreviewKey = null;
		voiceCloneText = text;
		voiceCloneFailReason = null;
		voiceCloneConsentedAt = now;
		voiceCloneUpdatedAt = now;
	}

	// 워커가 보낸 미리 듣기로 READY가 된다.
	public void voiceCloneReady(String previewKey) {
		voiceCloneStatus = CHARACTER_READY;
		voiceClonePreviewKey = previewKey;
		voiceCloneUpdatedAt = OffsetDateTime.now();
	}

	// 만들기에 실패했다. 쓸 수 있는 목소리가 없으니 녹음·미리 듣기를 비우고, 골랐던 "self"도 지운다.
	public void voiceCloneFailed(String reason) {
		voiceCloneStatus = CHARACTER_FAILED;
		voiceCloneFailReason = reason;
		voiceCloneKey = null;
		voiceClonePreviewKey = null;
		voiceCloneText = null;
		voiceCloneUpdatedAt = OffsetDateTime.now();
		clearSelfVoice();
	}

	// 내 목소리를 모두 지우고, 골랐던 "self"도 지운다.
	public void removeVoiceClone() {
		voiceCloneStatus = null;
		voiceCloneKey = null;
		voiceClonePreviewKey = null;
		voiceCloneConsentedAt = null;
		voiceCloneFailReason = null;
		voiceCloneShareFriends = false;
		voiceCloneText = null;
		voiceCloneUpdatedAt = OffsetDateTime.now();
		clearSelfVoice();
	}

	// 친구 쇼츠 동의를 바꾼다.
	public void shareVoiceClone(boolean share) {
		voiceCloneShareFriends = share;
	}

	// 골랐던 목소리가 "self"면 지운다.
	private void clearSelfVoice() {
		if (SELF_VOICE.equals(characterVoice)) {
			characterVoice = null;
		}
	}

}
