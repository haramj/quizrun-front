# QuizRun

실시간 퀴즈 레이스 웹 서비스 (NCP 클라우드 과정 5조 프로젝트).

| 폴더 | 내용 | 실행 |
|---|---|---|
| [`frontend/`](frontend) | 화면(`public/`) + Node 서버(전체 v2 API를 구현한 참조 서버) | `cd frontend && npm start` |
| [`backend/`](backend) | Spring Boot API 서버 (Redis 상태 외부화, AI 문제 생성, 문제은행). 문서·DB·배포 스크립트 포함 | [backend/README.md](backend/README.md) |

## 규칙
- 프론트 작업은 `frontend/`, 백엔드 작업은 `backend/` 안에서만 합니다. 서로의 폴더를 고칠 때는 PR에서 담당자를 리뷰어로 지정합니다.
- API가 바뀌면 [`backend/docs/api.md`](backend/docs/api.md)를 같이 고칩니다.
- 비밀값(`.env`, API 키)은 커밋하지 않습니다. 예시는 각 폴더의 `.env.example`을 사용합니다.
- `main`에 직접 푸쉬하지 않고 브랜치 → PR로 올립니다.
