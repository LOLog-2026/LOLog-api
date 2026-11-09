-- 저장소를 SeaweedFS로 정했으므로 V1의 "MinIO" 표기를 Crowfoot 문서(컬럼 설명)와 같게 고친다. V1은 이미 적용돼 고칠 수 없다.
COMMENT ON COLUMN comics.image_key IS '이미지 오브젝트 키-----SeaweedFS 오브젝트 키';
