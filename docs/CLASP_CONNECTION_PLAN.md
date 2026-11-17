# clasp 연결 · 6단계 착수 전 계획

2026-11-18. 기준 main `bb1201e026facb7fbe6e1fedfe7b9b20aca7a44f`.

**이번에는 계획 문서만 작성했다.** 사용자 지정 순서인 “착수 전 계획 보고”에 따라 실제 연결 파일 추가/인증/운영 프로젝트 조회/업로드/배포는 구현 승인 뒤 진행한다. main 푸시 자동 배포는 제외한다. 계획 문서의 커밋은 clasp 연결 완료나 운영 반영 완료가 아니다.

## 확인한 저장소 현황

| 항목 | 현재 근거/판단 |
| --- | --- |
| 기존 Script ID | `1PDZifBX6iBw21fmOyzq-6aI2AqqW-Ib5zpeJz-fdhiu3TeLAtt_m_IaH` — [복구 기록](GOOGLE_SERVER_RECOVERY_20261009.md)의 기존 프로젝트 URL |
| 기존 배포 ID | `AKfycbzY7JKzXYmS9APLNFDt15k8dv09zb4EXKFHOOv_T1vpFmkr6TLwbqGznGLgpd2cIVgD` — Android `app/build.gradle.kts:WEB_REQUEST_SCRIPT_URL`, PC `desktop/namufarm_desktop/api.py:DEFAULT_URL`와 복구 기록 일치 |
| 유지 URL | 위 배포 ID의 `https://script.google.com/macros/s/<배포ID>/exec` 그대로. 도메인·기기 인증·서명·DB 변경 없음 |
| 서버 파일 | `server/quote-requests/` 최상위 `.gs` 16개 + `appsscript.json` 1개. 아래 목록 참조 |
| manifest | Asia/Seoul·V8·기존 7개 oauthScopes·Drive v3 고급 서비스가 저장소에 있음. 변경하지 않음 |
| clasp 구성 | 추적된 `.clasp.json`·`.claspignore` 없음. 운영 자동 배포 workflow 없음 |
| 운영 상태 | 위 ID는 저장소의 기존 운영 식별자. 실제 프로젝트↔배포 매핑/현재 버전/원격 파일/권한/manifest/트리거는 이번에 조회하지 않음 |
| 첫 업로드 위험 | 복구 문서에는 과거 운영 코드가 합친 `Code.gs`였다고 기록됨. 지금도 같은 구성이라고 가정하거나 저장소 16개 파일과 이름만 비교해 동일하다고 판단하지 않음 |

Script ID와 배포 ID는 서로 다르다. 배포 URL의 ID를 `.clasp.json.scriptId`에 넣지 않는다. 실제 접근 시 기존 프로젝트의 배포 목록에 위 배포 ID가 있고 URL도 일치하는지 읽기 전용으로 확인한다. 불일치하면 추측한 새 프로젝트·배포를 만들지 않고 차이를 보고한다.

## 승인 후 변경할 최소 파일

| 파일 | 내용 |
| --- | --- |
| 루트 `.clasp.json` | 위 기존 scriptId, `rootDir: "server/quote-requests"`, `.gs` 확장자를 유지할 설정. 새 GCP projectId를 지정/변경하지 않음 |
| 루트 `.claspignore` | rootDir 기준 전체 제외 후 아래 17개 파일을 명시적으로 허용. tests/·문서·앱/PC·비밀 파일 제외 |
| 루트 `.gitignore` | clasp OAuth 인증 파일·클라이언트 비밀값·원격 원본/비공개 대조 산출물이 커밋되지 않도록 필요한 항목만 추가. 실제 인증 파일은 저장소 밖에 보관 |
| `docs/CLASP_MANUAL_DEPLOYMENT.md` | 확인한 CLI 버전, 최초 연결/원격 대조, 기존 ID 수동 갱신, 버전/해시 기록·복구 절차 |
| `NAMUFARM_PROJECT_STATUS.md`, 이 계획 | 연결 파일 구현 여부·실제 인증/조회/업로드/배포 여부·미완료 사항을 따로 기록 |

