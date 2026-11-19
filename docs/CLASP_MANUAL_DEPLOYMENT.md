# clasp 기존 프로젝트 연결 · 수동 배포와 코드 대조

2026-11-19. 6단계 연결 설정 구현. 계획/범위는 [CLASP_CONNECTION_PLAN.md](CLASP_CONNECTION_PLAN.md), 업무 위험 검토는 [P0_REVIEW_CHECKLIST.md](P0_REVIEW_CHECKLIST.md)를 따른다.

**연결 파일 구현과 운영 인증/배포는 별개다.** 이번에는 로컬 업로드 대상 17개를 확인했다. 원격 읽기 전용 `list-deployments`는 `No credentials found.`로 실패했다. 현재 CLI 인증이 없어 프로젝트↔배포 매핑, 원격 HEAD/배포 코드, 실행 주체/접근 설정·버전은 미확인이다. 업로드·버전 생성·배포는 실행하지 않았다.

## 고정 대상과 로컬 구성

| 항목 | 유지 값 |
| --- | --- |
| Git 브랜치 | `ssanai91-hash/NamuFarm`의 main만 사용, 커밋에 `[skip ci]` |
| Script ID | `1PDZifBX6iBw21fmOyzq-6aI2AqqW-Ib5zpeJz-fdhiu3TeLAtt_m_IaH` |
| 기존 deploymentId | `AKfycbzY7JKzXYmS9APLNFDt15k8dv09zb4EXKFHOOv_T1vpFmkr6TLwbqGznGLgpd2cIVgD` |
| 기존 `/exec` URL | `https://script.google.com/macros/s/AKfycbzY7JKzXYmS9APLNFDt15k8dv09zb4EXKFHOOv_T1vpFmkr6TLwbqGznGLgpd2cIVgD/exec` |
| `.clasp.json` | 저장소 루트, rootDir=`server/quote-requests`, scriptExtensions=`[".gs"]` |
| `.claspignore` | rootDir 기준 전체 제외 후 최상위 `.gs` 16개+기존 manifest만 허용 |
| manifest | 기존 `server/quote-requests/appsscript.json` 내용 그대로. 프로젝트/GCP·인증·scope·서비스 설정을 연결 때문에 바꾸지 않음 |

위 ID의 저장소 근거는 [기존 복구 기록](GOOGLE_SERVER_RECOVERY_20261009.md), Android `app/build.gradle.kts`, PC `desktop/namufarm_desktop/api.py`다. 운영 ID 매핑을 실제로 확인했다는 의미는 아니다.

`.claspignore`에 없는 새 `.gs` 파일은 업로드되지 않는다. 새 운영 소스를 추가/삭제하면 허용 목록을 함께 검토한다. tests/·문서·Android·PC 파일은 업로드 대상이 아니다. 현재 포함 목록은 계획 문서의 17개와 같으며 `QuoteCompaction.gs`·`ServerMaintenance.gs`도 포함한다.

## 도구와 인증

CLI는 **@google/clasp 3.4.1**로 고정한다. 공식 릴리스/동일 버전의 실제 `--help`를 확인했다. 확인 환경은 Node v24.22.3·npm 11.9.0이다. 이번 작업은 CLI를 저장소 밖에 설치했고 설치 lifecycle script를 끄기 위해 `--ignore-scripts`를 사용했다. NamuFarm의 패키지/의존성/lockfile을 추가·변경하거나 앱을 컴파일하지 않았다.

사용자 PC에서는 Node 20 이상과 동일 CLI를 준비한다. 아래 설치 예시는 필요할 때 수동 실행하며 저장소에서 `npm install`로 앱 의존성을 만들지 않는다.

```sh
npm install --global --ignore-scripts --no-audit --no-fund @google/clasp@3.4.1
clasp --version
```

명령은 특별한 설명이 없으면 **NamuFarm 저장소 루트**에서 실행한다. 현재 환경처럼 저장소 밖에 설치했다면 `clasp` 대신 그 설치의 `node_modules/.bin/clasp` 경로를 사용한다. 다른 버전으로 바꾸기 전에 `clone-script`, `pull`, `update-deployment`의 help를 확인한다. 특히 **3.4.1의 clone 버전은 positional 인자**이며 `clone-script <scriptId> <versionNumber>`다.

