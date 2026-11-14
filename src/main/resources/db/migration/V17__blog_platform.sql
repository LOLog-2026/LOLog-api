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
