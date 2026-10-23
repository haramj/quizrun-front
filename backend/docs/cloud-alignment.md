# 클라우드 활용 검증 보고서

**질문**: 지금 구현해 놓은 것이 **수업에서 배운 클라우드 기술을 적극 활용**했는가?

**결론부터**: 처음 점검(이 보고서의 이전 판)에서는 **아니었습니다.** 그 뒤 가장 큰 격차였던 **무상태 설계(Day4)** 를 해결하고 **서버 이미지 + Init Script 배포 경로(Day2·Day4)** 를 만들어 다시 검증했습니다. 지금은 아래와 같습니다.

1. **백엔드 쪽 격차는 대부분 메웠습니다.** 서버를 2대로 늘렸을 때 방이 깨지던 문제(404)가 사라졌고, 게임 도중 서버 1대를 강제 종료해도 한 판이 끝까지 진행됩니다. Docker 없이 Rocky Linux 8 + systemd로 앱을 올려서 같은 상태를 공유하는 것도 확인했습니다.
2. **그러나 NCP 리소스는 아직 하나도 만들지 않았습니다.** VPC·서버 이미지·Application LB·Auto Scaling·Cloud DB for MySQL/Cache·Cloud Insight는 인프라 담당(정하람)의 작업이고, 지금은 **그 위에 백엔드를 올리는 방법([VM 배포 가이드](deploy-vm.md))** 까지가 준비된 상태입니다. 모든 검증은 **로컬 컨테이너**(MySQL 8, Redis 7, nginx, Rocky Linux 8.9)에서 했습니다.
3. 수업에 한 번도 나오지 않은 서비스(Container Registry, NKS, Secret Manager, Sub Account, CDN+)를 이전 문서에 넣었던 것은 정정했습니다(5장).

> 이 보고서는 "잘 했다"가 아니라 **무엇이 되어 있고 무엇이 안 되어 있는지**를 가리기 위한 것입니다. 발표에서 "NCP에 LB와 Auto Scaling을 적용했다"고는 말할 수 없고, **"그 구조가 동작함을 로컬에서 검증했고, 올릴 절차를 준비했다"** 가 사실입니다(8장).

---

## 1. 검증 기준과 방법

| 구분 | 내용 |
|---|---|
| **수업 기준** | `네이버클라우드_클라우드_Day1~4.pdf`(슬라이드 155쪽), `labguide_1-5일.pdf`(실습 80쪽: VPC, 서버, 이미지, Init Script, 스토리지, LB, Auto Scaling, **Cloud DB for MySQL**). 5일차(DB)는 슬라이드 없이 실습 가이드로 확인 |
| **팀 기준** | `프로젝트_기획 초안_5팀.pdf`의 "보충: 기술 구성(NCP 활용)"과 기능 요구 |
| **역할 구분** | 기획서: 정하람 = 서버·클라우드 인프라(NCP 서버·LB·Auto Scaling·배포·모니터링), 이해규 = 프론트, **이상민 = DB 설계·백엔드**. 이 보고서는 **백엔드가 수업 기술과 맞물리는 지점**을 검증한다 |
| **방법** | ① 코드·문서 확인 ② **실제 실행**: MySQL 8.0.46·Redis 7 컨테이너, 서버 2대 + nginx LB, 게임 도중 서버 강제 종료, Redis 정지·복구, Rocky Linux 8.9(systemd) VM에서 Init Script 실행, LB 헬스체크 방식(HEAD/GET), CORS, 자원 사용량 |
| **상태 표기** | ✅ 구현하고 실행으로 검증 · 🟡 일부만 · 📐 설계 문서만(리소스·코드 없음) · ❌ 되어 있지 않음 · ➖ 해당 없음 |

**수업에서 다루지 않은 것**(슬라이드·실습 전체에서 등장 횟수 0): Docker/컨테이너, Container Registry, NKS(Kubernetes), Secret Manager, Sub Account, WebSocket, Cloud Functions. `CDN`과 `Cloud Log Analytics`는 각각 1회(실습 중 팝업)만 나옵니다.

---

