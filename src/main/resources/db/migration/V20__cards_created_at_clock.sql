-- 카드 생성 일시를 DB 시계(문장 실행 시각)로 넣는다.
-- 보관함 새 소식 점은 블로그 글 카드의 생성 일시와 badge_reads.seen_at(clock_timestamp)을 비교하므로 같은 시계를 쓴다.
-- 기본값만 바꾸고 기존 줄은 그대로 둔다.
ALTER TABLE cards ALTER COLUMN created_at SET DEFAULT clock_timestamp();