기존 `appsscript.json`, 실행 코드, Android/나피 패키지·버전, workflow는 연결 설정만을 위해 바꾸지 않는다. 원격과 저장소 manifest/코드가 다르면 먼저 차이를 보고하고 필요한 최소 수정 범위를 정한다. 확인되지 않은 원격 설정을 무조건 덮어쓰지 않는다.

허용할 서버 파일(현재 main 기준):

```text
appsscript.json
AnnualLedgers.gs
Code.gs
ConfirmedQuotes.gs
ContentLibrary.gs
CustomerInquiries.gs
FarmManagement.gs
InventoryManagement.gs
MonthlyStorage.gs
QuoteCompaction.gs
RecoverySetup.gs
SecureCommerce.gs
ServerMaintenance.gs
SoldNumbers.gs
SpeciesManagement.gs
TwoBookCutover.gs
TwoTabQuotes.gs
```

clasp 공식 문서상 `.claspignore` 패턴은 rootDir 기준이고 gitignore와 디렉터리 매칭 방식이 다르다. 구현 때 `clasp show-file-status`의 대상이 위 목록과 정확히 같은지 **로컬 목록으로만** 확인한다. 향후 `.gs`를 추가하면 허용 목록도 함께 검토한다. 설치/명령어는 확인한 clasp 3.x 버전을 고정해 문서에 기록하고, 구 2.x의 alias가 같다고 가정하지 않는다. 이번 계획에서는 설치/명령 실행을 하지 않았다.

## 연결 구현과 실제 운영 쓰기의 경계

1. **연결 파일 구현:** 승인된 기존 ID/경로·제외 규칙과 수동 절차를 main에 `[skip ci]`로 커밋한다. GitHub 비밀값/Actions/OAuth 키를 새로 만들지 않는다. 로그인 세션·비밀 파일을 탐색하거나 인증값을 로그에 출력하지 않는다.
2. **인증/원격 읽기:** 연결 구현 승인 후 필요한 읽기 전용 범위에서 기존 프로젝트 소유자 계정으로 clasp를 사용한다. 필요한 Apps Script API 이용/승인 상태는 확인하되 웹 앱의 인증·실행 주체·접근 설정이나 GCP 프로젝트를 임의 교체하지 않는다. 인증은 로컬 CLI 접근 권한이며 운영 앱의 기기 인증과 구분한다. 권한/계정/API 설정이 준비되지 않으면 연결 파일 구현과 접근 미완료를 나눠 보고한다.
3. **HEAD/배포 코드 대조:** main 체크아웃을 덮어쓰는 pull 대신 저장소 밖 비공개 작업 폴더에 각각 받는다. 업무 시트/고객자료/Script Properties 원문을 조회·수정할 필요는 없다. 원격 코드에 비밀 상수가 있을 가능성이 있으므로 원문/diff 전체를 공개 로그·커밋에 싣지 않는다.
4. **운영 업로드/배포:** 실제 `clasp push`, 버전 생성, 기존 배포 갱신은 운영 쓰기다. **연결 설정 구현 승인만으로 실행하지 않는다.** 사용자가 운영 반영을 요청한 범위에서 아래 수동 절차를 따른다. 여러 단계마다 반복 승인을 요구하는 것이 아니라 연결 준비와 운영 반영의 승인 범위를 분리하는 것이다.

중요한 이유: `push`는 개별 파일만 수정하는 동작이 아니라 원격 프로젝트 파일 전체를 대체한다. 또한 HEAD 코드는 트리거 실행에 사용되므로 `/exec`의 버전 갱신 전이라도 기존 백그라운드 작업에 영향을 줄 수 있다. “업로드만 했으니 운영 영향 없음”으로 보고하지 않는다. 이 동작은 Google `projects.updateContent` 공식 명세에 명시돼 있다.

## 운영 배포 버전과 저장소 코드를 대조하는 방법

