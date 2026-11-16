# lolog-api

LOLog의 메인 백엔드예요. 앱이 올린 대화 글을 받아 Jetson AI 워커에 일을 맡기고, 결과(에피소드 카드, 4컷 만화, 쇼츠, 프로필 캐릭터, 내 목소리)를 저장해 앱에 돌려줘요. 친구·방·알림함·공지·관리자 기능도 이 서버가 맡아요.

> LOLog는 일상의 웃긴 대화를 백그라운드 녹음으로 모아, 이야기를 찾아 4컷 만화와 쇼츠로 만들어 주는 Android 서비스예요. 전체 구조는 [LOLog-2026 조직 README](https://github.com/LOLog-2026)에 있어요.

## 기술 스택

| 분류 | 기술 | 쓰는 곳 |
| --- | --- | --- |
| 언어·프레임워크 | Java 21, Spring Boot 4.1 (Web MVC, Validation, Actuator) | REST API 서버 |
| DB | PostgreSQL(배포 17, 로컬·테스트 latest), Spring Data JPA(Hibernate) | 회원 서비스 정보, 카드, 만화·쇼츠 기록, 친구, 방, 알림 |
| DB 변경 관리 | Flyway (V1~V19) | 표 구조를 번호 붙은 SQL로 순서대로 적용 |
| 캐시·큐 | Redis(배포 7.4, 로컬 latest), Redis Streams | Jetson 작업 큐 3개, 컨슈머 그룹 `jetson-workers` |
| 파일 저장 | MinIO(S3 호환), AWS SDK for Java v2 | 만화 PNG, 쇼츠 mp4, 음성, 캐릭터 그림. 앱에는 10분 서명 주소로 줌 |
| 인증 | Spring Security OAuth2 Resource Server, JWT(HS256) | lolog-auth가 발급한 액세스 토큰을 서명만으로 확인 |
| 푸시 | Firebase Admin SDK(FCM) | 작업 완료·친구·방 소식을 data 메시지로 보냄 |
| 블로그 | jsoup, RSS·Atom 파싱 | 블로그 피드를 30분마다 읽어 새 글을 에피소드로 |
| 테스트 | JUnit, Testcontainers(PostgreSQL) | H2 같은 대용품 대신 진짜 PostgreSQL 컨테이너로 테스트 |
| 빌드·배포 | Maven, Docker(멀티 스테이지), GitHub Actions, GHCR | `main` 머지 → 테스트 → 이미지 → 서버 배포 |

## 구조

```
앱 ──HTTPS──▶ 학교 Nginx ──▶ 우리 nginx ─┬─ /auth → lolog-auth
                                      ├─ /api  → lolog-api (이 레포)
                                      └─ /lolog-comics → MinIO (서명 주소)

lolog-api ──XADD──▶ Redis Streams ──XREADGROUP──▶ Jetson 워커 1·2·3호
     ▲                                                  │
     └──────── /internal/... (SSH 터널, X-Internal-Token) ┘
```

- **비동기 처리:** 감지·만화·쇼츠처럼 오래 걸리는 일은 요청에 202로 바로 답하고, 큐에 넣어 Jetson이 처리해요. 끝나면 워커가 `/internal` 주소로 결과를 보내고, api가 DB를 고친 뒤 FCM 푸시를 보내요.
- **작업 상태는 DB가 기준:** 큐에 넣기 전에 `jobs` 표에 먼저 적고, 1분마다 큐에 못 들어간 작업을 다시 넣어요. 같은 결과가 두 번 와도 `jobId`로 걸러요.
- **멱등 업로드:** 앱이 만든 녹음 id와 묶음 번호로 같은 묶음을 두 번 처리하지 않아요. 네트워크가 끊겨 앱이 다시 보내도 카드는 한 번만 생겨요.

### 작업 큐

| 큐 | Jetson | kind |
| --- | --- | --- |
| `lolog:detect` | 1호 (LLM Qwen3.5-4B) | 녹음 묶음 이야기 찾기, `text`(직접 쓴 글·블로그 글) |
| `lolog:comic` | 2호 (FLUX.2 klein 4B) | `comic`(4컷), `avatar`(프로필 캐릭터), `video`(쇼츠 장면·립싱크) |
| `lolog:video` | 3호 (Qwen3-TTS 0.6B) | 쇼츠 대사 음성, `voice_clone`(내 목소리) |

## 패키지

기능(도메인) 단위로 나눴어요. 각 패키지 안에 Controller → Service → Repository가 있어요.

| 패키지 | 맡는 일 |
| --- | --- |
| `recording` | 녹음·1시간 묶음 받기, 감지 큐에 넣기, 감지 결과로 카드 만들기 |
| `audio` | 선택한 음성 저장(10분 단위 파일), 7일 뒤 삭제 |
| `text` | 직접 쓴 글로 에피소드 만들기 |
| `blog` | 블로그 연결(플랫폼별 `BlogPlatform` 구현), 30분마다 새 글 가져오기 |
| `card` | 에피소드 카드 목록·수정·삭제, 화자·대사 고치기 |
| `comic` | 4컷 만화 만들기(카드당 3번), 결과 받기 |
| `video` | 쇼츠 만들기(카드당 2번), 3호 → 2호로 넘기기 |
| `character` | 기본 화자 24명, 프로필 캐릭터, 내 목소리 클론 |
| `sound` | 쇼츠 효과음·배경음 목록(Freesound CC0) |
| `friend`, `room` | 친구 코드·요청·차단, 방·초대·만화 올리기·좋아요·댓글 |
| `inbox`, `notification` | 알림함(30일 보관), FCM 푸시 |
| `admin` | 공지사항, 회원 정지, 의견 보기 |
| `user` | 프로필, 탈퇴 시 데이터 삭제 |
| `storage` | MinIO 업로드·삭제·서명 주소 |
| `job` | 작업 상태, 다시 넣기, 실패 기록 |
| `auth`, `common`, `health` | 토큰 확인, 공통 오류 응답, `GET /api/health` |

## DB

- 표 설명은 마이그레이션 파일의 `COMMENT ON TABLE`에 있어요. `src/main/resources/db/migration/V1__init.sql` ~ `V19__sound_library.sql`.
- 한 번 적용한 마이그레이션은 고치지 않고 다음 번호 파일을 더해요.
- 회원 계정(이메일·비밀번호·소셜 계정)은 lolog-auth의 DB에 있고, 이 서버는 `user_id`만 가져요.
- ERD는 [조직 README](https://github.com/LOLog-2026)의 `lolog-erd.png`를 보세요.

## 보안

- 모든 `/api` 요청은 `Authorization: Bearer <액세스 토큰>`이 필요해요. 만료면 401 `TOKEN_EXPIRED`라 앱이 토큰을 새로 받아요.
- 정지·탈퇴한 회원은 Redis 표시로 바로 막아요(토큰 만료 30분을 기다리지 않음).
- `/internal/...`은 `X-Internal-Token`으로 확인하고, nginx가 밖에서는 막아요. Jetson은 SSH 터널로만 들어와요.
- 파일은 10분 서명 주소로 주고, 사용자 목소리 원본은 서명 주소 없이 api가 워커에게만 직접 줘요.
- 다른 사람의 카드·만화를 요청하면 있다는 것도 알리지 않게 404로 답해요.

## 로컬에서 띄우기

필요한 것: JDK 21, Docker.

```bash
cp .env.example .env          # 값 채우기 (POSTGRES_PASSWORD, JWT_SECRET 32바이트 이상 등)
docker compose up -d          # PostgreSQL, Redis, SeaweedFS(로컬용 S3)
./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # 프로젝트 루트에서 (.env를 여기서 찾음)
```

- 로컬은 S3 호환 저장소로 SeaweedFS를 쓰고, 배포 서버는 학교 MinIO를 써요. 코드는 같아요(S3 API).
- 인증 서버 없이 시험할 때는 `scripts/dev-token.sh [사용자 ID]`로 토큰을 만들어 `Authorization: Bearer <토큰>`으로 보내요.
- 기본 화자 데이터는 `scripts/seed-default-characters.sh`, 효과음 목록은 `scripts/collect-sounds.sh`로 넣어요.

## 테스트

```bash
./mvnw verify
```

Testcontainers가 PostgreSQL 컨테이너를 띄우므로 Docker가 켜져 있어야 해요.

## 배포

`main`에 머지하면 `.github/workflows/deploy.yml`이 돌아요.

1. `./mvnw verify`로 테스트
2. Docker 이미지를 만들어 `ghcr.io/lolog-2026/lolog-api:<커밋 SHA>`로 올림
3. SSH로 서버의 `deploy api <SHA>`를 불러 api 컨테이너만 새로 띄움
4. 헬스체크가 2분 안에 통과하지 못하면 이전 태그로 되돌림

서버 쪽 설정(compose, nginx, 배포 스크립트)은 [LOLog-infra](https://github.com/LOLog-2026/LOLog-infra) 레포에 있어요.

## 관련 문서

- API 명세·DTO·요구사항: [LOLog-docs](https://github.com/LOLog-2026/LOLog-docs)
- 인증 서버: [lolog-auth](https://github.com/LOLog-2026/lolog-auth)
- AI 워커: [lolog-ai](https://github.com/LOLog-2026/lolog-ai)
- 앱: [lolog-app](https://github.com/LOLog-2026/lolog-app)
- 팀 작업 규칙: [CLAUDE.md](CLAUDE.md)
