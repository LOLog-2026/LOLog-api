-- 배포 저장소를 교육기관 MinIO로 바꿔(LOLog-docs PR #33, 로컬은 SeaweedFS 그대로) 특정 저장소 이름을 빼고 S3 호환으로 적는다.
-- Crowfoot 문서(컬럼 설명)와 같은 문구다. V3는 이미 적용돼 고칠 수 없다.
COMMENT ON COLUMN comics.image_key IS '이미지 오브젝트 키-----S3 호환 저장소 오브젝트 키(배포는 교육기관 MinIO)';
