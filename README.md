# lolog-api

LOLog의 메인 백엔드예요. 앱이 올린 대화 글을 받아 Jetson AI 워커에 일을 맡기고, 결과(에피소드 카드, 4컷 만화, 쇼츠, 프로필 캐릭터, 내 목소리)를 저장해 앱에 돌려줘요. 친구·방·알림함·공지·관리자 기능도 이 서버가 맡아요.

> LOLog는 일상의 웃긴 대화를 백그라운드 녹음으로 모아, 이야기를 찾아 4컷 만화와 쇼츠로 만들어 주는 Android 서비스예요. 전체 구조는 [LOLog-2026 조직 README](https://github.com/LOLog-2026)에 있어요.

## 기술 스택

| 분류 | 기술 | 쓰는 곳 |
| --- | --- | --- |
| 언어·프레임워크 | Java 21, Spring Boot 4.1 (Web MVC, Validation, Actuator) | REST API 서버 |
| DB | PostgreSQL(배포 17, 로컬·테스트 latest), Spring Data JPA(Hibernate) | 회원 서비스 정보, 카드, 만화·쇼츠 기록, 친구, 방, 알림 |
| DB 변경 관리 | Flyway (V1~V21) | 표 구조를 번호 붙은 SQL로 순서대로 적용 |
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

최상위는 세 갈래예요.

```
com.lolog.api
├── global/     기능과 상관없는 기반 코드
├── domain/     /api(앱이 부름)와 업무 로직, 큐에 작업 넣기(Publisher)
└── internal/   /internal(Jetson 워커, 수집 스크립트, lolog-auth가 부름)
```

- `domain/<기능>/` 안은 `controller`, `service`, `repository`, `entity`, `dto`로 나눠요. 그 층에 파일이 없으면 폴더를 만들지 않아요.
- `entity`에는 DB 표와 표에 저장하는 상태 값을 두고, 응답을 만들 때 계산하는 상태 값(`TextStatus`)은 `dto`에 둬요.
- `internal/<기능>/`은 `domain`과 같은 기능 이름을 써요. `internal`은 `domain`을 부르고, `domain`은 `internal`을 부르지 않아요.

### `global`

| 패키지 | 맡는 일 |
| --- | --- |
| `auth` | JWT 확인, `X-Internal-Token` 확인, 관리자 확인(`AdminGuard`) |
| `error` | 공통 오류 응답 |
| `web` | 업로드 크기 제한, 목록 쪽 나누기 |
| `storage` | MinIO 업로드·삭제·서명 주소, 지울 파일 목록(`StorageDeletions`) |
| `push` | FCM으로 보내기 |
| `transaction` | 커밋 뒤에 할 일(`AfterCommit`) |
| `util` | 로그에 대화 글을 남기지 않기(`SafeLog`) |

### `domain`

| 패키지 | 맡는 일 |
| --- | --- |
| `recording` | 녹음·1시간 묶음 받기, 감지 큐에 넣기 |
| `audio` | 선택한 음성 저장(10분 단위 파일), 7일 뒤 삭제 |
| `text` | 직접 쓴 글로 에피소드 만들기 |
| `blog` | 블로그 연결(`platform`에 플랫폼별 `BlogPlatform` 구현), 30분마다 새 글 가져오기 |
| `card` | 에피소드 카드 목록·수정·삭제, 화자·대사 고치기 |
| `comic` | 4컷 만화 만들기(카드당 3번) |
| `video` | 쇼츠 만들기(카드당 2번) |
| `character` | 기본 화자 24명, 프로필 캐릭터, 내 목소리 클론 |
| `sound` | 쇼츠 효과음·배경음 목록(Freesound CC0) |
| `friend`, `room` | 친구 코드·요청·차단, 방·초대·만화 올리기·좋아요·댓글 |
| `inbox`, `notification` | 알림함(30일 보관), 기기 토큰, 푸시 내용 |
| `admin` | 공지사항, 회원 정지, 의견 보기 |
| `user` | 회원 프로필(`user_profiles`)·정지, 탈퇴 시 데이터 삭제 |
| `job` | 작업 상태 |
| `health` | `GET /api/health` |

### `internal`

| 패키지 | 맡는 일 |
| --- | --- |
| `recording`, `text` | 감지 결과로 카드 만들기 |
| `comic` | 만화 원본 주기, 콘티·그림 받기 |
| `video` | 쇼츠 단계별로 주고받기, 3호 → 2호로 넘기기 |
| `character` | 프로필 사진·목소리 샘플 주기, 프로필 캐릭터·목소리 미리듣기·기본 화자 그림 받기 |
| `sound` | 효과음·배경음 넣기·지우기·목록(수집 스크립트, 3호 워커) |
| `user` | 회원 정지·탈퇴(lolog-auth) |
| `job` | 작업 실패 받기(`POST /internal/jobs/{id}/fail`) |

## DB

- 표 설명은 마이그레이션 파일의 `COMMENT ON TABLE`에 있어요. `src/main/resources/db/migration/V1__init.sql` ~ `V21__storage_deletions.sql`.
- 한 번 적용한 마이그레이션은 고치지 않고 다음 번호 파일을 더해요.
- 회원 계정(이메일·비밀번호·소셜 계정)은 lolog-auth의 DB에 있고, 이 서버는 `user_id`만 가져요.
- ERD는 [조직 README](https://github.com/LOLog-2026)의 `lolog-erd.png`를 보세요.

### DB 접근 규칙

- 표마다 주인 도메인이 있고, 그 도메인의 `entity`에 엔티티를, `repository`에 리포지토리를 둬요.
- 서비스는 `JdbcTemplate`을 쓰지 않아요. DB는 리포지토리로만 읽고 써요.
- 쓰기는 엔티티로 해요. 상태를 바꾸는 일은 엔티티 메서드(`markRead()`, `changeCategories()` 등)로 드러내요.
- 잠금 조회는 리포지토리 메서드(`@Lock(PESSIMISTIC_WRITE)`)로 두고, 그 행을 처음 읽을 때 잠가요. 여러 행을 잠그면 순서(`order by`)를 정해 교착을 막아요.
- 여러 줄을 한 번에 지우거나 고치면 리포지토리의 JPQL(`@Modifying`)로 해요.
- JPA로 쓰기 어려운 SQL은 리포지토리 안의 native query로만 둬요. `on conflict` upsert, 조건부 증감(`set x = x + 1 where x < 3`), `returning`, advisory lock, 실행 시각(`clock_timestamp()`)이 필요한 넣기가 여기에 들어가요.
  - native query로만 바꾸는 열은 엔티티에서 `insertable = false, updatable = false`로 둬요. 엔티티를 저장할 때 옛 값으로 덮어쓰지 않게 하려는 거예요.
- 리포지토리의 고치기·지우기 쿼리에는 `@Modifying(flushAutomatically = true, clearAutomatically = true)`를 붙여요. 쿼리 전에 바꾼 엔티티를 먼저 저장하고, 쿼리 뒤에 낡은 엔티티를 쓰지 않게 해요.
  - 영속성 컨텍스트를 비우면 그 전에 읽은 엔티티는 떨어져 나가서, 그 뒤에 바꾼 값은 저장되지 않아요. 그래서 엔티티를 바꾸는 일은 이 쿼리보다 먼저 하거나 다시 읽어요.
  - 새 줄만 넣는 쿼리(알림 넣기 등)는 이미 읽은 엔티티를 낡게 하지 않으니 `flushAutomatically`만 붙여요.
- 다른 도메인의 표는 그 도메인의 리포지토리나 서비스를 거쳐요.
  - 예외: 여러 도메인의 표를 한 문장으로 봐야 하는 조회는 그 조회를 쓰는 도메인의 리포지토리에 native query로 둬요(예: `RecordingAudioFileRepository.findCardIdsToClip`).
- 서로 비교해서 순서를 정하는 시각(알림·새 소식 본 시각·방 대화·읽은 시각·카드 생성 일시)은 DB 시계(`clock_timestamp()`)로 정해요. Java 시계와 DB 시계는 조금씩 어긋날 수 있어서, 섞어 비교하면 순서가 뒤집혀요.
- 여러 표를 합쳐 보여 주는 화면용 목록 조회는 그 조회를 쓰는 도메인의 리포지토리에 JPQL 한 문장으로 두고, 결과는 화면에 필요한 칸만 담은 record로 받아요(`select new ...`). 표마다 따로 읽어 Java에서 합치면 DB 왕복이 늘어서예요. 쓰기는 지금처럼 엔티티로 해요.
- SQL 문장 자체가 실행 중에 바뀌는 조회는 리포지토리 패키지의 `@Repository` 클래스에서만 `JdbcTemplate`을 써요(`InboxQueries`).
- 서버 상태 확인(`HealthController`의 `select 1`)은 데이터를 다루지 않아 이 규칙에서 빼요.

### 커밋 뒤에 할 일

- 큐에 넣기, 푸시, 구간 음성 자르기처럼 커밋된 뒤에 할 일은 `AfterCommit.run`으로 맡겨요. 트랜잭션 동기화를 직접 쓰는 곳은 `AfterCommit` 하나예요.
- 커밋 뒤 할 일이 실패해도 요청은 실패로 돌리지 않고 로그만 남겨요. 데이터는 이미 저장됐고, 500을 주면 앱이 다시 보내 같은 일이 두 번 생겨서예요.
- 그래서 꼭 해야 하는 일은 다시 하는 길을 둬요. 큐에 넣기는 1분마다 못 넣은 작업을 다시 넣고(`republishPending`), 구간 음성은 한 시간마다 다시 잘라요(`AudioRetentionJob`). 푸시는 다시 보내지 않아요(앱이 조회로 상태를 알아요).
- 저장소 파일 지우기는 `StorageDeletions.schedule`(키 하나)·`schedulePrefix`(그 아래 전체)로 해요. 데이터를 바꾸는 트랜잭션 안에서 `storage_deletions`에 줄을 넣어 그 변경과 함께 커밋·되돌림되고, 커밋 뒤에 바로 지운 다음 줄을 빼요. 못 지운 줄은 10분마다 다시 지워요(`lolog.storage.deletion-retry-delay-ms`).
- 정해진 키(프로필 사진, 내 목소리 녹음 등)에 파일을 다시 올릴 때는 올리기 전에 `StorageDeletions.cancel`로 그 키의 남은 지우기를 빼요. 예전 지우기가 새 파일을 지우지 않게 하려는 거예요.
- 탈퇴(`users/{userId}/` 지우기)와 7일 지난 녹음 지우기는 이 목록을 쓰지 않고 바로 지워요. 탈퇴는 실패하면 오류를 돌려 lolog-auth가 다시 부르고, 녹음은 줄이 남아 다음 시간에 다시 지워요.

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
