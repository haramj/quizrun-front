# QUIZRUN Frontend — 퀴즈 월드

작은 육상 경기장을 배경으로 친구들과 함께 달리는 퀴즈 프론트엔드입니다. 순수 JavaScript/CSS/Canvas로 구현하며, 게임 상태·출제·채점·사진 저장은 `backend/`의 Spring API가 담당합니다.

## 실행

Java 21과 Gradle이 있는 환경에서 먼저 백엔드를 실행합니다.

```sh
cd backend
gradle bootRun --args='--spring.profiles.active=local'
# http://localhost:3000 : Spring API + frontend/public 정적 파일
```

Spring의 `local` 프로파일 또는 기존 Docker Compose를 사용하면 프론트 정적 파일도 함께 제공하므로 Node 없이 3000 포트에서 플레이할 수 있습니다.

프론트를 별도 프로세스로 개발할 때는 Node.js 20.12 이상에서 다음을 실행합니다. 외부 npm 의존성은 없습니다.

```sh
cd frontend
npm start
# http://localhost:5173
```

`server.mjs`는 정적 파일을 제공하고 `/api/*` 요청을 Spring에 전달하는 프록시입니다. 방이나 점수를 별도로 만들지 않습니다. `frontend/.env.example`을 `.env`로 복사하면 다음 값을 바꿀 수 있습니다.

```dotenv
PORT=5173
BACKEND_URL=http://127.0.0.1:3000
```

프론트와 백엔드는 서로 다른 포트를 사용해야 합니다. 브라우저는 상대 경로 `/api/*`로 요청하므로 개발 프록시와 Spring의 정적 파일 제공 방식 모두 같은 코드로 동작합니다. API 키는 프론트가 아닌 백엔드 환경변수 `NCP_CLOVA_API_KEY`에만 설정합니다. Node는 백엔드의 `.env`를 대신 읽지 않습니다.

## Spring API와의 연결

- `/api/config`: AI 설정 여부, 키워드 최대 길이, 문제 수와 지원 기능을 조회합니다. 키 값은 내려오지 않습니다.
- 방 생성에는 숫자형 `count`, `capacity`, `timeLimit`과 최대 30자의 `keyword`, `name`, `avatar`, `visibility`를 보냅니다. 예전 `questionSource` 선택 필드는 보내지 않습니다.
- 카테고리는 문제은행에서, 그 외 키워드는 서버 설정에 따라 자료·문제은행·AI를 이용해 출제합니다. 결과의 `source`(`bank`/`ai`/`mixed`)를 그대로 표시합니다.
- 방 생성은 즉시 응답합니다. 대기실은 `prepare`(`preparing`/`ready`/`failed`)와 `prepareError`를 읽어 준비 중·준비 완료·실패를 표시합니다. 실패한 방에는 시작 버튼을 표시하지 않고 새 방 만들기를 안내합니다.
- 준비 중 시작 요청은 서버에 예약됩니다. `startRequested`를 표시하고, 실제 `phase`가 바뀌면 5초 카운트다운에 따릅니다.
- 문제 준비가 끝나면 `total`이 실제 출제 수입니다. `requestedCount`보다 작으면 검증된 문제로 축소됐다는 안내를 표시합니다.
- `credits`는 대기실·경기·결과 화면에 출처로 표시합니다. 나무위키 자료가 있으면 라이선스 링크도 표시합니다.
- 현재 문제와 결과 화면에서 `/report`로 신고할 수 있습니다. 신고를 연 당시의 방·문제 번호를 고정하므로 폴링 중 다음 문제로 넘어가도 신고 대상이 바뀌지 않습니다.

상태는 700ms 폴링으로 동기화합니다. GET 상태 조회에는 Bearer 토큰을, POST에는 본문 `token`을 전달합니다. 토큰은 브라우저 세션 저장소에 보관하고 URL에 넣지 않습니다. 채팅 입력과 신고 창은 상태 갱신 중에도 유지됩니다.

## 사진과 러너

프로필에서 JPG·PNG·WebP(최대 8MB)를 선택하고 원 안에서 드래그하거나 두 손가락·휠·크기 막대로 0.5~5배 조절할 수 있습니다. 방향키로 이동, +/-로 확대·축소, Enter로 적용, Escape로 취소할 수 있습니다. 1배보다 작게 줄이면 흰색 여백도 저장됩니다.

브라우저는 사진을 256×256 JPEG로 변환합니다. Spring은 JPEG 형식·해상도·48KB 크기를 검증하고 해시 URL로 저장합니다. 생성·입장 시 사진을 전달하며, `/profile`로 이름과 사진을 바꾸면 다른 참가자에게도 반영됩니다. 상태에는 사진 URL만 들어가 큰 Base64가 반복 전송되지 않습니다.

백엔드의 메모리 모드에서는 사진도 해당 서버 메모리에 있습니다. Redis 모드에서는 사진을 `quizrun:avatar:{hash}` 키에 TTL과 함께 두어 다른 API 서버에서도 조회할 수 있습니다. 상세 계약은 [백엔드 API 문서](../backend/docs/api.md)를 참고하세요.

## 디자인과 경기

홈·내 캐릭터·방 설정·초대 코드·대기실·퀴즈·결과 화면에 크림색과 잔디색, 빨간 트랙, 큰 얼굴의 토이 러너를 사용합니다. 밝은 화면과 어두운 화면을 지원합니다.

서버 카운트다운에 맞춰 준비 자세 → 신호총·연기 → 달리기로 이어집니다. 별도 연출 시간을 추가하지 않아 설정한 풀이 시간을 그대로 사용합니다. 정답 공개 때 오른 점수에 따라 1.2초간 스퍼트합니다. 동작 줄이기 설정도 반영합니다.

큰 화면은 6명, 휴대폰은 4명씩 트랙을 전환하며 `내 러너 보기`로 돌아올 수 있습니다. 순위표에는 모두 표시됩니다. 초대 전용 방은 공개 목록에 나타나지 않으며 코드를 아는 사람은 입장할 수 있습니다.

## 검증

```sh
npm test
# 또는 node --test test.mjs quiz-generator.test.mjs crop.test.mjs bridge.test.mjs quiz-state.test.mjs
```

`bridge.test.mjs`는 인증·숫자형 JSON·오류 상태·사진 캐시·백엔드 연결 실패가 프록시를 통해 유지되는지 검사합니다. `quiz-state.test.mjs`는 생성 요청과 준비/실패/축소 출제 처리를 검증합니다. 사진 크롭과 이전 Node API의 회귀 테스트도 유지합니다.

`reference-server.mjs`는 이전 Node API의 테스트용 구현입니다(`npm run start:reference`). 현재 서비스 실행과 Spring 연동은 `npm start` 또는 Spring의 `local` 프로파일을 사용합니다. 이전 Node API와 Spring을 같은 포트에 동시에 실행하면 안 됩니다.

Spring 테스트는 `backend/`에서 `gradle test`로 실행합니다. 실제 NCP 호출과 실제 Redis 인프라 검증은 별도의 키·서버가 필요합니다. 두 폴더를 함께 배포해야 새 사진·프로필 API가 연결됩니다.
