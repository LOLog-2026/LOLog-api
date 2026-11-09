-- 카드마다 만든 만화 횟수. 요청 +1, 실패 -1, 만화를 지워도 줄지 않음
ALTER TABLE cards ADD COLUMN comic_count INTEGER NOT NULL DEFAULT 0;
COMMENT ON COLUMN cards.comic_count IS '만든 만화 횟수-----만화 요청 때 +1, 실패하면 −1. 만화를 지워도 줄지 않음. comicRemaining = 3 − comic_count';

-- 만화를 만들 때 카드 요약을 복사해 둠. 카드를 지워도 남음
ALTER TABLE comics ADD COLUMN card_summary TEXT;
COMMENT ON COLUMN comics.card_summary IS '카드 요약-----만화 요청 때 cards.summary를 복사. 카드를 지워도 남음(응답 cardSummary). V2 이전 줄은 NULL';

-- 이미 있는 줄 채우기 (로컬에 시험 데이터가 있을 때만 의미 있음)
-- 지운 만화는 셀 수 없으므로 남은 만화만 센다. 시험 데이터가 3개를 넘어도 CHECK에 걸리지 않게 3에서 자른다.
UPDATE cards c SET comic_count = LEAST(3, (
    SELECT COUNT(*) FROM comics m WHERE m.card_id = c.id AND m.status <> 'FAILED'
));
UPDATE comics m SET card_summary = c.summary FROM cards c WHERE m.card_id = c.id;

ALTER TABLE cards ADD CONSTRAINT ck_cards_comic_count CHECK (comic_count BETWEEN 0 AND 3);
