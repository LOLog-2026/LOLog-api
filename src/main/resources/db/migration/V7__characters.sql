-- 캐릭터: 기본 화자 목록, 프로필 캐릭터, 화자에 붙이기
CREATE TABLE default_characters (
    id VARCHAR(20) NOT NULL,
    age_group VARCHAR(10) NOT NULL,
    gender VARCHAR(1) NOT NULL,
    glasses BOOLEAN NOT NULL DEFAULT FALSE,
    label VARCHAR(50) NOT NULL,
    appearance VARCHAR(200) NOT NULL,
    image_key VARCHAR(255) NOT NULL,
    thumb_key VARCHAR(255) NOT NULL,
    sort_order INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT default_characters_pk PRIMARY KEY (id),
    CONSTRAINT ck_default_characters_age_group CHECK (age_group IN ('CHILD', 'YOUNG', 'MIDDLE')),
    CONSTRAINT ck_default_characters_gender CHECK (gender IN ('F', 'M'))
);

ALTER TABLE user_profiles ADD COLUMN character_status VARCHAR(12);
ALTER TABLE user_profiles ADD COLUMN character_key VARCHAR(255);
ALTER TABLE user_profiles ADD COLUMN character_thumb_key VARCHAR(255);
ALTER TABLE user_profiles ADD COLUMN character_appearance VARCHAR(200);
ALTER TABLE user_profiles ADD COLUMN character_updated_at TIMESTAMPTZ;
ALTER TABLE user_profiles ADD CONSTRAINT ck_user_profiles_character_status CHECK (character_status IN ('PROCESSING', 'READY', 'FAILED'));

ALTER TABLE card_speakers ADD COLUMN character_type VARCHAR(10);
ALTER TABLE card_speakers ADD COLUMN character_id VARCHAR(20);
ALTER TABLE card_speakers ADD CONSTRAINT ck_card_speakers_character_type CHECK (character_type IN ('DEFAULT', 'PROFILE'));
ALTER TABLE card_speakers ADD CONSTRAINT ck_card_speakers_character CHECK ((character_type IS NULL) = (character_id IS NULL));
CREATE INDEX idx_card_speakers_profile_character ON card_speakers (character_id ASC) WHERE character_type = 'PROFILE';

ALTER TABLE jobs DROP CONSTRAINT ck_jobs_type;
ALTER TABLE jobs ADD CONSTRAINT ck_jobs_type CHECK (job_type IN ('DETECT', 'COMIC', 'AVATAR'));
ALTER TABLE jobs DROP CONSTRAINT ck_jobs_target;
ALTER TABLE jobs ADD CONSTRAINT ck_jobs_target CHECK (
    (job_type = 'DETECT' AND recording_part_id IS NOT NULL AND comic_id IS NULL)
    OR (job_type = 'COMIC' AND comic_id IS NOT NULL AND recording_part_id IS NULL)
    OR (job_type = 'AVATAR' AND comic_id IS NULL AND recording_part_id IS NULL));

COMMENT ON TABLE default_characters IS '기본 화자-----서비스가 넣어 둔 화자 그림 목록. 그림 파일은 저장소에 있음';
COMMENT ON COLUMN default_characters.id IS '기본 화자 ID-----kid-f-01 같은 바뀌지 않는 키';
COMMENT ON COLUMN default_characters.age_group IS '나이대-----CHILD, YOUNG, MIDDLE';
COMMENT ON COLUMN default_characters.gender IS '성별-----F, M';
COMMENT ON COLUMN default_characters.glasses IS '안경 여부';
COMMENT ON COLUMN default_characters.label IS '설명-----앱에 보일 한글 설명';
COMMENT ON COLUMN default_characters.appearance IS '외모 글-----그림 모델용 영어 글. 앱에는 보내지 않음';
COMMENT ON COLUMN default_characters.image_key IS '그림 오브젝트 키-----768 PNG';
COMMENT ON COLUMN default_characters.thumb_key IS '썸네일 오브젝트 키-----256 WebP';
COMMENT ON COLUMN default_characters.sort_order IS '목록 순서';
COMMENT ON COLUMN default_characters.created_at IS '생성 일시';
COMMENT ON COLUMN default_characters.updated_at IS '수정 일시';
COMMENT ON TABLE user_profiles IS '회원 프로필-----닉네임, 친구 코드, 프로필 캐릭터. 처음 프로필을 보거나 캐릭터를 만들 때 만들어짐';
COMMENT ON COLUMN user_profiles.character_status IS '프로필 캐릭터 상태-----가장 최근에 올린 사진의 상태. PROCESSING, READY, FAILED. 만든 적 없거나 지웠으면 NULL';
COMMENT ON COLUMN user_profiles.character_key IS '프로필 캐릭터 그림 오브젝트 키-----768 PNG. 쓸 수 있는 그림이 없으면 NULL';
COMMENT ON COLUMN user_profiles.character_thumb_key IS '프로필 캐릭터 썸네일 오브젝트 키-----256 그림';
COMMENT ON COLUMN user_profiles.character_appearance IS '프로필 캐릭터 외모 글-----워커가 그림과 함께 보낸 영어 글';
COMMENT ON COLUMN user_profiles.character_updated_at IS '프로필 캐릭터 수정 일시-----사진을 올렸거나 상태가 바뀐 시각';
COMMENT ON TABLE card_speakers IS '카드 화자-----카드에 나오는 화자(화자1, 화자2...)와 사용자가 붙인 이름·그림';
COMMENT ON COLUMN card_speakers.character_type IS '그림 종류-----DEFAULT(기본 화자), PROFILE(프로필 캐릭터). 안 붙였으면 NULL';
COMMENT ON COLUMN card_speakers.character_id IS '그림 ID-----기본 화자 ID 또는 회원 ID(글자로)';
COMMENT ON TABLE jobs IS '작업-----Redis Streams에 넣은 작업의 상태 기준. 대상은 묶음(DETECT), 만화(COMIC), 회원의 프로필 캐릭터(AVATAR) 중 하나';
COMMENT ON COLUMN jobs.user_id IS '회원 ID-----AVATAR 작업은 이 회원의 프로필 캐릭터가 대상';
