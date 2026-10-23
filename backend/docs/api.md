# API 명세

기본 주소 `http://localhost:3000`. 모든 응답은 JSON이며, 오류는 `{"error": "사용자에게 보여줄 메시지"}` 형태입니다.
이 계약은 기존 Node 서버(`server.mjs`)와 **JSON 키 구조까지 동일**하게 검증했습니다(프론트 수정 없이 교체 가능). 추가된 필드는 `source`와 `credits`뿐입니다.

## 인증 모델

로그인은 없습니다. 방을 만들거나 입장하면 **참가자 토큰**(48자리 난수)을 받고, 이후 요청에 이 토큰을 붙입니다.

- `GET` 요청: `Authorization: Bearer <token>` 헤더
- `POST` 요청: 본문의 `token` 필드
- 토큰은 `state` 응답에 절대 포함되지 않습니다. 비교는 상수 시간(`MessageDigest.isEqual`)으로 합니다.

## 엔드포인트

| 메서드 | 경로 | 설명 |
|---|---|---|
| GET | `/api/health` | 로드밸런서 헬스체크 → `{"ok":true}` |
| GET | `/api/rooms` | 대기 중인 방 목록 (문제 준비에 실패한 방은 제외) |
| POST | `/api/rooms` | 방 생성 → `201 {code, token}`. **즉시 응답한다.** AI 생성이 필요한 키워드는 문제를 백그라운드에서 준비하고 `state.prepare`로 진행을 알린다 |
| POST | `/api/rooms/{code}/join` | 입장 → `{code, token}` |
| GET | `/api/rooms/{code}/state` | 방 전체 상태 (프론트가 700ms마다 폴링) |
| POST | `/api/rooms/{code}/start` | 방장이 수동 시작 |
| POST | `/api/rooms/{code}/answer` | 답 제출 (`answer`: 0~3) |
| POST | `/api/rooms/{code}/report` | **문제 신고** (아래 참고) |
| POST | `/api/rooms/{code}/chat` | 채팅 (`text`: 1~200자) |
| GET | `/actuator/health` | 상세 헬스 (`/liveness`, `/readiness` 포함) |
| GET | `/api/admin/questions` | **관리자** 문제은행 목록 (신고 많은 순) |
| POST | `/api/admin/questions/{id}/deactivate` | **관리자** 문제를 출제에서 제외 |
| POST | `/api/admin/questions/{id}/reactivate` | **관리자** 제외한 문제 복구 |

### POST /api/rooms

```json
{ "keyword": "손흥민", "name": "Host", "capacity": 4, "timeLimit": 10, "count": 6, "title": "선택" }
```

| 필드 | 규칙 | 기본값 |
|---|---|---|
| `keyword` | 필수, 30자 이내. 줄바꿈·따옴표는 제거됨. 내장 카테고리 이름(상식·개발…)이면 AI 없이 바로 준비 완료 | - |
| `capacity` | 정수 2~100 | 4 |
| `timeLimit` | 5 · 10 · 15 · 20 · 30 (초) | 10 |
| `count` | 4 · 6 · 8 · 10 · 12, 그 외 값은 무시. 검증을 통과한 문제가 모자라면 **더 적은 수(절반 이상, 최소 3개)로 진행**될 수 있다. 실제 문제 수는 준비가 끝난 뒤의 `total`을 따를 것 | 6 |

### 방 생성과 문제 준비 (비동기)

AI가 문제를 만드는 데 15~30초가 걸리므로, **방 생성은 기다리지 않고 바로 응답하고 문제는 대기실에서 준비합니다.**

```
POST /api/rooms ──▶ 201 {code, token}  (수십 ms)      state.prepare = "preparing"
                          │  백그라운드: 위키 자료 조회 → 문제은행 + AI 생성 → 검증
                          ▼
                 state.prepare = "ready"  (또는 "failed" + prepareError)
```

| `state.prepare` | 의미 | 프론트 처리 |
|---|---|---|
| `preparing` | 문제를 만드는 중 | "문제 준비 중…" 표시. 입장·채팅은 가능. `total`은 요청한 수 |
| `ready` | 준비 완료 | `total`이 실제 문제 수로 확정, `source`·`credits` 채워짐 |
| `failed` | 준비 실패 | `prepareError`를 보여 주고 새 방을 만들게 안내. 이 방은 입장·시작 불가, 목록에서 빠지고 10분 뒤 삭제 |