현재 작업에서는 로그인을 시작하거나 인증 파일/토큰/사용자 계정을 찾아 읽지 않았다. 인증이 필요할 때 소유자가 다음 명령으로 기존 프로젝트에 접근 가능한 Google 계정을 직접 승인한다. 인증 정보를 채팅/저장소로 보내지 않는다.

```sh
clasp --user namufarm-owner login
```

이 로그인은 CLI용 로컬 권한이며 기존 웹 앱의 기기 인증을 바꾸는 작업이 아니다. Apps Script API 사용이 비활성화돼 있으면 소유자가 해당 계정의 기존 설정에서 필요한 이용 준비를 확인한다. 새 GCP 프로젝트/서비스 계정/OAuth 클라이언트를 만들어 우회하거나 기존 manifest scope를 바꾸지 않는다. 권한이 없으면 미완료로 기록한다.

기본 인증 저장은 사용자 프로필의 `.clasprc.json`이다. 별도 client secret이 필요하면 저장소 밖에 보관한다. `.gitignore`는 `.clasprc*.json`과 백업, `client_secret*.json`, `clasp-credentials*.json`, `clasp-oauth*.json`, 루트 `.clasp-private/`를 제외한다. `.clasp.json`과 `.claspignore`는 추적 대상이다. ignore 규칙이 비밀 파일을 안전하게 백업하거나 접근 권한을 제한해 주는 것은 아니다.

## 먼저 할 읽기 전용 확인

### 로컬 파일 선택

인증/네트워크 없이 수행하는 파일 선택 확인이다. 자동시험이나 컴파일 명령이 아니다.

```sh
clasp --project .clasp.json --ignore .claspignore show-file-status --json
```

`filesToPush`가 `server/quote-requests/` 아래 의도한 17개인지 확인한다. `untrackedFiles`로 표시된 문서/tests는 clasp 업로드 제외 목록이며 Git 미추적 상태를 뜻하지 않는다. `--allow-symlinks`나 다른 `--ignore`/`--project` 경로로 대상을 우회하지 않는다. 예시는 루트 두 파일을 명시해 셸의 clasp_config_project/clasp_config_ignore 재정의에 의해 다른 대상이 선택되지 않도록 했다.

2026-11-19 이 로컬 목록은 실제 CLI로 확인했다. 신규 `.gs`/manifest 누락이나 tests/다른 앱 포함을 발견하면 업로드하지 말고 설정부터 바로잡는다.

### 기존 배포 식별과 원격 코드 수신

인증 준비 후 아래 **기존 프로젝트만** 조회한다. 계정 전체 스크립트 목록을 탐색할 필요는 없다.

```sh
clasp --project .clasp.json --user namufarm-owner list-deployments
```

결과에서 고정 deploymentId의 **현재 배포 버전 V**를 기록한다. Apps Script의 배포 관리 또는 공식 읽기 API에서 URL·실행 주체/접근 설정도 확인한다. ID 매핑 불일치/권한 오류는 새 ID 생성으로 해결하지 않는다.

HEAD(편집/트리거 코드)와 운영 배포 V를 **각각 저장소 밖 비공개 폴더**에 받는다. main 체크아웃에서 `pull`해 로컬 원문을 덮어쓰지 않는다. 아래는 Bash 예시이며 실제 V는 위에서 읽은 양의 정수로 채운다. 이 단계는 Google 코드를 읽어 로컬 사본을 만들 뿐 새 원격 프로젝트를 만들지 않는다.

```sh
NAMUFARM_SCRIPT_ID='1PDZifBX6iBw21fmOyzq-6aI2AqqW-Ib5zpeJz-fdhiu3TeLAtt_m_IaH'
NAMUFARM_OLD_VERSION='<확인한 배포 버전 V>'
NAMUFARM_REVIEW_DIR="$(mktemp -d)"
chmod 700 "$NAMUFARM_REVIEW_DIR"
mkdir "$NAMUFARM_REVIEW_DIR/head" "$NAMUFARM_REVIEW_DIR/deployed"
cd "$NAMUFARM_REVIEW_DIR/head"
clasp --user namufarm-owner clone-script "$NAMUFARM_SCRIPT_ID"
cd "$NAMUFARM_REVIEW_DIR/deployed"
clasp --user namufarm-owner clone-script "$NAMUFARM_SCRIPT_ID" "$NAMUFARM_OLD_VERSION"
```

