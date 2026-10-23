# QUIZRUN Backend

키워드를 입력하면 **위키 문서를 근거로 HyperCLOVA X(CLOVA Studio)가 즉석에서 퀴즈를 만들고**, 친구들과 같은 문제를 동시에 풀며 달리는 실시간 퀴즈 레이스 서비스의 백엔드입니다. 검증을 통과한 문제는 DB 문제은행에 쌓여 쓸수록 커집니다. Naver Cloud Platform(NCP) 위에서 운영하는 것을 전제로 설계했습니다.

- 프론트엔드: [haramj/quizrun-front](https://github.com/haramj/quizrun-front)
- 기술 스택: Java 21 · Spring Boot 3.3 · Spring Data JPA/Redis · MySQL(H2) · Redis · Gradle (배포는 jar + systemd, Docker는 개발·검증 편의 수단)
- 문서: [아키텍처](docs/architecture.md) · [**VM 배포 가이드**](docs/deploy-vm.md) · [**클라우드 활용 검증**](docs/cloud-alignment.md) · [**DB 설계서**](docs/database-design.md) · [AI 문제 생성](docs/ai-question-generation.md) · [API 명세](docs/api.md) · [발표 자료](docs/presentation.md)

## 핵심 기능

| 기능 | 설명 |
|---|---|
| 근거 기반 AI 출제 | 어떤 키워드든 위키백과(얇으면 나무위키)를 근거로 HCX-007이 출제. 서버가 **근거 문장 실존·정답 일치·숫자 일치**를 기계적으로 검증하고 탈락 문제는 버림 |
| 문제은행 누적 | 검증을 통과한 문제를 DB에 저장. 한 판은 **문제은행 30% + 새로 생성 70%**(설정 가능), 같은 사실을 묻는 중복은 차단 |
| 출처 표시 | 사용한 문서를 `credits`로 내려줌 (예: `위키백과 · 손흥민`) |
| 신고 → 자동 제외 | 플레이어가 문제를 신고하고, 서로 다른 신고자가 3명이 되면 자동으로 출제에서 제외. 관리자는 API로 확인·확정·복구 |
| 대기실에서 비동기 준비 | 방 생성은 **즉시 응답**하고 문제는 백그라운드에서 준비. `state.prepare`(`preparing`/`ready`/`failed`)로 진행을 알림. 준비 중 정원이 차거나 시작을 눌러도 준비가 끝나는 즉시 자동 출발 |
| 폴백 | 키가 없거나 AI 실패 시 내장 문제은행(15개 카테고리 · 180문제)으로 진행 |
| 실시간 방 | 6자리 코드로 입장, 정원 2~100명, 정원이 차면 자동 시작 / 방장 수동 시작 |
| 서버 기준 채점 | 정답 1,000점 + 남은 시간 비율 최대 500점. 마감·중복 제출·정답 사전 노출을 서버가 차단 |
| 채팅 | 방 참가자 전용, 200자 제한, 도배 방지 |
| **서버 여러 대 확장** | 방 상태를 **Cloud DB for Cache(Redis)** 에 두고(방 JSON + 방 단위 분산 락 + 락 없는 폴링 읽기) 어느 서버가 요청을 받아도 같은 결과(수업 Day4 무상태 설계). **서버 2대 + LB 실험·게임 도중 서버 강제 종료 검증**([검증 보고서](docs/cloud-alignment.md#31-서버-2대-실험--무상태-설계-day4)). 켜는 법: `SPRING_PROFILES_ACTIVE=redis` |
| **VM 배포(Docker 없이)** | `deploy/vm/`: 서버 이미지용 `install-app.sh` + systemd 유닛 + 서버 생성 시 설정을 쓰는 **Init Script**(수업 Day2 규칙: 영어만·주석 없음). Rocky Linux 8.9에서 검증 — [배포 가이드](docs/deploy-vm.md) |

## 빠른 시작

### Docker (권장)

```bash
cp .env.example .env        # NCP_CLOVA_API_KEY 입력 (없으면 내장 카테고리/쌓인 문제은행만 사용)
docker compose up --build   # http://localhost:3000  (프론트 포함)
```

저장소 루트의 `public/`(프론트)이 `backend/`의 상위에 있어야 프론트가 함께 서빙됩니다.
문제은행 DB는 도커 볼륨(`quizrun-data`)에 남습니다. 처음부터 다시 시작하려면 `docker compose down -v`.

### 테스트

로컬에 Java가 없어도 컨테이너로 돌릴 수 있습니다. (일반 실행 124개 통과 + 진짜 Redis가 필요한 통합 테스트 11개는 건너뜀, 외부 네트워크·API 호출 없음)

```bash
docker run --rm -v "$PWD":/app -v quizrun-gradle-cache:/home/gradle/.gradle -w /app \
  gradle:8.10-jdk21 gradle test --no-daemon
```

Java 21이 있다면 `gradle test`, 실행은 `gradle bootRun`입니다.

**진짜 Redis로 통합 테스트까지**(락·원자적 저장·서버 2대 시나리오, 총 135개):

```bash
docker network create qr-net && docker run -d --name qr-redis --network qr-net redis:7
docker run --rm --network qr-net -e REDIS_TEST_HOST=qr-redis -v "$PWD":/app \
  -v quizrun-gradle-cache:/home/gradle/.gradle -w /app gradle:8.10-jdk21 gradle cleanTest test --no-daemon
```

### 서버 2대 + 로드밸런서 + Redis + MySQL (수업 Day4 구조를 로컬에서 흉내)

```bash
# .env 에 MYSQL_PASSWORD, ADMIN_TOKEN 이 있어야 한다 (.env.example 참고)
docker compose -f docker-compose.scale.yml up --build     # http://localhost:8080  (nginx가 app1/app2에 Round Robin)
```

한쪽 서버를 멈춰도 한 판이 계속되는지 보려면 `docker kill quizrun-scale-app1-1`을 해 보세요. NCP 리소스가 아니라 **검증용 흉내**입니다. 실제 NCP에 올리는 방법은 [VM 배포 가이드](docs/deploy-vm.md).

## 환경변수

| 이름 | 기본값 | 설명 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | (없음) | **`redis`** 를 켜면 방 상태를 Redis에 두고 서버를 여러 대로 운영할 수 있다(readiness도 Redis 연결을 확인). 없으면 서버 1대용 메모리 모드 |
| `REDIS_HOST` · `REDIS_PORT` | `localhost` · `6379` | Cloud DB for Cache 접속 주소 (`redis` 프로파일에서만 사용) |
| `REDIS_PASSWORD` · `REDIS_SSL` | (빈 값) · `false` | Cache 인증·SSL |
| `NCP_CLOVA_API_KEY` | (없음) | CLOVA Studio API 키. 없으면 AI 생성이 꺼짐 |
| `PORT` | `3000` | 서버 포트 |
| `ADMIN_TOKEN` | (없음) | 관리자 API 토큰. 없으면 관리자 API가 꺼짐(403). 길고 예측 불가능한 값을 쓸 것 |
| `REPORT_THRESHOLD` | `3` | 이 횟수의 서로 다른 신고가 쌓이면 문제를 자동 제외 |
| `DB_URL` | `jdbc:h2:file:./data/quizrun` | NCP Cloud DB for MySQL 사용 시 `jdbc:mysql://<host>:3306/quizrun?characterEncoding=UTF-8` |
| `DB_USER` / `DB_PASSWORD` | `sa` / (빈 값) | DB 계정 |

그 밖의 설정(`application.yml`의 `quizrun.*`): `prepare.threads`(동시 준비 4)·`prepare.queue`(대기 50), `quiz.bank-ratio`(기본 0.3), `clova.model`(HCX-007), `sources.namu-enabled`, `sources.thin-chars`(2500) 등.

> **API 키·관리자 토큰·DB 비밀번호는 코드·설정 파일·Git에 넣지 않습니다.** 로컬은 `.env`(gitignore), 서버에서는 환경변수(또는 권한 600 설정 파일)로 주입합니다.

## 구조

```
src/main/java/com/quizrun/room/
├── RoomController / RoomService     REST API(프론트 계약) · 게임 규칙 · 상태 전이 · 채점
├── RoomStore                        방 저장소 인터페이스 (find는 복사본, saveIfAbsent·save(XX)·touch)
│   ├── RedisRoomStore               운영: Cloud DB for Cache(Redis). 방 JSON + TTL + 접속 신호
│   └── InMemoryRoomStore            로컬·테스트: Redis와 같은 JSON 방식
├── RoomLocks                        방 단위 락 (RedisRoomLocks = 분산 락, InMemoryRoomLocks)
├── RoomCodec                        방 ↔ JSON (모르는 필드는 무시 → 롤링 배포 가능)
├── QuizComposer                     한 판 구성: 문제은행 30% + 신규 70%, 중복 방지, 폴백
├── SourceService                    근거 자료: 위키백과 → 나무위키, 캐시
│   ├── WikipediaSource              위키백과 공식 API
│   └── NamuWikiSource               나무위키 HTML 본문 추출
├── ClovaQuestionGenerator           HCX-007 호출 + 서버 검증 규칙
├── QuestionVault / JpaQuestionVault 검증된 문제 저장소 (stored_question, question_report 테이블)
├── QuestionReportService            신고 접수, 기준 횟수 자동 제외, 관리자 비활성화/복구
├── AdminController                  /api/admin (X-Admin-Token)
├── QuestionSeeder / QuestionBank    내장 문제 180개 (초기 적재 및 폴백)
└── Dtos, Scoring, 예외 처리, 만료 방 정리
src/main/resources/questions.json    15개 카테고리 × 12문제
db/schema.sql                        목표 DB 스키마(MySQL 8, 11개 테이블) — 실행·제약 시험 완료
db/migrate-from-v1.sql               현재(v1) → 목표(v2) 데이터 이행 — 실행 검증 완료
deploy/vm/                           VM 배포: install-app.sh · quizrun.service · init-script.sh · render-init-script.py
deploy/local-lb/nginx.conf           로컬 LB 흉내(Round Robin)
docker-compose.scale.yml             서버 2대 + LB + Redis + MySQL 검증 구성
```

## 현재 상태 · 한계

- **서버 여러 대 확장은 `redis` 프로파일에서만 됩니다.** 프로파일 없이 실행하면 방 상태가 서버 메모리에 있어 서버 1대용입니다. **Redis는 컨테이너로 검증**했고 NCP **Cloud DB for Cache 인스턴스로는 확인하지 못했습니다**(인증·SSL). Redis가 멈추면 방 조회·생성이 500이 되고(서버는 readiness 503으로 LB에서 제외) 복구되면 5~10초 안에 정상화되며, **Redis가 데이터를 잃으면 진행 중인 방이 사라집니다.**
- **LB 헬스체크 경로는 `/actuator/health/readiness`** 로 지정하세요. `/api/health`는 Redis가 죽어도 200이라 장애 서버를 제외하지 못합니다.
- 100명 방을 폴링하면 읽기 비용이 큽니다(간이 측정 p95 약 340ms, 오류 0건). 부하 테스트로 확인이 필요합니다.
- 문제은행 DB는 **MySQL 8.0.46 컨테이너에서 실행 검증**했습니다(`DB_URL`만 변경, 신고·관리자 흐름·한글 포함). 다만 **NCP Cloud DB for MySQL 인스턴스는 만들지 않았습니다.** 현재 코드의 DB 스키마(v1)는 수업의 설계 원칙과 어긋나는 부분이 있어 [DB 설계서](docs/database-design.md)의 목표 스키마(v2)로 이행해야 합니다.
- AI 출제는 근거 검증으로 오류를 크게 줄였지만 **완벽하지 않습니다.** 자료가 틀리면 문제도 틀립니다. 신고 → 자동 제외 → 관리자 복구 루프가 있으나, 로그인이 없어서 **여러 참가자를 만들어 정상 문제를 신고하는 악용은 막지 못합니다.**
- 프론트에는 아직 **신고 버튼이 없습니다** (API는 준비됨, 사용법은 [API 명세](docs/api.md#post-apiroomscodereport--문제-신고)). 자세한 내용은 [AI 문제 생성 문서](docs/ai-question-generation.md#알려진-한계-솔직하게)를 보세요.
- 문제 준비는 AI 키워드에서 **15~30초** 걸립니다. 방 생성은 즉시 응답하고 대기실에서 진행되지만, 프론트가 `state.prepare`를 읽어 **"문제 준비 중"과 실패 안내를 표시해야 합니다**([API 명세](docs/api.md#방-생성과-문제-준비-비동기)). 준비 실패는 HTTP 오류가 아니라 `prepare: "failed"`로 전달됩니다.
- 나무위키는 CC BY-NC-SA(비영리)입니다. 상용으로 가면 `quizrun.sources.namu-enabled=false`로 끄세요.
- 실시간 동기화는 프론트의 700ms 폴링을 지원합니다. SSE(`/events`)는 아직 구현하지 않았습니다.
- 사용자 인증과 IP별 생성 제한은 아직 없습니다. 공개 운영 전에 필요합니다.
- **NCP 리소스(VPC·서버 이미지·LB·Auto Scaling·Cloud DB)는 만들지 않았습니다.** 인프라 담당이 만들 때 백엔드를 올리는 절차는 [VM 배포 가이드](docs/deploy-vm.md)에 있고, Rocky Linux 8.9 + systemd에서 검증했습니다.
- 서버가 **처음에 동시에** 켜지면 `ddl-auto=update`가 같은 테이블을 만들려다 충돌할 수 있습니다(재현하지는 않음). 처음엔 1대로 시작하세요.
