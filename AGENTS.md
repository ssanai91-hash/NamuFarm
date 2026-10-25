# NamuFarm 작업 기준 · main 통합

- **2026-10-24 사용자 요청으로 홈페이지·PC(나피)·Android·Apps Script를 모두 `main`에서 관리한다.** 작업 전에 main 최신 커밋과 관련 파일만 확인한다. 임의로 새 브랜치를 만들거나 이전 Android 브랜치에서 계속 개발하지 않는다.
- Android는 `restore/android-initial-20260927`의 `ad0074f`를 main의 `app/`로 통합했다. 이전 main의 Android 구현으로 되돌리거나 두 앱 구현을 섞지 않는다. 초기버전 브랜치는 과거 기록으로 보존한다.
- 한 로컬 프로젝트 폴더에서 `app/`(Android), `desktop/`(나피), `website/`(홈페이지), `server/`(Apps Script), `scripts/`(도구)를 구분한다. Android Studio는 루트 폴더를 연다. Gradle에는 `:app`만 포함된다.
- 초기 앱 패키지 `com.example.namufarm.original`과 기존 ui/·res/·공식견적서·농원/묘목/매매 기능을 보존한다. DB는 `NamuFarm.db` v13, `NamuFarmQuotes.db` v1, 독립 `NamuFarmInquiries.db` v1을 유지하며 파괴적 마이그레이션을 사용하지 않는다.
- 기존 초기버전 설치 위에 업데이트하려면 같은 인증서로 서명한다. 고정 SHA-256은 `a31a78a895be8f89bacb2c7b355f413e1cacecfbfd769b257801fcb619e616e1`이다. `NamuFarm-signing-backup-20261019.zip`의 초기 앱 키와 `scripts/sign_initial_apk.py`를 사용한다. 과거 main용 키·새 키·디버그 키로 업데이트용 APK를 대신 전달하거나 앱 삭제·패키지명 변경으로 서명 오류를 해결하지 않는다. 개인키·비밀번호는 저장소/로그에 넣지 않는다.
- 기존 서버 URL·배포 ID·인증·고객자료·로컬 초안·전송 대기를 유지한다. 서버 확인 전 완료/커서 전진 금지, 결과 불명 시 자동 재발송 금지, 고객 원문 수정 금지 원칙을 지킨다. 구매확정과 입금확인은 같은 상태다.
- 요청한 기능만 최소 수정한다. 사용자가 실제 PC·Android·홈페이지와 운영 발송을 직접 확인한다. 별도 요청 없이는 컴파일·자동시험·대규모 통합시험·실제 고객 접수/문자/메일 시험·GitHub Actions를 실행하지 않는다. 커밋 메시지에는 `[skip ci]`를 붙인다. 과거 main Android용 CI/APK 작업은 통합 후 중지하며 로컬 컴파일을 기준으로 한다.
- PC 나피는 수정 작업마다 패치 버전을 0.0.1씩 올린다(2026-10-25 사용자 요청: 0.5.3 → 0.5.4부터 적용). `desktop/namufarm_desktop/__init__.py`와 `desktop/pyproject.toml`의 버전을 함께 갱신하며 컴파일·실행 검증은 사용자가 로컬에서 진행한다.
- GitHub 커밋, 홈페이지 게시, Apps Script 재배포를 구분한다. 홈페이지 게시 시 기존 `namufarm.co.kr` 도메인과 서버 설정을 유지한다.
- 브랜치 삭제·강제 기록 덮어쓰기는 수행하지 않는다. 갱신할 브랜치의 최신 커밋을 조건으로 반영한다.