위 작업 후에는 명시적으로 원래 NamuFarm 루트로 돌아온다. 임시 폴더의 `.clasp.json`에서 push/배포하지 않는다. 기본 clone/pull의 결과는 HEAD이며 운영 `/exec` 버전과 같다고 가정할 수 없다. clone 사본은 `.js`로 저장될 수도 있으므로 `.gs`와 원격 SERVER_JS 파일의 이름/형식을 맞춰 비교한다.

동일한 내용은 승인된 공식 API 클라이언트의 다음 **GET**으로도 확인할 수 있다. 이 작업을 위해 access token을 콘솔/채팅에 출력하거나 Script Properties·고객 장부를 읽지 않는다.

```text
GET https://script.googleapis.com/v1/projects/<scriptId>/deployments/<기존 deploymentId>
GET https://script.googleapis.com/v1/projects/<scriptId>/content?versionNumber=<V>
GET https://script.googleapis.com/v1/projects/<scriptId>/content
```

## 코드 대조와 증거 기록

고정 main SHA의 서버 파일과 원격 HEAD/배포 V 각각을 비교한다.

- 파일별 상대 이름·원격 type·크기·SHA-256을 비교한다. `.gs/.js`와 SERVER_JS, `appsscript.json`과 JSON manifest를 대응시킨다. 원문 해시와 줄바꿈만 LF로 통일한 해시를 구분한다.
- manifest는 원문에 더해 JSON 객체 키 순서만 정렬한 비교를 남긴다. 배열 순서·설정값을 지워 차이를 숨기지 않는다. 저장소에 없는 원격 webapp 설정·scope·서비스가 있으면 기존 실행 주체/접근 설정을 보존할 방법을 먼저 검토한다. `--force`로 원격 manifest를 밀어버리지 않는다.
- 운영이 합친 Code.gs이면 함수명 개수만으로 일치라고 쓰지 않는다. 함수 본문·전역 초기화·중복 정의·운영 전용 코드와 저장소 분리 파일의 대응을 읽고 차이를 정리한다. 미해결 원격 파일/설정은 조용히 없애지 않는다.
- 원문/비밀 상수가 포함될 수 있는 전체 diff는 비공개 사본 안에서만 검토한다. 공유 기록에는 파일/함수별 차이 요약·해시와 결론만 남긴다. 소스 백업·대조 기록에 OAuth 토큰이나 Script Properties 값을 넣지 않는다.
- API v5/기능 플래그가 보인다는 것만으로 저장소 코드와 운영 버전이 같다고 판단하지 않는다. 소스 해시는 트리거 설치·운영 속성·장부 데이터의 일치 증거도 아니다.

기록 양식:

| 확인일/작업자 | Git main SHA | Script ID/기존 배포 ID | 배포 V/N | 비교 대상 | 파일별 해시/차이 | 결론 |
| --- | --- | --- | --- | --- | --- | --- |
| ... | ... | ... | ... | HEAD / 배포 V / 신규 N | 비밀값 없는 요약 | 일치 / 차이 있음 / 미확인 |

이번 작업의 원격 대조 결과는 **미확인(현재 CLI 인증 없음)**이다. 형식/설정 구현 완료와 운영 연결 성공을 구분해 현황에 남긴다.

## 운영 반영 요청 시에만 실행할 쓰기 절차

**아래 push·버전 생성·배포 갱신은 이번 연결 구현에서 실행하지 않았다.** 실제 운영 반영 요청이 있을 때 적용한다. main push 자동 배포나 watch를 추가하지 않는다.

1. main 작업 트리가 깨끗한지 확인하고 대상 SHA를 고정한다. 필요한 P0 검토와 Android/PC 준비 상태를 기록한다. 특히 앞선 4단계의 서버/클라이언트 적용과 사용자 확인을 혼동하지 않는다.
2. 위 원격 HEAD·배포 V 사본/설정과 코드 대조가 준비되고 미해결 차이가 없어야 한다. 같은 프로젝트를 편집하는 작업이 겹치지 않는 시간에 진행한다. 업로드 자체가 트리거용 HEAD를 바꾸므로 운영 영향도 함께 다룬다. 트리거를 임의 삭제/교체하거나 원문/카운터를 초기화하지 않는다.
3. 원래 NamuFarm 루트에서 다시 파일 선택/기존 ID를 확인한다. 별도 프로젝트/ignore 설정으로 바뀌지 않았는지 확인한 다음, 요청된 범위에서 수동 push한다.