## 2. 수업 기술별 검증 (Day1~5)

| # | 수업 내용 (출처) | 이 프로젝트에서의 의미 | 상태 | 근거·비고 |
|---|---|---|---|---|
| 1 | **VPC·Subnet·CIDR, Public/Private** (Day1) | 앱 / LB / DB를 서브넷으로 분리 | 📐 | [아키텍처](architecture.md), 값은 실습과 같게(`web-sub 10.0.1.0/24`, `web-lb-sub 10.0.2.0/24`, `db-sub 10.0.3.0/24`). 리소스 없음. 인프라 담당 |
| 2 | **NAT Gateway** — Private 서버의 외부 호출 (Day1 *"Private 서버가 yum install을 못 하는 이유"*) | 앱이 CLOVA·위키백과·나무위키를 호출 → Private이면 **NAT 필수**. `dnf`로 Java를 설치할 때도 필요 | 📐 | [배포 가이드 1장](deploy-vm.md#1-먼저-필요한-것-서버를-만들기-전에). 외부 호출이 실패해도 서버는 죽지 않고 폴백(✅ 코드) |
| 3 | **ACG(Stateful)·NACL(Stateless), 3계층 최소 권한** (Day1) | LB → 앱(3000) → DB(3306)·Cache만 허용 | 📐 | **규칙표 작성**([배포 가이드 1.1](deploy-vm.md#11-acg-day1-최소-권한-실습-11)), DB·Cache는 소스를 앱 ACG로 |
| 4 | **서버 이미지·Init Script·인증키** (Day2) | 앱 서버를 사람 손 없이 똑같이 복제 → Launch Configuration의 재료 | 🟡 | **`install-app.sh`·`quizrun.service`·`init-script.sh` 작성, Rocky Linux 8.9 + systemd에서 실행 검증**(Java 21 설치, 이미지 시점 설정 파일 0바이트, Init Script로 약 16초 만에 준비 상태). Init Script 규칙(영어만·주석 없음·shebang)은 자동 테스트로 고정. **NCP에서 서버 이미지를 만들고 실행해 본 것은 아님** |
| 5 | **서버 타입·스펙 선택** (Day2) | 앱 서버 사이징 | 🟡 | 측정값만 확보(2.1). 스펙 결정은 인프라와 |
| 6 | **Object Storage** — 프로필 이미지 (Day3, 기획서) | 이미지는 서버 로컬 디스크가 아니라 Object Storage | ❌ | 로그인·프로필이 없어 이미지 기능 자체가 없음 |
| 7 | **Object Storage 정적 웹호스팅** (Day3) | 프론트를 서버 없이 호스팅 | ❌ | **지금 코드로는 불가**: 프론트는 `/api/...` 상대 경로만 쓰고 API 주소 설정이 없으며, 백엔드는 다른 출처 요청을 **403으로 거절**(CORS 미설정). 3.3절 |
| 8 | Block·NAS (Day3) | 파일 저장이 없음 | ➖ | 문제·기록은 DB, 이미지는 Object Storage |
| 9 | **LB Health Check** — HEAD/GET + URL Path (Day4, 실습은 HEAD) | Target Group이 서버 상태를 확인 | ✅ | **검증**: `HEAD`·`GET /api/health`, `/actuator/health/liveness·readiness` 모두 동작. **Redis를 멈추면 readiness만 503**이고 `/api/health`는 200 → **Target Group 경로는 `/actuator/health/readiness`**. Redis 복구 후 5~10초 안에 스스로 200으로 복귀 |
| 10 | **LB 분산 알고리즘·Target Group** (Day4) | Round Robin(기본) / Least Connection / Source IP Hash | 🟡 | **로컬 nginx(Round Robin)로 검증**: 서버 2대에 요청이 66:60으로 번갈아 가며 한 판이 오류 없이 끝남. **Source IP Hash 불필요**(상태가 Redis에 있음). NCP Application LB·Target Group은 만들지 않음 |
| 11 | **무상태 설계** — *"서버가 사라져도 잃을 것이 없어야 한다. 서버는 계산만 한다"* (Day4) | 방 상태를 외부 저장소로 | ✅ | **서버 2대 실험을 재실행해 통과**(3.1절). 게임 중 서버 강제 종료 후에도 진행. **단 Redis 컨테이너 기준이며 실제 Cloud DB for Cache는 미검증** |
| 12 | **Auto Scaling** — Launch Configuration, ASG(최소·최대·기대), 정책, Cloud Insight Event Rule (Day4) | 접속 폭주(퀴즈 시작 시각) 대응 | 📐 | **전제는 충족**(무상태, 서버 이미지 + Init Script, 서버 새로 켜졌을 때 약 16초에 준비, 헬스체크 경로). Launch Configuration·ASG·정책은 인프라 담당([배포 가이드 6장](deploy-vm.md#6-launch-configuration--auto-scaling-group)) |
| 13 | **SPOF 제거·Multi-Zone** (Day4) | 서버 2대 이상을 다른 Zone에, DB는 HA | 📐 | **앱 서버는 더 이상 SPOF가 아니다**: 서버 1대가 죽거나 재시작해도 방이 유지된다(검증). **대신 Redis 한 대가 새 SPOF**(정지 시 방 조회·생성 500) — Cache 가용성 옵션 확인 필요 |
| 14 | **2-Tier**: Application LB → Web/App ASG → Cloud DB for MySQL (Day4 *"가장 흔한 최소 구성"*) | Spring Boot가 정적 파일과 API를 함께 서빙 | 📐 | 로컬 `docker-compose.scale.yml`로 같은 구조를 흉내 내 검증. 정적 파일은 서버에 배치하는 절차가 아직 없음 |
| 15 | **Cloud Insight** — 지표·Event Rule (Day4) | CPU 기준 서버 증감 | 📐 | 별도 코드 불필요. 임계값을 정하려면 부하 테스트가 필요(미실시) |
| 16 | **Cloud DB for MySQL** (실습 11) | 문제은행·신고·(예정) 기록 | ✅ | **검증**: `DB_URL`만 바꿔 MySQL 8.0.46에 연결 → 테이블 생성, 문제 180개 적재, 신고 → 자동 제외 → 관리자 복구, 한글 정상, **서버 2대가 같은 DB 공유**. 목표 스키마 DDL·제약 시험 25/25 ([DB 설계서](database-design.md)). **실제 Cloud DB 인스턴스는 만들지 않았다** |
| 17 | **DB 보안**: Private Subnet(`db-sub`), DB ACG 3306 ← 앱 ACG, 운영은 HA (실습 11) | DB는 인터넷에서 접근 불가 | 📐 | [DB 설계서 8장](database-design.md#8-보안-설계), [배포 가이드](deploy-vm.md) |
| 18 | **Cloud DB for Cache(Redis)** — *"세션→Cloud DB for Cache"* (Day4) | 방 상태·접속 신호·분산 락 | ✅ | **구현·검증**: 방 JSON + TTL, 방 단위 분산 락(Lua 해제), 락 없는 폴링 읽기, 방 코드 `SET NX`, 서버 장애 시 준비 타임아웃. 자동 테스트 135개(진짜 Redis 통합 11개 포함). **실제 Cloud DB for Cache(인증·SSL)는 미검증** |

**집계** (총 18개 항목): ✅ **4** · 🟡 **3** · 📐 **8** · ❌ **2** · ➖ **1**
(이전 판: ✅ 2 · 🟡 1 · 📐 8 · ❌ 6 · ➖ 1 → **무상태·Cache·배포 수단·분산이 해결되어 ❌ 6개가 4개 줄었다.** 남은 ❌는 Object Storage 두 항목뿐)

### 2.1 서버 사이징 참고 측정 (Day2 "서버 스펙 선택")

한 대의 노트북 Docker에서 측정했고 실제 NCP 서버와 다릅니다. 참고용입니다.

| 항목 | 값 |
|---|---|
| 유휴 메모리 | 앱 1대 약 433~500 MiB (JVM 힙 상한 = 메모리의 75%) |
| 기동 시간 | 약 11~14초(Docker), Rocky Linux VM에서 Init Script 실행 후 **약 16초에 readiness 200** — Auto Scaling에서 새 서버가 LB에 들어오기까지의 시간에 포함 |
| **100명 방 폴링**(700ms 간격, 약 120 req/s, 10초) | **LB → 서버 2대 → Redis**: 오류 0건, p50 32ms / **p95 339ms** / p99 505ms. **메모리 모드(같은 PC)의 이전 측정은 p95 90ms**였다. 차이는 방(약 25KB JSON)을 매번 읽고 파싱하는 비용 + LB 한 단계 + 같은 PC의 클라이언트 영향이 섞인 값. 700ms 폴링에는 충분하지만 **방이 클수록 읽기 비용이 늘어난다**. 클라이언트도 같은 PC·10초짜리 간이 측정이라 **용량 근거로는 쓰지 않는다** |
| Docker 이미지 | 557 MB (수업에는 없는 배포 방식이므로 참고만). **배포되는 실체는 jar 62MB** |

---

## 3. 핵심 격차의 증거

### 3.1 서버 2대 실험 — 무상태 설계 (Day4)

수업 슬라이드(Day4 38쪽)는 *"특정 서버에만 상태를 저장하면 서버 프로세스 종료로 메모리 세션이 소실… 근본 해결은 서버를 무상태로 설계하고 상태는 외부 저장소로 분리하는 것"* 이라고 합니다.

**실험**: 같은 DB를 보는 백엔드 2대(서버 A, B)에서 A가 만든 방을 B가 아는지 확인.

| 요청 | **해결 전** (방 상태를 서버 메모리에 둠) | | **해결 후** (방 상태를 Redis에 둠) | |
|---|---|---|---|---|
| | 서버 A | 서버 B | 서버 A | 서버 B |
| 방 생성 `POST /api/rooms` | 201 | — | 201 | — |
| 상태 조회 `GET .../state` | 200 | **404** | 200 | **200** |
| 입장 `POST .../join` | — | **404** | — | **200** |
| 입장 후 A가 보는 참가자 수 | — | — | **2명** (B의 입장 반영) | |
| 방 목록 | 방 3개 | **빈 목록** | (정원이 차서 시작한 방은 대기 목록에서 빠짐 — 두 서버 모두 같은 결과) | |

- **해결 전**에는 LB가 Round Robin으로 나누면 요청마다 200과 404가 번갈아 나옵니다. Source IP Hash로 한 사용자를 고정해도, 같은 방의 다른 참가자가 다른 서버로 가므로 해결되지 않습니다.
- **해결 후** 같은 방의 요청이 두 서버에 번갈아 가도 됩니다:

| 추가 검증 (해결 후) | 결과 |
|---|---|
| LB(Round Robin)를 거쳐 한 판(4문제) 끝까지 | 요청이 서버 2대에 **66 : 60**으로 분산, 단계 `countdown → playing → reveal → … → finished`, **200이 아닌 응답 0건**, 점수는 한 번씩만 계산 |
| **게임 도중 서버 1대를 `docker kill`**(6문제 판, 2번째 문제 진행 중) | 6문제 모두 순서대로 진행(`playing0 → … → reveal5 → finished`), **오류 0건**, 이후 응답은 남은 서버가 처리 |
| 죽은 서버가 있는 동안 LB로 30번 요청 | **전부 정상 응답**(27건은 바로, 3건은 죽은 서버를 먼저 시도했다가 2초 안에 다른 서버로 넘어감) |
| 서버를 다시 켜면 | 바로 분산에 복귀(30번 중 19:11), 다른 서버가 만든 방을 조회 가능 |
| **Redis를 정지** | 해당 서버 `readiness` **503**(LB가 제외할 수 있음), `/api/health`는 200(**감지 못 함**), 방 조회·생성은 500 |
| Redis를 다시 켜면 | 재시작 없이 **5~10초 안에 readiness 200**, 방 생성 정상 |
| 마감 직후 두 서버가 동시에 폴링(자동 테스트) | 채점은 **한 번만** 반영 |
| 정원 5명 방에 24명이 두 서버로 동시 입장(자동 테스트) | **정확히 4명만** 추가 입장(정원 초과 없음) |
| 8명이 두 서버로 동시에 답 제출(자동 테스트) | 전원 기록, 정답 4명만 1,500점, 이중 채점 없음 |
| 방 코드 60개를 서버 2대에서 동시에 생성(자동 테스트) | **중복 없음** |
| 준비를 맡은 서버가 죽었을 때(자동 테스트) | 180초 뒤 다른 서버가 실패 처리 — 방이 영원히 `preparing`에 남지 않음 |

> **이 결과가 보장하지 않는 것**: Redis는 **컨테이너**였습니다. NCP **Cloud DB for Cache**의 접속 방식(인증·SSL)과 가용성은 확인하지 못했습니다. 코드는 `REDIS_PASSWORD`·`REDIS_SSL`을 지원합니다.

### 3.2 배포 수단 — 서버 이미지 + Init Script (Day2·Day4)

수업의 자동화 흐름은 **서버 이미지 + Init Script → Launch Configuration → Auto Scaling Group** 입니다. 이전에는 Docker 이미지뿐이어서 이 흐름에 쓸 수 없었습니다. 지금은 `deploy/vm/`에 있습니다.

| 파일 | 역할 |
|---|---|
| `install-app.sh` | 골든 서버에서 한 번: Java 21 설치(`dnf`), 서비스 계정, jar, systemd 등록. **설정 파일은 비움** |
| `quizrun.service` | 설정이 비어 있으면 시작하지 않음, 크래시 시 자동 재시작, 정상 종료(143)는 성공 처리 |
| `init-script.sh` | 서버가 만들어질 때 설정 파일을 쓰고 서비스 시작. **영어만·주석 없음·shebang** |
| `render-init-script.py` | `__자리표시자__`를 값으로 채움(누락·한글 거부) |

**Rocky Linux 8.9 + systemd에서 실제로 실행한 결과** ([배포 가이드 9장](deploy-vm.md#9-검증-기록)):

- Java 21이 **기본 저장소에서 설치**됨. 설치 직후(= 서버 이미지 시점) **설정 파일 0바이트** → 이미지에 비밀값이 없고, 설정 없이는 서비스가 **시작되지 않음**
- Init Script 실행 후 **약 16초 만에 readiness 200**, 프로세스는 root가 아닌 `quizrun` 계정
- 이 VM이 **Docker로 띄운 서버들과 같은 방을 공유**
- `kill -9` → systemd가 **약 19초 만에 되살림**, 그 사이 다른 서버가 서비스, 되살아난 뒤에도 방 유지
- `systemctl stop` → 실패가 아닌 정상 종료

NCP에서의 서버 이미지 생성·Launch Configuration 실행은 하지 않았습니다.

### 3.3 프론트 호스팅 — Object Storage 정적 호스팅은 지금 불가 (Day3)

수업 Day3는 *"서버 한 대 없이 웹사이트를 띄운다"* 를 가르칩니다. 프론트를 거기에 올리려면:

| 확인 | 결과 |
|---|---|
| 프론트의 API 호출 방식 | `fetch('/api/rooms/...')` **상대 경로**만 사용. API 주소 설정 없음. 절대 URL 0개 |
| 다른 출처에서 백엔드 호출 | 프리플라이트 `OPTIONS /api/rooms` → **403**. 응답에 `Access-Control-Allow-Origin` 헤더 없음 → **브라우저가 응답을 차단** |

선택지:
1. **(현재 구조 유지, 권장)** 앱 서버가 프론트도 서빙한다. 수업의 2-Tier 구성과 같다. 정적 서빙은 로컬(docker compose)에서 검증했고, **서버에 프론트 파일을 배치하는 절차는 아직 없다.** Object Storage는 프로필 이미지에만 쓴다.
2. Object Storage 정적 호스팅 → 프론트에 API 주소 설정 추가 + 백엔드 CORS 허용 목록 추가 + 공개 권한 위험(Day3 *"공개 권한과 그 위험"*)을 관리.

---

## 4. 기획서 요구 대비 백엔드 구현

기획서가 요구한 기능·기술 중 **백엔드가 담당하는 부분**의 상태입니다.

| # | 기획서 요구 | 상태 | 비고 |
|---|---|---|---|
| P1 | 키워드 기반 방 생성·검색 | ✅ | 검색 필터는 프론트 |
| P2 | 정원 도달 시 자동 시작 | ✅ | |
| P3 | **최소 인원**이 모여야 시작 | ❌ | 현재는 정원 충족 또는 방장 수동 시작만. "최소 인원" 설정이 없다 |
| P4 | 실시간 퀴즈 진행·즉시 판정 | 🟡 | **700ms 폴링**. 기획서의 WebSocket이 아니다. 동작은 하지만 요청량이 많다 |
| P5 | 빨리 맞힐수록 더 이동 | ✅ | 정답 1,000 + 시간 보너스 최대 500 |
| P6 | 문제당 제한시간, 1회 제출, 시간 초과는 오답 | ✅ | 서버가 검증 |
| P7 | 시작 후 입장 불가 | ✅ | |
| P8 | 이탈 처리(오답 처리·순위 유지), **방장 승계** | ❌ | 이탈 개념이 없다. 방장이 나가면 승계하지 않는다 |
| P9 | 동점 처리: 정답 수 → 평균 응답 속도 | ❌ | 서버는 순위를 계산하지 않는다(프론트가 점수로 공동 순위). SQL은 DB 설계서에 검증해 둠 |
| P10 | 일정 시간 시작하지 않은 방 자동 닫기 | 🟡 | 만든 지 6시간 뒤 삭제(시작 여부와 무관, Redis 모드에서는 키 TTL), 준비 실패 방은 10분 |
| P11 | **로그인**(닉네임·프로필 사진) | ❌ | 참가자는 방마다 임시 토큰만 있다 |
| P12 | **전적·마이페이지·오답 문제 확인** | ❌ | 방·응답을 저장하지 않는다. v2 스키마에 설계됨 |
| P13 | 관리자: 신고된 문제 검토 | ✅ | 신고 3회 자동 제외 + 관리자 API |
| P14 | 관리자: 키워드·문제 **등록/수정/삭제** | 🟡 | 비활성/복구만. 등록·수정은 없다 |
| P15 | **Load Balancer + Auto Scaling** | 🟡 | **백엔드는 전제를 충족하고 LB + 서버 2대 + 장애 전환을 로컬에서 검증.** NCP LB·ASG는 만들지 않음 |
| P16 | **WebSocket + Redis(Pub/Sub, 방 상태, 캐릭터 위치·순위)** | 🟡 | **Redis 방 상태는 ✅**(락·접속 신호·TTL 포함). **WebSocket과 Pub/Sub은 없다** — 폴링이라 서버가 늘어도 같은 결과가 나오므로 필요하지 않았다 |
| P17 | **Cloud DB for MySQL**: 문제·키워드·사용자·퀴즈 기록 | 🟡 | **문제만** 저장. 키워드는 문자열, 사용자·기록 없음 |
| P18 | **Object Storage**: 프로필 이미지 | ❌ | |
| P19 | **Cloud Insight**: 부하·서버 대수 확인 | 📐 | |

**집계** (총 19개 항목): ✅ **6** · 🟡 **6** · 📐 **1** · ❌ **6**
(이전 판: ✅ 6 · 🟡 4 · 📐 1 · ❌ 8)

> **기획서의 "보충: 기술 구성" 5개 중 상태**: LB + Auto Scaling 🟡(백엔드 전제 충족·로컬 검증), WebSocket + Redis 🟡(Redis ✅·WebSocket ❌), Cloud DB for MySQL 🟡(문제만), Object Storage ❌, Cloud Insight 📐.

---

## 5. 이전 문서의 정정 (수업 범위 기준)

이전 `architecture.md`에 제가 일반적인 구성으로 넣었던 서비스 중 **수업에서 다루지 않은 것**입니다. 쓰면 안 된다는 뜻이 아니라, **"수업 기술을 적극 활용했다"는 근거로 쓸 수 없다**는 뜻입니다. `architecture.md`를 아래대로 정정했습니다.

| 이전 문서의 서비스 | 수업 등장 | 정정 |
|---|---|---|
| Container Registry, NKS(선택) | 0회 | 삭제. 배포는 **서버 이미지 + Init Script + Launch Configuration**(Day2·Day4) — 실제 스크립트까지 작성 |
| Secret Manager | 0회 | 삭제. 환경변수/설정 파일(권한 640)로 주입하고 Init Script 노출 위험을 명시 |
| Sub Account(IAM) | 0회 | 삭제 |
| CDN+ | 1회 | 삭제. 프론트는 앱 서버가 서빙(권장) 또는 Object Storage 정적 호스팅(Day3) |
| Cloud Log Analytics | 1회(실습 팝업) | 선택 사항으로 격하. 로그는 `journalctl -u quizrun` |
| **"Cloud DB for Redis"** | — | 수업의 이름은 **"Cloud DB for Cache (Redis 기반)"** 로 정정 |
| ALB의 경로 기반 라우팅 | 슬라이드에는 "L7은 URL까지 본다"만 | 경로 라우팅을 전제로 하지 않는 구성으로 정정 |
| Docker 기반 실행 | 0회 | **개발·검증 편의 수단으로만** 표기. 배포 실체는 jar |

**이전 판에서 정정한 설명**: "상태 비저장 엔진이라 서버를 늘려도 똑같이 동작한다"는 이전 문장은 **틀렸습니다.** 타이머를 없앤 것은 전제 조건이었고 상태는 서버 메모리에 있었습니다. 3.1절의 실험으로 확인했고, 지금은 Redis 외부화로 해결했습니다.

---

## 6. 인프라 담당(정하람)과 맞출 인터페이스

값은 **제안이며 팀 합의가 필요합니다.** 단계별 절차는 [VM 배포 가이드](deploy-vm.md)에 있습니다.

| 항목 | 백엔드가 요구하는 값 | 수업 근거 |
|---|---|---|
| 리스닝 포트 | `PORT` 환경변수, 기본 **3000** | — |
| LB 리스너 → Target Group | 80(또는 443) → 서버 3000 | Day4 LB |
| **Target Group Health Check** | 프로토콜 HTTP, 포트 3000, **URL Path `/actuator/health/readiness`**, 메서드 HEAD 또는 GET, 정상 응답 200, **주기 5~10초 권장**(실습의 30초 × 2회면 죽은 서버가 최대 약 60초 남는다) | Day4 Health Check, 실습 9 |
| 앱 ACG Inbound | **LB 서브넷에서 오는 TCP 3000**만. 관리용 SSH(22)는 필요한 곳에서만 | Day1 최소 권한 |
| 앱 ACG Outbound | **TCP 443** → CLOVA Studio(`clovastudio.stream.ntruss.com`), 위키백과(`ko.wikipedia.org`), 나무위키(`namu.wiki`). Private Subnet이면 **NAT Gateway 경유** | Day1 NAT |
| DB ACG Inbound | **TCP 3306, 접근 소스 = 앱 ACG** | 실습 11 |
| **Cache ACG Inbound** | **Cache 포트(6379), 접근 소스 = 앱 ACG** | Day4 |
| **Cloud DB for Cache** | 생성 후 **접속 주소·포트·비밀번호·SSL 여부**를 백엔드에 전달 (`REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `REDIS_SSL`) | Day4 무상태 설계 |
| 환경변수 | `SPRING_PROFILES_ACTIVE=redis`, `REDIS_*`, `DB_URL`(`jdbc:mysql://<DB Private 도메인>:3306/quizrun?characterEncoding=UTF-8`), `DB_USER`, `DB_PASSWORD`, `NCP_CLOVA_API_KEY`, `ADMIN_TOKEN` — **Init Script가 쓴다** | Day2 Init Script |
| 런타임 | **Java 21** — `install-app.sh`가 `dnf`로 설치(Rocky Linux 8 기본 저장소) | Day2 |
| DB 스키마 | 처음에는 **서버 1대로 시작**해 테이블을 자동 생성한 뒤 확장(서버 동시 첫 기동 시 충돌 위험). 운영 전 SQL 적용 + `ddl-auto=validate` | 실습 11 |
| 새 서버 준비 시간 | Init Script 후 **약 16초**. LB 헬스체크가 통과해야 트래픽을 받는다 | Day2 "운영중 ≠ 준비 완료" |

---

## 7. 우선순위 (갱신)

| 순위 | 작업 | 상태 | 담당 |
|---|---|---|---|
| ~~1~~ | ~~방 상태를 Cloud DB for Cache(Redis)로 외부화 → 3.1절 실험 통과~~ | ✅ **완료**(컨테이너) | 백엔드 |
| ~~2~~ | ~~Init Script + 서버 이미지 배포 절차~~ | ✅ **완료**(Rocky 8.9, NCP 미실행) | 백엔드 |
| **1** | **NCP 리소스 구성**: VPC/Subnet/ACG/NAT, 골든 서버 → 서버 이미지, Application LB + Target Group, Launch Configuration + ASG, Cloud DB for MySQL(HA) + Cache — [배포 가이드](deploy-vm.md) | 📐 | **인프라** |
| 2 | **NCP에서 재검증**: 실제 Cloud DB for Cache·MySQL 접속(인증·SSL), 서버 2대 실험과 서버 정지 실험을 NCP에서 반복 | 📐 1 이후 | 인프라 + 백엔드 |
| 3 | 전적·로그인·기록 저장 (v2 스키마 이행) | 📐 | 백엔드(DB) |
| 4 | Object Storage 프로필 이미지 | 📐 | 백엔드 + 프론트 |
| 5 | 기획서 규칙 보완(최소 인원, 이탈·방장 승계, 동점 처리) | 📐 | 백엔드 |
| 6 | 부하 테스트 → Cloud Insight Event Rule 임계값, 헬스체크 주기. **100명 방의 읽기 비용**(p95 339ms) 개선 여부 판단 | 📐 | 인프라 + 백엔드 |
| 7 | 스키마를 SQL로 먼저 적용 + `ddl-auto=validate`(서버 동시 첫 기동 충돌 제거) | 📐 | 백엔드(DB) |
| 8 | WebSocket 전환 | 📐 후순위 | 백엔드 + 프론트 |

---

## 8. 발표에서 말할 수 있는 것과 없는 것

| ✅ 말해도 되는 것 (검증함) | ❌ 지금은 말하면 안 되는 것 |
|---|---|
| **서버 2대 실험으로 무상태 문제를 찾아냈고(404), Redis로 외부화해 같은 실험을 통과시켰다** | "NCP에 Load Balancer와 Auto Scaling을 적용했다" (NCP 리소스는 없다) |
| LB(Round Robin) 뒤 서버 2대로 한 판을 진행했고, **게임 도중 서버 1대를 죽여도 끝까지 진행**됐다(로컬) | "Cloud DB for Cache / Cloud DB for MySQL을 사용한다" (실제 인스턴스 없음, 컨테이너로 검증) |
| 서버 이미지 + Init Script + systemd 방식으로 **Docker 없이 Rocky Linux 8에서 앱이 올라오는 것**을 검증했다. 이미지에 비밀값이 들어가지 않게 설계했다 | "NCP 서버 이미지로 서버를 복제해 운영한다" (콘솔 작업은 안 했다) |
| LB 헬스체크는 `readiness`를 써야 Redis 장애 서버가 제외된다는 것을 실험으로 확인했다 | "서버를 얼마든지 늘려도 된다" (100명 방의 읽기 비용·Redis 단일 장애점이 남아 있다) |
| DB를 수업 순서대로 설계했고(ER → 관계 스키마 → 정규화) MySQL에서 제약조건을 시험했다 | "전적·마이페이지·로그인을 지원한다" |
| 수업에서 배운 기준으로 우리 구현을 직접 점검했고 격차를 문서화했고, 그 격차를 줄여 나갔다 | "수업에서 배운 기술을 모두 적극 활용했다" |
