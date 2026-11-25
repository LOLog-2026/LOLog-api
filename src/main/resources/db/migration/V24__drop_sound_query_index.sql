-- 워커가 검색어 목록(GET /internal/sounds) 대신 벡터 검색(POST /internal/sounds/search)으로 소리를 고르게 되어(ai #77)
-- 목록 조회용 인덱스를 지운다. query 열은 모을 때 쓴 검색어 기록으로 남긴다(sounds.md 5장).
DROP INDEX IF EXISTS idx_sound_library_kind_query;