1. 고정 Script ID + 고정 배포 ID로 배포 정보를 읽어 **현재 versionNumber=V**, URL, 실행 주체/접근 설정을 기록한다. Script HEAD와 운영 배포 V를 구분한다.
2. `projects.getContent`의 versionNumber=V로 **운영 배포 코드**를 받고, versionNumber를 생략한 별도 읽기로 **HEAD 코드**를 받는다. 기본 pull 결과가 운영 배포 코드라고 가정하지 않는다.
3. 저장소 대상 main SHA의 서버 17개 파일과 각각 비교한다. 원격 Apps Script 파일의 name/type과 `.gs`/manifest 매핑을 맞추고, 소스는 줄바꿈만 LF로 통일한 SHA-256과 원문 해시를 구분해 기록한다. manifest는 JSON 키 순서만 정렬한 비교도 병기하며 배열/설정값을 임의 삭제/재배열하지 않는다.
4. 파일별 이름·형식·해시, 빠진/추가 파일, 달라진 함수·manifest 설정을 표로 보고한다. 합친 Code.gs는 분리 파일들과 함수명만 같다고 동등 판정하지 않는다. 중복 함수·초기화 코드·운영 전용 코드를 읽어 차이를 해결하기 전에는 첫 push를 보류한다. 원격 전용 파일도 검토 없이 제거하지 않는다.
5. `Git SHA ↔ Apps Script versionNumber ↔ 기존 deploymentId ↔ 파일별 해시/확인일`을 배포 기록으로 남긴다. API v5 또는 기능 플래그가 맞는 것만으로 정확한 배포 커밋 일치를 주장하지 않는다. 코드 해시는 Script Properties·설치 트리거·장부 데이터의 일치 증거도 아니다.

읽기 API 형식(설계 예시이며 이번에 호출하지 않음):

```text
GET https://script.googleapis.com/v1/projects/<scriptId>/deployments/<기존 deploymentId>
GET https://script.googleapis.com/v1/projects/<scriptId>/content?versionNumber=<V>
GET https://script.googleapis.com/v1/projects/<scriptId>/content
```

현재 연결 도구에는 Apps Script 프로젝트/배포를 직접 관리하는 전용 API 도구가 확인되지 않았다. 필요 시 소유자 인증 clasp/공식 API로 수행하며, 그 전까지 운영 코드/배포 대조는 미완료로 표시한다. 이번에는 인증 유무를 탐색하거나 운영 API를 호출하지 않았다.

## 승인된 운영 반영 요청 때의 수동 갱신 순서

아래는 **나중의 수동 절차 설계**다. 지금 바로 실행하는 명령이 아니다. 실제 명령·CLI 옵션은 구현 때 고정 버전 help와 공식 문서로 대조한다.

1. main 대상 SHA를 고정하고 관련 P0 자가 점검, Android/PC 준비 상태를 확인한다. 기존 HEAD와 배포 V의 비공개 소스 보관 및 기존 manifest·실행 주체/접근 설정을 확인한다. 속성·카운터·주문 연결을 초기화하지 않는다.
2. 같은 프로젝트를 편집하는 작업이 겹치지 않는 시간에 업로드한다. 기존 설치 트리거가 HEAD를 사용한다는 영향까지 포함하며 트리거를 임의 삭제/교체하지 않는다. 코드/설정 차이 미해결 또는 동시 변경을 발견하면 덮어쓰지 않는다.
3. 로컬 `clasp show-file-status` 대상 목록을 확인한 뒤 요청된 범위에서 `clasp push`를 수동 실행한다. `--force`, `--watch`를 기본으로 쓰지 않는다. 전체 프로젝트 대체이므로 없는 원격 파일을 무심코 제거하지 않는다.
4. HEAD를 다시 받아 대상 SHA의 파일 해시/manifest와 비교한 뒤 `clasp create-version "NamuFarm <Git SHA>"`으로 불변 버전 N을 만든다. N의 코드도 다시 읽어 일치 여부를 확인한다. 중간에 다른 편집이 들어온 버전은 활성화하지 않는다.
5. `clasp update-deployment <기존 deploymentId> --versionNumber <N>`으로 **같은 배포를 갱신**한다. 배포 ID 없는 create-deployment, 새 프로젝트 생성, 기존 배포 삭제를 사용하지 않는다. 웹 편집기로 할 때도 배포 관리의 기존 항목을 수정한다.
6. 배포 정보를 다시 읽어 기존 ID/URL·버전 N·실행 주체/접근 설정을 확인한다. N의 코드 해시와 연결해 현황에 기록한다. 운영 기능 동작은 사용자가 확인한다. 자동 주문/문자/메일 시험이나 물리 정리 활성화를 끼워 넣지 않는다.

