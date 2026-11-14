-- 내 목소리(LOLog-docs characters.md "내 목소리 (FR-CHAR-05)", db.md "내 목소리").
-- 녹음 하나를 참조 음성으로 쓰는 방식이라 학습한 모델은 없고, 원본 녹음과 미리 듣기 파일 키만 둔다.
ALTER TABLE user_profiles ADD COLUMN voice_clone_status VARCHAR(12);
ALTER TABLE user_profiles ADD COLUMN voice_clone_key VARCHAR(255);
ALTER TABLE user_profiles ADD COLUMN voice_clone_preview_key VARCHAR(255);
ALTER TABLE user_profiles ADD COLUMN voice_clone_consented_at TIMESTAMPTZ;
ALTER TABLE user_profiles ADD COLUMN voice_clone_fail_reason VARCHAR(200);
ALTER TABLE user_profiles ADD COLUMN voice_clone_share_friends BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE user_profiles ADD CONSTRAINT ck_user_profiles_voice_clone_status
    CHECK (voice_clone_status IN ('PROCESSING', 'READY', 'FAILED'));

-- 내 목소리 만들기 작업(lolog:video, kind "voice"). AVATAR처럼 회원(user_id)만 가리킨다.
ALTER TABLE jobs DROP CONSTRAINT ck_jobs_type;
ALTER TABLE jobs ADD CONSTRAINT ck_jobs_type
    CHECK (job_type IN ('DETECT', 'COMIC', 'AVATAR', 'VIDEO_VOICE', 'VIDEO_DRAW', 'TEXT', 'VOICE_CLONE'));
ALTER TABLE jobs DROP CONSTRAINT ck_jobs_target;
ALTER TABLE jobs ADD CONSTRAINT ck_jobs_target CHECK (
    (job_type = 'DETECT' AND recording_part_id IS NOT NULL AND comic_id IS NULL AND video_id IS NULL AND text_id IS NULL)
    OR (job_type = 'COMIC' AND comic_id IS NOT NULL AND recording_part_id IS NULL AND video_id IS NULL AND text_id IS NULL)
    OR (job_type IN ('AVATAR', 'VOICE_CLONE') AND comic_id IS NULL AND recording_part_id IS NULL AND video_id IS NULL AND text_id IS NULL)
    OR (job_type IN ('VIDEO_VOICE', 'VIDEO_DRAW') AND video_id IS NOT NULL AND comic_id IS NULL AND recording_part_id IS NULL AND text_id IS NULL)
    OR (job_type = 'TEXT' AND text_id IS NOT NULL AND comic_id IS NULL AND recording_part_id IS NULL AND video_id IS NULL));

COMMENT ON COLUMN user_profiles.voice_clone_status IS '내 목소리 상태-----PROCESSING, READY, FAILED. 만든 적 없거나 지웠으면 NULL';
COMMENT ON COLUMN user_profiles.voice_clone_key IS '내 목소리 원본 녹음 키-----users/{userId}/voice/sample.wav 또는 sample.m4a. 보관(필수 동의)';
COMMENT ON COLUMN user_profiles.voice_clone_preview_key IS '내 목소리 미리 듣기 키-----users/{userId}/voice/preview.wav. 워커가 만듦';
COMMENT ON COLUMN user_profiles.voice_clone_consented_at IS '내 목소리 필수 동의 시각';
COMMENT ON COLUMN user_profiles.voice_clone_fail_reason IS '내 목소리 실패 이유-----실패가 아니면 NULL';
COMMENT ON COLUMN user_profiles.voice_clone_share_friends IS '친구 쇼츠 동의-----false면 친구가 만드는 쇼츠에서는 기본 화자 목소리';
