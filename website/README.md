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
| `QUOTE_TEST_MODE` | `true`이면 테스트 안내 팝업·테스트 전송/완료 문구를 표시합니다. 실제 접수를 시작할 때 `false`로 변경합니다. |

이 값들은 실제 운영 환경의 비밀 설정에 넣고 Git에 커밋하지 않습니다. 기본 설정은 서버에서 최초 화면을 만들 때 확인하므로, 느린 상태 조회 때문에 전송 버튼이 잠기지 않습니다. 전송 API는 API v5에 인증된 HTTPS 요청을 보내며 서버가 저장을 승인한 경우에만 접수 완료를 표시합니다. 새 요청에는 `consent`를 보내지 않습니다. 과거에 동의한 요청의 재시도는 기존 값과 접수번호를 보존합니다. 전체 설정 순서와 Android·FCM 연결은 [홈페이지 접수 안내](../docs/WEB_QUOTE_REQUESTS.md)에 있습니다.

고객 RSA 키 등록 없이 홈페이지 견적을 접수합니다. 서버의 WEB_SECRET, 입력 검증, 중복 방지, 기기 인증, 보관 종료 처리는 유지합니다. 전송 응답이 없으면 입력 내용과 접수번호를 유지해 같은 번호로 재시도합니다. 주문 묘목, 받는 사람·연락처, 공식 견적서 옵션은 입력 화면과 최종 확인 화면에서 구분됩니다.

이메일은 선택 입력이며, 공식 견적서 요청 시 문자 이미지·이메일 PDF·둘 다를 선택할 수 있습니다. 이메일 수신 선택 시 이메일이 필요합니다. 최종 확인 화면에서도 이메일·수신인·전달 방법을 다시 보여줍니다. PDF/JPG 견적서 작성과 발송 준비는 Android 앱에서 운영자가 수행합니다.

## 홈페이지 연결 테스트

실제 고객 접수는 `QUOTE_TEST_MODE=false`와 `docs/production-privacy-notice.txt`의 운영 안내를 설정합니다. 테스트 모드(`true`)에서는 테스트 안내를 표시하고 요청사항에 `[홈페이지 테스트 · 실제 견적 아님]` 표시를 붙입니다. 입력한 요청과 내부 UUID는 응답 유실 시에도 그대로 재사용하며, 이전에 전송 대기 중인 요청의 원문을 변경하지 않습니다.

접수 서버는 한국 날짜 기준 `NFQ-YYYYMMDD-NNNN` 견적접수번호를 발급합니다. 완료 화면에는 이 번호와 한국시간 접수일시를 표시하고, 시트의 Q/R 열에도 동일하게 저장합니다. A:P의 기존 UUID·UTC 접수시각·고객정보 구조 (기존 v4 암호문 읽기 호환)는 유지합니다. 접수순번은 삭제 동기화 시 바뀔 수 있으므로 고객용 접수번호로 사용하지 않습니다. 저장 후 같은 UUID·내용을 재전송하면 최초 번호와 접수일시를 돌려줍니다.

최신 서버 코드를 기존 Apps Script 프로젝트에 반영하고 `setupQuoteRequests()`를 실행한 뒤, 기존 웹앱 배포를 새 버전으로 갱신합니다. setup은 기존 행의 오른쪽 두 열만 채우며 내부 번호·암호문·내용해시·동기화 순번을 변경하지 않습니다. 다른 데이터가 Q/R에 있으면 덮어쓰지 않고 중단합니다. 서버 배포 확인 후 홈페이지를 배포합니다.

- 테스트용 성함, `01000000000` 같은 가상 연락처와 테스트 지역을 입력합니다. 실제 고객정보는 입력하지 않습니다.
- `견적서 예시 보기` 버튼은 블루베리·감나무와 SAMPLE 워터마크가 있는 양식 견본을 보여줍니다. 수신 업체와 연락처는 원본 이미지에서 가져오지 않습니다. PDF의 가격·수량은 예시입니다.
- 견본과 홈페이지 하단의 공급자는 사용자가 제공한 원본에 따라 **나무팜농원 / 213-94-24363 / 대표 오진웅**으로 표시합니다. 테스트 처리 안내는 `docs/test-privacy-notice.txt`에 보관합니다.
- 홈페이지에서 전송해 접수번호를 확인한 다음 PC의 **지금 동기화**, 휴대폰의 **새 요청 가져오기**로 같은 요청을 확인합니다. 테스트 요청의 견적서 발송·구매·배송은 진행하지 않습니다.