문제 시 이전 V를 같은 배포 ID로 다시 지정하는 수동 복구안을 기록한다. 이 동작은 웹 배포 버전만 바꾸며 **HEAD/트리거 코드나 이미 변경된 업무 데이터까지 복원하지 않는다.** 무조건 전체 장부/속성·연결·카운터를 과거 값으로 덮어쓰는 복구를 하지 않는다. HEAD 복원 필요 여부와 구·신 클라이언트 호환은 별도 판단한다.

## 범위·위험·현재 판정

- 예상 작업량: 연결 파일/문서 **소**, 최초 원격 대조와 합친 파일/manifest 차이 조정은 **중**이 될 수 있다. 실제 운영 차이 미확인이다.
- 프로젝트 main 단일 관리·임의 브랜치 금지·최소 수정과 충돌 없음. 원격 읽기 사본은 git 브랜치/새 Apps Script 프로젝트가 아니라 비공개 비교 자료다. 차이가 있으면 추가 수정 범위를 드러내며 무조건 저장소 전체로 덮어쓰지 않는다.
- 기존 ID가 기록에는 있지만 운영 매핑·권한·버전은 미확인이다. OAuth/API 이용 준비, 원격 전용 코드 유실, manifest 인증/접근 설정 차이, HEAD 트리거 영향과 동시 편집이 남은 위험이다.
- 이번 변경은 계획/현황 문서 2개뿐이다. SYNC/PAY/EXP/INTAKE 모두 실행 코드 영향 없음. 근거는 이번 diff 범위이며 기존 기능 전수 감사/운영 합격이 아니다. 4단계 물리 정리는 기본 OFF·미실행 기록 그대로다.
- **이번 문서 변경은 재배포 불필요**, 홈페이지 게시 없음. 앞선 4단계 코드의 기존 ID 재배포·클라이언트 갱신/사용자 확인은 여전히 미완료다. 컴파일·자동시험·Actions·실제 고객/문자/메일·운영 함수/정리 실행은 하지 않았다.
- 다음 승인 대상: 이 계획에 따른 `.clasp.json`·`.claspignore`·인증 파일 제외 규칙과 수동 배포/대조 문서 구현, 가능한 범위의 원격 식별자/코드 읽기 전용 대조. **실제 운영 업로드·버전 생성·배포는 별도 요청 범위**다.

## 공식 근거

2026-11-18에 명령/동작 확인만 했다. 아래 공식 자료 열람은 NamuFarm 운영 계정/서버 접속이 아니다.

- [google/clasp 공식 README](https://github.com/google/clasp): rootDir/ignore, 명령 이름, 동일 배포 갱신. 이 계획의 clasp CLI는 3.x 기준.
- [projects.updateContent](https://developers.google.com/apps-script/api/reference/rest/v1/projects/updateContent): 전체 파일 대체, HEAD와 트리거 실행 영향.
- [projects.getContent](https://developers.google.com/apps-script/api/reference/rest/v1/projects/getContent): HEAD와 특정 versionNumber 소스 조회.
- [projects.deployments.get](https://developers.google.com/apps-script/api/reference/rest/v1/projects.deployments/get): 기존 프로젝트/배포의 읽기 전용 조회.
- [projects.deployments.update](https://developers.google.com/apps-script/api/reference/rest/v1/projects.deployments/update): 기존 deploymentId의 versionNumber 갱신.
