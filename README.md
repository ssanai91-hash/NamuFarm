# NamuFarm · 나무팜

묘목 견적 접수와 판매관리를 위한 홈페이지·PC 프로그램·Android 앱·Google Apps Script 서버를 **한 저장소의 `main` 브랜치**에서 관리합니다.

## 한 프로젝트 폴더에서 작업

| 경로 | 대상 | 안내 |
| --- | --- | --- |
| `app/` | Android 나무팜 앱 | [Android 빌드·서명](docs/ANDROID_INITIAL_BUILD.md) |
| `desktop/` | 나피 PC 프로그램 | [PC 실행 안내](desktop/README.md) |
| `website/` | 홈페이지 | [홈페이지 실행 안내](website/README.md) |
| `server/quote-requests/` | Google Apps Script·Google Sheets 연동 | [서버 접수 안내](docs/WEB_QUOTE_REQUESTS.md) |
| `scripts/` | 서명·관리 도구 | Android 서명: `sign_initial_apk.py` |

Android Studio는 이 프로젝트의 루트 폴더를 엽니다. Gradle은 `:app`만 빌드하므로 `desktop/`, `website/`, `server/`를 Android 소스로 컴파일하지 않습니다. PC와 홈페이지는 각각 해당 하위 폴더에서 작업합니다. 작업별로 브랜치나 로컬 프로젝트 폴더를 바꿀 필요가 없습니다.

이미 main을 사용하는 로컬 폴더에서는 `git pull --ff-only`로 갱신합니다. 초기버전 브랜치에서 이번 통합 커밋으로 이동하는 경우 `git fetch origin` 후 `git switch main`, `git pull --ff-only origin main`을 사용합니다. 저장하지 않은 변경이나 로컬 전용 파일이 있으면 보존한 뒤 전환합니다.

## Android 통합 기준

- 2026-10-24 사용자 요청에 따라 `restore/android-initial-20260927`의 최신 `ad0074f` Android 소스를 main으로 통합했습니다. 이전 브랜치는 기록용으로 남깁니다.
- 현재 앱 패키지는 **`com.example.namufarm.original`**, 표시 이름은 **나무팜**, 소스 버전은 **1.1.16(19)**입니다. 문의 답변 3안·문자/메일 전송 전 자동 문구 확인 변경까지 포함합니다.
- 기존 초기 앱의 화면·공식견적서·농원/묘목/매매 기능과 `NamuFarm.db` v13, `NamuFarmQuotes.db` v1, `NamuFarmInquiries.db` v1을 유지합니다.
- 기존 앱 업데이트에는 같은 초기 앱 인증서가 필요합니다. 과거 main용 다른 인증서와 혼동하지 않습니다. 서명키·비밀번호·로컬 DB는 Git에 올리지 않습니다.
- **컴파일·서명·실기기 검증은 이번 통합에서 수행하지 않았습니다.** 로컬 컴파일과 실제 화면·발송 확인은 사용자가 진행합니다. 과거 Android CI/APK 작업은 중지했고 GitHub Actions를 요청하지 않습니다.

## 운영 원칙

견적 접수 → 견적 작성·전달 → **입금확인(=구매확정)** → 배송·송장 관리 → 배송완료를 기본 업무 흐름으로 사용합니다. 구매확정과 입금확인을 별도 상태로 나누지 않습니다.

기기에서는 로컬 자료를 먼저 보관하고 명시적으로 서버와 동기화합니다. 고객 원문·기존 견적/주문·미전송 초안을 보존하며 서버 확인 전 완료로 처리하지 않습니다. 발송 결과가 불명확하면 자동 재발송하지 않습니다.

최신 운영 상태와 과거 변경 기록은 [프로젝트 현황](NAMUFARM_PROJECT_STATUS.md), 작업 지침은 [AGENTS.md](AGENTS.md)를 참고하세요. 이전 문서의 별도 Android 브랜치 지침은 통합 전 이력이며 현재 기준은 main입니다.

**GitHub 코드 반영과 실제 홈페이지 게시·Apps Script 재배포는 별도 작업입니다.** 이번 통합은 홈페이지·나피·서버 소스와 운영 배포를 변경하지 않습니다.
