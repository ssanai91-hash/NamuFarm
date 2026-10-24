# NamuFarm 작업 브랜치

- **Android 수정·APK 빌드는 `restore/android-initial-20260927`에서 수행한다.** 작업 시작 전 그 브랜치의 최신 커밋과 `docs/ANDROID_INITIAL_BUILD.md`를 확인한다. main의 Android 소스에 적용하거나 main을 대신 빌드하지 않는다.
- `main`은 홈페이지·PC(나피)·공용 Apps Script 기준이다. Android 기능 이식을 위해 main 전체를 초기버전 브랜치에 병합하지 않는다. 기존 서버 URL·배포 ID·고객 자료는 보존한다.
- 초기 Android 패키지는 `com.example.namufarm.original`이다. 기존 ui/·res/·공식견적서·농원/묘목/매매·NamuFarm.db v13·NamuFarmQuotes.db v1을 보존하고 문의는 별도 DB로 추가한다. 파괴적 마이그레이션 금지.
- 서명 충돌을 기존 앱 삭제나 패키지명 변경으로 해결하지 않는다. 배포용 인증서가 없으면 시험용 빌드로 명확히 표시한다.
- 사용자 요청 전 GitHub Actions를 실행하지 않는다. 로컬 빌드하고 커밋에 `[skip ci]`를 붙인다. 실제 고객 접수·문자·메일 시험은 사용자가 수행한다.
- 브랜치 삭제나 강제 기록 덮어쓰기는 수행하지 않는다. 필요한 브랜치만 최신 커밋을 조건으로 갱신한다.

- 초기 Android 고정 인증서 SHA-256: `a31a78a895be8f89bacb2c7b355f413e1cacecfbfd769b257801fcb619e616e1`. 기존 초기 APK 1.1.3·1.1.5와 일치한다. `NamuFarm-signing-backup-20261019.zip`의 초기 앱 키를 사용하고 main용 다른 키와 구별한다. 초기 브랜치의 `scripts/sign_initial_apk.py`로 이전 APK와 검증한다. 키가 없으면 새 키/디버그 키로 업데이트용 APK를 대신 전달하지 않는다. 개인키·비밀번호는 코드/로그에 기록하지 않는다.
