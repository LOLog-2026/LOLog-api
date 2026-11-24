-- 효과음·배경음 벡터 검색(LOLog-docs sounds.md 5장, FR-EXT-03). 콘티의 설명 글과 가까운 소리를 CLAP 소리 벡터로 찾는다.
-- 서버 PostgreSQL에 pgvector가 깔려 있어야 한다.
CREATE EXTENSION IF NOT EXISTS vector;

-- 검색어는 이제 콘티가 고르는 값이 아니라 모을 때 쓴 검색어라 더 길게 받는다.
ALTER TABLE sound_library ALTER COLUMN query TYPE VARCHAR(50);
-- 이미 넣은 소리는 벡터가 없어서 NULL을 허용한다. 수집 스크립트를 다시 돌리면 같은 freesound_id로 채워진다.
ALTER TABLE sound_library ADD COLUMN embedding vector(512);
ALTER TABLE sound_library ADD COLUMN embedding_model VARCHAR(100);
-- 벡터 인덱스(HNSW)는 만들지 않는다. 3,000개쯤은 모두 비교해도 빠르고, 인덱스는 kind로 거르기 전에 후보를 줄여
-- 수가 적은 배경음을 놓칠 수 있다.

COMMENT ON COLUMN sound_library.query IS '모을 때 쓴 검색어-----Freesound에서 찾을 때 쓴 값';
COMMENT ON COLUMN sound_library.embedding IS '소리 벡터-----CLAP 소리 쪽, 512차원, 길이 1';
COMMENT ON COLUMN sound_library.embedding_model IS '벡터 모델-----벡터를 만든 모델 이름. 같은 모델 벡터끼리만 비교';
