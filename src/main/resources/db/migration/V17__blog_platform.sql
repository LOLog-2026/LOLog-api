-- 블로그 플랫폼(LOLog-docs texts.md 4장 "플랫폼별 구현", 7장 blog_sources). 연결할 때 한 번 골라 저장하고 서버 안에서만 쓴다.
ALTER TABLE blog_sources ADD COLUMN platform VARCHAR(20) NOT NULL DEFAULT 'OTHER';
ALTER TABLE blog_sources ADD CONSTRAINT ck_blog_sources_platform
    CHECK (platform IN ('NAVER', 'TISTORY', 'VELOG', 'MEDIUM', 'WORDPRESS', 'OTHER'));

-- 이 열보다 먼저 연결한 블로그는 주소로 플랫폼을 채운다(자기 도메인 블로그는 OTHER로 남는다).
UPDATE blog_sources SET platform = CASE
    WHEN site_url ~* '^https?://(m\.|rss\.)?blog\.naver\.com(/|$|\?)' THEN 'NAVER'
    WHEN site_url ~* '^https?://[^/?#]+\.tistory\.com(/|$|\?)' THEN 'TISTORY'
    WHEN site_url ~* '^https?://(v2\.)?velog\.io(/|$|\?)' THEN 'VELOG'
    WHEN site_url ~* '^https?://([^/?#]+\.)?medium\.com(/|$|\?)' THEN 'MEDIUM'
    WHEN site_url ~* '^https?://[^/?#]+\.wordpress\.com(/|$|\?)' THEN 'WORDPRESS'
    ELSE 'OTHER' END;

COMMENT ON COLUMN blog_sources.platform IS '플랫폼-----NAVER, TISTORY, VELOG, MEDIUM, WORDPRESS, OTHER. 서버 안에서만 씀';

-- 연결하거나 카테고리를 더할 때는 카테고리마다 최근 글 3개만 카드로 만든다(texts.md 4장 "가져오는 때", 정함 11-14).
-- 그보다 옛 글은 다음에 읽을 때 새 글로 잡히지 않게 guid만 적어 두고(skipped), 가져온 글 목록에는 보이지 않는다.
ALTER TABLE blog_posts ADD COLUMN skipped BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE blog_posts ALTER COLUMN title DROP NOT NULL;
ALTER TABLE blog_posts ALTER COLUMN link DROP NOT NULL;
ALTER TABLE blog_posts ADD CONSTRAINT ck_blog_posts_skipped CHECK (skipped OR (title IS NOT NULL AND link IS NOT NULL));
COMMENT ON COLUMN blog_posts.skipped IS '불러오지 않은 옛 글-----연결·카테고리 추가 때 최근 3개보다 옛 글. guid만 있음';
