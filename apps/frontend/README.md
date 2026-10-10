# Frontend (TASK-017b 업무지원 화면)

행원이 담당 상담 건을 열면 적용 공문, 승인 checklist, 원문 근거가 먼저 보이고, AI 준비안(READY/PARTIAL/HOLD)과 직원 근거 확인을 기록하는 화면입니다. 합성 자료만 다룹니다. 브라우저는 Core만 부르며(ADR-014) 서비스 토큰이나 grant ID를 다루지 않습니다. 대출 승인·거절, 금리·한도 확정, 신용등급 결정 기능은 없습니다.

기술: React + TypeScript + Vite, Tailwind, shadcn/ui 방식 컴포넌트(`src/components/ui`), React Router, TanStack Query. 의존성은 `package.json`의 정확한 버전과 `package-lock.json`으로 고정합니다(배포 뒤 2주 이상 지난 버전).

```bash
npm ci
npm run dev        # Core(기본 http://127.0.0.1:8080, TRUST_AGENT_CORE_URL로 변경)로 API·로그인을 프록시
npm run typecheck && npm run lint && npm test && npm run build
```

배포 형태에서는 `npm run build` 결과(`dist/`)를 Core가 정적 자원으로 제공합니다. Gradle `processResources`가 `apps/frontend/dist`를 Core의 `static/`에 넣습니다(같은 출처 세션 쿠키, CSRF는 `XSRF-TOKEN` 쿠키 → `X-XSRF-TOKEN` 헤더).

화면: 로그인, 상담 건 목록·생성, 상담 상세(신청 요약, 공문군별 적용 공문·승인 checklist·구조화 값·원문 근거, READY 공문군의 직원 근거 확인), 우측 접이식 AI 업무지원 패널(준비안 생성·저장본 조회. 근거 검색·질문 응답은 후속 연결), 검수 현황(REVIEWER, 읽기 전용). 업무일은 URL `?businessDate=YYYY-MM-DD`로 지정하며 기본값은 서울 기준 오늘입니다.

사유 코드 설명 `src/lib/reasonMessages.json`은 AI 서비스 `messages.py`의 표에서 내보낸 것이며 `tests/contract/test_frontend_reason_messages.py`가 일치를 확인합니다.

브라우저 E2E는 실제 Core + PostgreSQL + AI 서비스로 실행합니다(필수 CI 미포함). 먼저 `npm run build`와 `npx playwright install chromium`을 한 뒤 저장소 루트에서:

```bash
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :apps:core-service:test --tests '*UiEndToEndRunner' -PuiE2E=true -PaiServiceIntegration=true --no-daemon
```

결과와 화면 캡처는 `docs/evidence/task-017b/`에 남습니다.
