# QuizRun

실시간 퀴즈 레이스 웹 서비스 (NCP 클라우드 과정 5조 프로젝트).

| 폴더 | 내용 | 실행 |
|---|---|---|
| [`frontend/`](frontend) | 화면(`public/`) + Spring API 프록시. 게임 상태와 출제는 백엔드가 담당 | `cd frontend && npm start` → `http://localhost:5173` |
| [`backend/`](backend) | Spring Boot API 서버 (Redis 상태 외부화, AI 문제 생성, 문제은행). 문서·DB·배포 스크립트 포함 | [backend/README.md](backend/README.md) |

## 프론트와 백엔드 함께 실행

1. `backend/`에서 `gradle bootRun --args='--spring.profiles.active=local'`로 Spring을 실행합니다(기본 3000 포트). Docker 사용법은 백엔드 문서를 참고하세요.
2. Spring의 `local` 프로파일은 `frontend/public/`도 서빙하므로 `http://localhost:3000`에서 바로 플레이할 수 있습니다.
3. 프론트를 별도 프로세스로 개발하려면 `frontend/`에서 `npm start`를 실행하고 `http://localhost:5173`에 접속합니다. `/api/*`는 기본 `http://127.0.0.1:3000`으로 전달됩니다. 다른 주소는 `frontend/.env`의 `BACKEND_URL`로 지정합니다.

프론트와 백엔드를 함께 업데이트해야 `/api/config`, 사진·프로필 수정, 초대 전용 방이 연결됩니다. 방 생성은 즉시 응답하며 문제 준비 상태·실패·출처·실제 문제 수를 대기실에 표시합니다. 문제 신고도 현재 문제 또는 결과 화면에서 제출할 수 있습니다. AI 키는 `backend`의 `NCP_CLOVA_API_KEY`에만 설정합니다.

## 규칙
- 프론트 작업은 `frontend/`, 백엔드 작업은 `backend/` 안에서만 합니다. 서로의 폴더를 고칠 때는 PR에서 담당자를 리뷰어로 지정합니다.
- API가 바뀌면 [`backend/docs/api.md`](backend/docs/api.md)를 같이 고칩니다.
- 비밀값(`.env`, API 키)은 커밋하지 않습니다. 예시는 각 폴더의 `.env.example`을 사용합니다.
- `main`에 직접 푸쉬하지 않고 브랜치 → PR로 올립니다.