PDF 견본은 `public/quote-sample.pdf`, 화면 미리보기는 같은 PDF를 렌더한 `public/quote-sample.webp`입니다. 재생성하려면 ReportLab과 NanumGothic-Regular.ttf를 준비한 뒤 `python scripts/generate-quote-sample.py /path/to/NanumGothic-Regular.ttf`를 실행하고 PDF를 1190×1684 이미지로 렌더합니다. 고객 원본 이미지와 수신 업체의 개인정보는 공개 파일에 포함하지 않습니다.

보안 백업 `NamuFarm-Security-Backup.json`은 Git 추적 대상에서 제외합니다. 기존에 추적한 파일은 업데이트 시 PC에서도 지워질 수 있으므로 **git pull 전에 저장소 밖으로 복사해 보관**합니다. 과거 Git 커밋의 사본을 제거하는 이력 재작성은 이 변경에 포함하지 않습니다.

## 주요 소스

묘목 둘러보기는 2026-10-07에 정리한 186개 품목을 사용합니다. 원본 품목번호·이름을 유지하고, 초성 선택과 이름/초성 검색을 지원합니다. 품목 선택은 기존 견적 양식에 이름을 담으며, 가격·재고를 확정하지 않습니다. 카드를 한 번 누르면 담기고 다시 누르면 빠집니다. 해제하면 카드 문구도 `견적에 담기`로 돌아오며 양식으로 자동 이동하지 않습니다. 같은 이름을 직접 여러 줄 입력한 경우에도 카드에서 해제하면 해당 이름의 줄을 모두 뺍니다. 한 요청에는 최대 20종을 담으며 수량은 주 단위입니다. PC는 오른쪽 선택 목록, 모바일은 하단 수량 입력 버튼을 사용합니다. 잘못 뺀 품목은 수량·규격과 함께 되돌릴 수 있습니다.

`lib/species-data.json`은 사용 묘목 엑셀과 대조한 목록입니다. 보류 품목을 임의로 다시 추가하지 않습니다. `artwork`는 과거 자료와의 호환을 위해 유지하는 계열 값입니다. 현재 그림 선택은 이 값이나 12종 공통 아틀라스를 사용하지 않습니다. `lib/species-illustrations.json`에서 186개 품목번호에 개별 WebP 또는 명시적 보류 상태를 연결합니다. 같은 종의 잎·어린 수형을 공유할 수 있지만 품종별 열매·꽃·색을 임의로 확정하지 않습니다. 기존 명칭 확인 대상 37개는 `docs/species-identity-review.json`, 추가로 실제 잎 색·무늬·가시 및 유통명 확인이 필요한 항목은 `docs/species-visual-review.json`에 기록하며 중립적인 준비 상태로 표시합니다. 이미지가 빠지거나 로딩에 실패해도 다른 계열 그림으로 대체하지 않습니다. 그림 제작 원칙과 검증 범위는 [화면·일러스트 구현 기록](docs/responsive-catalog-review.md)을 참고하세요.

| 경로 | 역할 |
|---|---|
| `components/quote-home.tsx` | 소개·품목 선택·문의 입력·처리 안내·최종 확인 화면 |
| `components/species-browser.tsx`, `lib/species-catalog.ts` | 초성·이름 검색, 묘목 카드와 견적 품목 선택 |
| `lib/species-data.json` | 원본 번호와 이름을 유지한 186개 품목 |
| `lib/species-illustrations.ts`, `lib/species-illustrations.json`, `public/plants/v1/` | 품목번호별 그림·보류 상태·참고 출처와 최적화 WebP |
| `lib/species-illustration-index.json`, `scripts/sync-species-illustrations.mjs` | 원본 manifest에서 만드는 화면 전용 경로 색인. 그림 연결 변경 후 스크립트를 실행 |
| `lib/quote-form.ts` | 카드 선택·해제, 20종 제한·입력 오류의 화면 위치 연결 |
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