- **준비 중에 시작해도 됩니다.** 정원이 차거나 방장이 시작을 눌러도 거부하지 않고 기억해 두었다가, **준비가 끝나는 즉시 5초 카운트다운을 시작**합니다. 그래서 프론트는 별도 처리 없이 `phase`가 `countdown`으로 바뀌는 것만 따라가면 됩니다.
- 내장 카테고리 이름(상식·개발 등)이나 AI가 꺼진 서버는 요청 안에서 바로 준비되어 `prepare: "ready"`로 시작합니다. 키워드가 잘못된 경우의 오류도 예전처럼 **즉시 4xx**로 옵니다.
- 비동기 경로에서 생긴 실패(자료 없음, AI 실패 등)는 HTTP 오류가 아니라 `prepare: "failed"` + `prepareError`로 전달됩니다. **프론트는 `state`에서 `failed`를 반드시 처리해야 합니다.**
- **현재 프론트(`prepare`를 모르는 버전)를 수정하지 않아도 동작합니다**(실제 브라우저로 확인): 방이 즉시 열리고, 문제를 만드는 중에 "카운트다운 시작"을 눌러도 준비가 끝나는 대로 자동 출발합니다. 다만 "문제 준비 중" 표시와 **실패 안내는 없습니다** — 실패하면 대기실에 그대로 머뭅니다.
- 동시에 준비하는 방은 4개(`quizrun.prepare.threads`), 대기열은 50개입니다. 넘치면 새 방이 `failed`("지금 요청이 많아요")로 표시됩니다.

### GET /api/rooms/{code}/state

```json
{
  "serverTime": 1792761508000, "code": "4A9BAD", "title": "손흥민 한 판 달려!",
  "keyword": "손흥민", "source": "mixed", "credits": ["위키백과 · 손흥민"],
  "prepare": "ready", "prepareError": null, "capacity": 2,
  "phase": "playing", "index": 0, "total": 6, "deadline": 1792761518000, "timeLimit": 10,
  "messages": [], "host": "<playerId>", "you": "<playerId>",
  "players": [{ "id": "", "name": "", "color": "", "score": 0, "correct": 0,
                "lastPoints": 0, "online": true, "answered": false }],
  "question": { "text": "…", "options": ["", "", "", ""] },
  "answer": null, "yourAnswer": null
}
```

- `prepare`: `"preparing"` · `"ready"` · `"failed"` (위 "방 생성과 문제 준비" 참고). `prepareError`는 `failed`일 때만 문자열.
- `total`: 준비 전에는 요청한 문제 수, 준비 후에는 실제 문제 수.
- `source`·`credits`: 준비 전에는 `null`·빈 배열.
- `source`: `"ai"`(전부 새로 생성) · `"mixed"`(문제은행 + 새로 생성) · `"bank"`(전부 문제은행/내장 문제)
- `credits`: 문제에 쓰인 근거 자료 출처 목록. **화면에 출처를 표시해야 합니다** (나무위키는 CC BY-NC-SA 2.0 KR).
- `phase`: `waiting → countdown → playing ⇄ reveal → finished`
- `question`은 `playing`/`reveal`에서만, `answer`(정답 인덱스)는 **`reveal`에서만** 채워집니다.
- `serverTime`으로 프론트가 시계 오차를 보정합니다.

### POST /api/rooms/{code}/report — 문제 신고

화면에 이미 나온 문제를 신고합니다. 서로 다른 신고자가 **3명**(`REPORT_THRESHOLD`)이 되면 그 문제는 자동으로 출제에서 제외됩니다.

```json
{ "token": "<참가자 토큰>", "index": 2, "reason": "WRONG_ANSWER", "detail": "정답이 틀린 것 같아요" }
```

| 필드 | 규칙 |
|---|---|
| `index` | 신고할 문제 번호. `state`의 `index`와 같은 기준(0부터). 이미 공개된 문제만 가능 (현재 문제, 지나간 문제, 종료 후 모든 문제) |
| `reason` | `WRONG_ANSWER`(정답 오류) · `AMBIGUOUS`(모호함) · `OFFENSIVE`(부적절) · `OTHER`(기타) |
| `detail` | 선택, 200자 이내 |