```sh
git branch --show-current
git status --short
NAMUFARM_TARGET_SHA="$(git rev-parse HEAD)"
clasp --project .clasp.json --ignore .claspignore show-file-status --json
clasp --project .clasp.json --ignore .claspignore --user namufarm-owner push
```

4. push 뒤 HEAD를 새 비공개 폴더로 다시 읽어 대상 SHA와 파일별 해시/manifest를 비교한다. 변경이 겹쳤거나 일치하지 않으면 배포하지 않는다. 일치한 HEAD로 불변 버전 N을 만들고 **N의 코드도 다시 받아** 일치하는지 확인한다.

```sh
clasp --project .clasp.json --user namufarm-owner create-version "NamuFarm ${NAMUFARM_TARGET_SHA}"
```

5. 반환된 N을 확인한 뒤 고정된 기존 배포만 갱신한다. 별도 새 배포를 만들지 않는다.

```sh
NAMUFARM_DEPLOYMENT_ID='AKfycbzY7JKzXYmS9APLNFDt15k8dv09zb4EXKFHOOv_T1vpFmkr6TLwbqGznGLgpd2cIVgD'
NAMUFARM_NEW_VERSION='<확인한 새 버전 N>'
clasp --project .clasp.json --user namufarm-owner update-deployment "$NAMUFARM_DEPLOYMENT_ID" --versionNumber "$NAMUFARM_NEW_VERSION" --description "NamuFarm ${NAMUFARM_TARGET_SHA}"
```

6. 배포 정보를 다시 읽어 **기존 ID·URL 유지, N, 실행 주체/접근 설정 유지**를 확인한다. Git SHA↔N↔deploymentId↔파일 해시·확인일을 현황에 기록한다. 메타 확인을 실제 주문/발송 동작 시험으로 표현하지 않는다. 사용자가 기기/업무 동작을 확인한다. 물리 정리 활성화는 이 배포와 별개다.

`create-script`, 배포 ID 없는 `create-deployment`, `delete-script/delete-deployment`, `push --watch/--force`, `run-function`의 초기화/정리/장부 전환 함수는 이 절차에 필요하지 않다. 자동 호출하지 않는다.

문제 시 같은 배포 ID를 이전 V로 지정하는 수동 복구를 검토한다. 웹 배포의 버전만 되돌려도 HEAD/설치 트리거 코드·이미 변경된 업무 자료는 되돌아가지 않는다. HEAD 복원·구/신 클라이언트 호환과 데이터 영향을 따로 판단하고, 주문 연결·접수 순번·판매번호 카운터·고객 원문을 과거 장부로 무조건 덮어쓰지 않는다.

## 이번 구현의 확인 범위

- 완료: 기존 ID의 로컬 연결 설정, 17개 업로드 허용 목록, 인증/비공개 자료 git 제외 규칙, CLI 3.4.1 버전/help/로컬 파일 선택 확인, 이 수동 절차와 현황 기록.
- 미완료: 소유자 CLI 인증, 운영 프로젝트↔배포 매핑/버전·HEAD/배포 원문 대조, 실제 업로드·버전 생성·운영 배포, 사용자 업무 동작 확인.
- 미실행: 컴파일·자동시험·Actions·실제 주문/문자/메일 시험·고객 시트/속성 조회·쓰기·운영 함수/물리 정리·홈페이지 게시. CLI 도움말/로컬 파일 목록과 git ignore/diff 확인은 실행했다.
- 재배포: 이 설정/문서 변경 자체는 불필요. 앞선 4단계 서버 코드의 기존 ID 재배포·클라이언트 업데이트/사용자 확인은 여전히 남는다. 물리 정리는 기본 OFF 기록 그대로다.

## 공식 근거

- [clasp 3.4.1 릴리스](https://github.com/google/clasp/releases/tag/clasp-v3.4.1), [고정 버전 README](https://github.com/google/clasp/blob/clasp-v3.4.1/README.md). 설치된 동일 버전 help와 함께 확인했다.
- [projects.updateContent](https://developers.google.com/apps-script/api/reference/rest/v1/projects/updateContent): 파일 전체 대체·HEAD와 트리거 영향.
- [projects.getContent](https://developers.google.com/apps-script/api/reference/rest/v1/projects/getContent): HEAD/특정 버전 소스 읽기.
- [projects.deployments.get](https://developers.google.com/apps-script/api/reference/rest/v1/projects.deployments/get), [update](https://developers.google.com/apps-script/api/reference/rest/v1/projects.deployments/update): 고정 배포 조회·버전 갱신.
