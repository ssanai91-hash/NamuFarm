# 나무팜 관리자 대시보드

- 진입 주소: `https://namufarm.co.kr/admin`.
- 기존 통합 대시보드의 Sites 소스 `69cd6ef359323998ace1d8c885baeab33a3c10d0`에서 만든 `NamuFarm-Unified-Dashboard.html`을 같은 도메인에 제공한다.
- `dashboard.html`은 `public/` 밖에 두고 서버 번들에만 포함한다. `app/admin/route.ts`가 요청마다 HTTP Basic 아이디·비밀번호를 검사한 뒤 반환한다. 미입력·오입력은 401과 브라우저 로그인 창을 표시한다. ChatGPT 계정은 필요하지 않으며 홈페이지 전체의 공개 범위는 유지한다.
- 로그인 값은 Sites 서버 전용 비밀 설정 `NAMUFARM_ADMIN_USERNAME`, `NAMUFARM_ADMIN_PASSWORD`로 관리한다. 미설정은 503으로 닫힌다. 실제 값은 소스·화면 HTML에 넣지 않는다. 응답은 private/no-store 및 noindex이며 운영 연결을 막는 CSP를 포함한다.
- 견적·판매·문의·동기화와 점검 기록은 합성 사례 및 현재 브라우저 저장이다. 실제 PC·Android·Sheets 연결 기능이 아니다. 별도 Sites/파일과 이 도메인의 브라우저 저장 영역은 다르며, 기존 운영 점검 v2의 JSON 메모·이력은 가져올 수 있다.
- 고객용 홈페이지, 기존 API·입력 검증·접수 처리·Apps Script·기존 접수용 환경변수는 변경하지 않는다.

## 갱신

통합 대시보드를 수정하면 해당 소스에서 같은 단일 HTML을 다시 생성해 이 파일만 교체한다. 운영 인증 정보나 고객자료를 HTML에 넣지 않는다. HTTP Basic 로그인 값은 브라우저가 관리하며, 이전 ChatGPT 로그아웃 링크는 제거했다.

## 사용자 확인

`/admin`에서 공유 아이디·비밀번호 로그인, 잘못된 비밀번호의 접근 제한, 업무·운영 점검 메뉴, 메모·체크 저장, 통합 백업을 직접 확인한다. 실제 견적 접수·문자·메일·시트 쓰기 시험은 수행하지 않는다.