응답: `{"ok": true, "recorded": true}`

- `recorded: false` — 같은 참가자가 같은 문제를 다시 신고했거나, 문제은행과 연결되지 않은 문제(내장 폴백 문제)입니다. 오류가 아닙니다.
- 신고해도 **진행 중인 판의 문제는 바뀌지 않습니다.** 다음 판부터 빠집니다.
- 신고 사실은 다른 참가자에게 보이지 않습니다. 프론트에는 문제 ID가 내려가지 않고, 서버가 `index`를 문제은행 ID로 연결합니다.
- `400`: 아직 공개되지 않은 문제 · 잘못된 `index` · 잘못된 `reason` · 참가자 토큰 오류

프론트 예시 (신고 버튼):

```js
await fetch(`/api/rooms/${code}/report`, {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ token, index: room.index, reason: 'WRONG_ANSWER' })
});
```

## 관리자 API

로그인이 없으므로 환경변수 `ADMIN_TOKEN` 값을 `X-Admin-Token` 헤더로 보냅니다. **`ADMIN_TOKEN`을 설정하지 않으면 관리자 API 전체가 꺼지고 `403`을 돌려줍니다.** 운영에서는 ACG/ALB로 접근 IP도 제한하세요. 정답과 근거 문장이 포함되므로 플레이어에게 노출되면 안 됩니다.

```bash
# 신고가 있는 문제 (신고 많은 순)
curl -H "X-Admin-Token: $ADMIN_TOKEN" "http://localhost:3000/api/admin/questions?status=reported"
# 문제를 출제에서 제외 / 복구
curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" http://localhost:3000/api/admin/questions/16/deactivate
curl -X POST -H "X-Admin-Token: $ADMIN_TOKEN" http://localhost:3000/api/admin/questions/16/reactivate
```

| 쿼리 | 설명 |
|---|---|
| `status` | `reported`(기본, 신고가 1건 이상) · `inactive`(제외된 문제) · `active` · `all` |
| `topic` | 키워드로 필터 (선택) |
| `limit` | 기본 50, 최대 200 |

응답 항목: `id, topic, text, options[4], answer, evidence, origin, source, reportCount, active, deactivatedReason, timesUsed`
(`deactivatedReason`: `reports` 신고 누적 / `admin` 관리자)

- **비활성화는 삭제가 아닙니다.** 행을 남겨야 AI가 같은 문제·같은 사실(근거 문장)을 새 문제로 다시 만들어 되살리는 것을 막을 수 있습니다.
- **복구**하면 다시 출제 대상이 되고, 신고 기록과 횟수가 초기화됩니다.

## 상태 전이

```
waiting ──(정원 충족 또는 방장 start)──▶ countdown (5초)
countdown ──▶ playing (timeLimit초) ──▶ reveal (3.5초) ──▶ playing … ──▶ finished
                 └─ 전원이 답하면 즉시 reveal
```

## 오류 코드

| 상태 | 상황 |
|---|---|
| 400 | 검증 실패 · 문제 준비에 실패한 방에 입장/시작 · 토큰 오류("다시 입장해 주세요.") · 마감 후 제출 · 중복 제출 · 방장 아님 · 이미 시작한 방에 입장 · 정원 초과 |
| 403 | 관리자 API: 토큰이 없거나 틀림, 또는 `ADMIN_TOKEN` 미설정 |
| 404 | 없는 방 코드, 없는 경로, 없는 문제 ID(관리자) |
| 503 | (카테고리 경로의 즉시 오류) 모든 문제가 제외된 카테고리. AI 경로의 "자료 없음/생성 실패"는 HTTP가 아니라 `state.prepareError`로 전달된다. 예전 동작: 근거 자료를 찾지 못했거나("이 키워드는 참고할 자료를 찾지 못했어요…"), AI가 검증을 통과한 문제를 충분히 만들지 못했고("AI가 문제를 만들지 못했어요…") 문제은행에도 없는 키워드 |
| 500 | 처리되지 않은 서버 오류 (상세는 로그에만 기록) |
