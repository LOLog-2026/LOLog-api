-- 차단한 사람의 방 글 숨기기(LOLog-docs friends.md 차단, db.md V9 초안). 차단을 풀면 줄이 지워지니 설정도 없어진다.
ALTER TABLE user_blocks ADD COLUMN hide_in_rooms BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN user_blocks.hide_in_rooms IS '방에서 숨기기-----true면 같은 방에서 그 사람 글을 "숨긴 글"로 보여 줌';
