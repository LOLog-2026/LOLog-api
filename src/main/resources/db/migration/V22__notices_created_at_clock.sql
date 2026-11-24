-- 공지 생성·수정 일시의 기본값을 DB 시계(문장 실행 시각)로 넣는다.
-- 알림함 목록은 공지(notices.created_at)와 알림(notifications.created_at, clock_timestamp)을 시각 순으로 섞어 보여 주므로 같은 시계를 쓴다.
-- 기본값만 바꾸고 기존 줄은 그대로 둔다.
ALTER TABLE notices ALTER COLUMN created_at SET DEFAULT clock_timestamp();
ALTER TABLE notices ALTER COLUMN updated_at SET DEFAULT clock_timestamp();
