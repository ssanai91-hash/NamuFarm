# 나무팜 홈페이지

묘목 소개와 고객 견적 문의를 위한 홈페이지입니다. 고객이 품목·수량·연락처·배송 지역을 입력하고 개인정보 처리 안내를 확인하고, 최종 확인을 거쳐 문의를 접수합니다. 접수 내용은 별도 Google Sheet와 Android 앱의 **홈페이지 요청함**으로 연결됩니다.

이 폴더에는 홈페이지 화면, 서버 API, 이미지, 의존성 잠금 파일과 Sites 빌드 설정을 함께 보관합니다. Android 앱은 저장소 루트의 `app/`, 접수용 Apps Script는 `server/quote-requests/`에 있습니다.

## 로컬 실행

Node.js 22.13.0 이상과 `package.json`에 지정된 pnpm 11.25.0을 사용합니다. Corepack이 설치된 환경에서는 저장소 루트에서 다음 명령을 실행합니다.

```sh
cd website
corepack pnpm install --frozen-lockfile
cp .env.example .env
corepack pnpm dev
```

개발 주소는 기본 `http://localhost:5173`입니다. `.env`가 비어 있어도 화면과 입력·최종 확인을 살펴볼 수 있으며, 실제 전송은 준비 상태로 표시됩니다. Windows에서는 `.env.example`을 `.env`로 복사하세요.

```sh
corepack pnpm exec tsc --noEmit
corepack pnpm build
corepack pnpm start
```

`build`는 Worker 산출물을 만들고, `start`는 빌드된 Worker를 로컬에서 실행합니다. 상세 실행 프로필과 Sites 런타임 설명은 [SITES_RUNTIME.md](SITES_RUNTIME.md)를 참고하세요.

## 접수 서버 연결

서버에서만 읽는 설정은 빈 [`.env.example`](.env.example)에 정의되어 있습니다.

| 설정 | 내용 |
|---|---|
| `QUOTE_SCRIPT_URL` | 접수용 Apps Script 웹 앱의 `/exec` 주소 |
| `QUOTE_WEB_SECRET` | Apps Script의 `WEB_SECRET`과 같은 32자 이상 비밀키 |
| `QUOTE_PRIVACY_NOTICE` | 운영자가 확정한 개인정보 처리방침(처리 근거·항목·목적·문의 연락처·보유·파기 기준 포함) |

이 값들은 실제 운영 환경의 비밀 설정에 넣고 Git에 커밋하지 않습니다. 세 설정과 동의 없이 견적을 처리하는 접수 서버 v2의 `processingBases: ["quote_request"]` 응답이 모두 확인돼야 고객 접수가 활성화됩니다. 새 요청에는 `consent`를 보내지 않습니다. 과거에 동의한 요청의 재시도는 기존 값과 접수번호를 보존합니다. 전체 설정 순서와 Android·FCM 연결은 [홈페이지 접수 안내](../docs/WEB_QUOTE_REQUESTS.md)에 있습니다.

## 주요 소스

| 경로 | 역할 |
|---|---|
| `components/quote-home.tsx` | 소개·품목 선택·문의 입력·처리 안내·최종 확인 화면 |
| `app/privacy/page.tsx` | 개인정보 처리방침 및 접수 준비 안내 |
| `app/globals.css`, `public/` | 스타일과 홈페이지 이미지 |
| `lib/quote-schema.ts` | 입력 및 접수번호 검증 |
| `lib/quote-server.ts` | 서버 환경 설정과 준비 상태 검사 |
| `app/api/config/route.ts` | 접수 준비 상태·개인정보 안내 제공 |
| `app/api/quote-requests/route.ts` | Apps Script 서버 중계와 응답 확인 |
| `build/`, `scripts/`, `vite.config.ts` | Vinext·Cloudflare Worker·Sites 실행과 빌드 |

## 배포

현재 [나무팜 홈페이지](https://namufarm.co.kr)는 Sites에서 공개로 호스팅됩니다. `.openai/hosting.json`은 기존 Site의 프로젝트 정보를 유지합니다. 이 GitHub 폴더를 수정하거나 커밋하는 것만으로 운영 Site가 자동 배포되지는 않으며, 배포는 별도로 수행합니다.

이 폴더의 최초 소스는 Sites 커밋 `649f493ce9ca0d7e65b18c92e39626523c2239f9` 기준입니다. 실행 안내와 Git 제외 규칙을 정리하고, 생성된 TypeScript 캐시를 제외했습니다. 독립 실행 빌드에서는 여러 환경의 Sites 메타데이터 복사가 겹치지 않도록 `build/sites-vite-plugin.ts`의 복사 작업을 순서대로 실행합니다.
