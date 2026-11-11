-- 기본 화자 목소리와 프로필 캐릭터 목소리(LOLog-docs characters.md 기본 화자 목소리·프로필 캐릭터 목소리, db.md V9 초안)
ALTER TABLE default_characters ADD COLUMN voice_key VARCHAR(255);
ALTER TABLE user_profiles ADD COLUMN character_voice VARCHAR(20);

COMMENT ON COLUMN default_characters.voice_key IS '목소리 파일 키-----defaults/characters/{id}_voice.wav. 미리 듣기이자 TTS 참조 음성. 없으면 NULL';
COMMENT ON COLUMN user_profiles.character_voice IS '프로필 캐릭터 목소리-----고른 기본 화자 id. 안 골랐으면 NULL';
