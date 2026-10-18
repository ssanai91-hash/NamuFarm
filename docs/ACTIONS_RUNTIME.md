# GitHub Actions 실행시간 절감 (2026-10-18)

## 확인한 실행 기록

기준 main: `7b143ad2a31c2529eaf6574bba334ab9167afba3`.
최근 Actions 실행 100건과 main 커밋 35건을 조회하고, 최신 main 커밋 10개의 변경 파일을 대조했다.
이 10개는 홈페이지 단독 9개와 서버 단독 1개였다.
홈페이지 단독 커밋마다 Android CI와 Desktop manager CI가 함께 시작됐다.

예: 홈페이지 카탈로그만 바뀐 `5e899b7d`의 Android 실행
`39131551708`은 Android 단위 테스트/빌드까지 진행하다 실패했고,
작업 시간은 약 4분 8초였다. 서버 회귀 테스트는 기존 Android/PC 워크플로에 중복돼 있었다.
실패한 앱 테스트의 원인과 저장공간 정리는 이 변경의 범위가 아니다.

새 경로 조건을 같은 10개 커밋에 적용하면 Android 검증은 10회에서 0회,
PC 검증은 10회에서 서버 변경 1회로 줄어든다.
서버·홈페이지의 가벼운 검증은 각각의 관련 변경에 유지한다.
이는 과거 커밋 경로에 대한 재현 결과이며, 실제 계정 청구 분 절감률은 아니다.

## 실행 조건

push와 main 대상 pull_request에 같은 경로 조건을 적용한다.
한 push에 여러 영역이 바뀌면 필요한 검증을 모두 시작한다.

| 변경 영역 | 실행하는 검증 |
| --- | --- |
| Android 소스·리소스·테스트, Gradle 설정·wrapper, Android 서명 준비 스크립트 | Android 전체 단위 테스트와 debug 빌드, Android→Python 백업 호환성, PDF 검증 |
| PC 소스·테스트·의존성·리소스 | Linux 전체 PC 테스트 → 성공 시 Windows 전체 PC 테스트 |
| PC `api.py`, `crypto.py`, `errors.py`, `tests/check_android_backup.py` | PC 검증 + Android 검증 (빌드 서버 URL/실제 백업 호환성 의존성) |
| `server/**` | 서버·홈페이지 회귀 검증 + 실제 Apps Script fixture를 쓰는 PC 통합 테스트 |
| `website/**` | Node 24 서버·홈페이지 회귀 검증; JDK·Gradle·Qt 설치 없음 |
| 루트 README, `docs/**`, PC/홈페이지 Markdown 문서만 수정 | 앱 자동 검증 없음 |
| 특정 CI YAML | 해당 CI 자체 검증 + CI 설정 검사 |
| `.github/tests/**` | CI 설정 검사 |

`Server and website regression CI`는 기존 서버 전체 테스트와 홈페이지 암호화·서비스 호환성
테스트를 한 곳에서 실행한다. 의존성 설치 없이 실행 가능한 접수번호·응답 처리·카탈로그
테스트도 포함한다. 홈페이지 전체 프레임워크 빌드나 배포를 수행하는 워크플로는 아니다.
서버 테스트 실패 시에도 홈페이지 검증 결과를 얻을 수 있다.

PC 테스트는 실제 `server/quote-requests/tests/fixture.cjs`를 사용하는
`desktop/tests/server_bridge.cjs`에 의존하므로 서버 경로를 PC 실행 조건에 남겼다.
PC의 Node 설정은 두 OS 모두 보존한다. Linux/Windows 테스트 목록은 줄이지 않는다.
PC용 pip 다운로드 캐시를 사용하고, PyInstaller는 EXE 생성 요청 때만 설치한다.

## 연속 커밋과 중복 실행

- Android CI와 PC CI의 자동 push는 첫 30초 동안 설정·설치를 시작하지 않는다.
  같은 검증에 해당하는 후속 push가 오면 기존 concurrency로 이전 실행을 취소한다.
  이후 빌드 중에도 새 관련 커밋은 이전 자동 실행을 취소한다.
  30초 대기도 runner 시간에 포함되므로 가벼운 서버·홈페이지 검증에는 추가하지 않았다.
- 홈페이지만 바꾼 후속 push는 진행 중인 Android/PC 검증을 취소하지 않는다.
  서로 다른 영역의 검증이 빠지는 것을 방지한다.
- 서버→홈페이지 연속 push에서는 protocol 실행이 바뀔 수 있다.
  따라서 protocol은 매번 서버와 홈페이지 두 테스트 묶음을 모두 실행한다.
- PC push 대상은 main으로 통일했다. `fix/local-first-integrity-review`는 main 대상 PR 또는
  수동 실행으로 검증해 동일 커밋의 branch push/PR 이중 실행을 피한다.
- 수동 실행은 run ID가 포함된 별도 concurrency 그룹을 사용한다.
  자동 push가 수동 APK/EXE 검증을 취소하지 않는다.

## APK·EXE 수동 생성 유지

- `Android APK Build`: debug/release 선택 및 기존 영구 서명 유지.
  main push의 마지막 커밋 메시지에 `[build apk]`를 쓰는 기능도 유지한다.
  표시된 push끼리만 APK 작업 단위에서 취소·30초 대기를 적용한다.
  표시가 없는 push는 진행 중인 APK 생성을 취소하지 않는다.
- `Android CI`: `build_signed_apk`, `upload_ui_previews` 입력 유지.
- `Desktop manager CI`: `build_exe` 입력과 테스트 성공 후 패키징·기동 검증 유지.
- `Desktop EXE Build`: 기존 수동 전용 EXE 생성 그대로 유지.
- `Build APK and EXE downloads`: 수동 실행으로 debug APK·release APK·EXE 3개 요청 유지.
  이제 이 YAML 편집만으로 세 빌드를 자동 시작하지 않는다.
- 기존 아티팩트 보관일, 저장공간 정리, draft release 보존 경로는 변경하지 않는다.

## 검증 방법

```bash
python -m pip install 'PyYAML>=6,<7'
python -m unittest discover -s .github/tests -v
node --test server/quote-requests/tests/*.test.cjs
node --test website/tests/privacy-crypto.test.mjs website/tests/quote-service.test.mjs website/tests/quote-receipt.test.mjs website/tests/quote-upstream.test.mjs website/tests/species-catalog.test.mjs
```

CI 설정 검사 14개는 40개 경로 사례를 push/PR 양쪽(80개 조합)으로 검증하며,
혼합 변경·삭제/이동 경로·분야별 취소·수동 입력·기존 전체 테스트 명령도 확인한다.
서버·홈페이지 테스트 160개가 로컬에서 통과했다.
Android SDK/Windows GUI 실행 결과를 이 로컬 검증으로 대체하지 않는다.

공식 동작 기준:
- https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax
- https://docs.github.com/en/actions/concepts/workflows-and-actions/concurrency

경로 필터로 생략된 워크플로를 향후 필수 PR 검사로 지정하면 Pending 상태가 남을 수 있다.
필수 검사 정책을 추가할 때에는 모든 PR에 결과를 내는 별도 집계 검사를 함께 설계한다.
