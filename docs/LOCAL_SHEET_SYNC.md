# PC·안드로이드 서버 자동 연결

## 현재 코드와 운영 서버 맞추기

PC·안드로이드에서 `서버 연결` 또는 `지금 동기화`를 누르면 `connect_app`의 `authMode: auto-v1`로 기기를 등록합니다. JSON 파일·공통키 입력은 필요 없습니다.

**GitHub 커밋과 Apps Script 웹앱 게시는 별개입니다.** 설치파일만 새로 빌드해도 기존 운영 웹앱의 인증 동작은 달라지지 않습니다.

1. 기존 운영 Google Apps Script 프로젝트를 엽니다. 새 프로젝트나 스프레드시트를 만들지 않습니다.
2. GitHub `main`의 `server/quote-requests/Code.gs`, `server/quote-requests/SecureCommerce.gs`를 **동일한 이름의 기존 파일**에 반영합니다. 다른 서버 파일(`MonthlyStorage.gs`, `RecoverySetup.gs`, `AnnualLedgers.gs`, `TwoTabQuotes.gs` 등)은 그대로 둡니다. 이미 한 `Code.gs`에 통합 관리 중이면 해당 함수 정의만 중복 없이 갱신합니다.
3. **배포 → 배포 관리 → 기존 웹앱 선택 → 수정(연필) → 버전 새로 만들기 → 배포**를 실행합니다. 새 배포를 추가하지 말고 기존 `/exec` 주소와 실행 계정·접근 설정을 유지합니다.
4. 기존 `/exec`에 접속해 JSON에 `"connectionAuth":"auto-v1"`가 표시되는지 확인합니다. 이 항목이 없거나 `shared-key-v1`이라면 이전 버전이 실행 중입니다.
5. 나피 실행파일과 안드로이드 앱은 최신 `main`에서 빌드·설치한 뒤, 사용자가 `서버 연결` 또는 `지금 동기화`로 직접 확인합니다.

**금지:** `setupQuoteRequests()`, `initializeRecoveredServer()`, `reset_to_default_server()` 같은 초기화·복구 함수 실행, 운영 시트 초기화, Script Properties 삭제. 기존 고객·견적·주문자료와 기기 설정을 보존합니다.

기존 암호키·기기 인증 요청도 호환을 위해 서버에 남아 있습니다. 다만 **자동 등록을 허용하면 서버 URL을 아는 제3자가 등록해 고객 정보를 열람할 수 있으므로**, 운영 공개 전에 별도의 접근 제한을 마련해야 합니다.

## Windows 나피 빌드

이미 로컬에 저장소가 있는 경우, 저장소 폴더에서:

```powershell
git switch main
git pull --ff-only origin main
cd desktop
.\build_windows.bat
```

결과: `desktop/dist/NamuFarm-PC.exe`

`build_windows.bat`가 가상환경을 준비하고 PyInstaller를 실행합니다. 실제 Windows 동작과 운영 서버 연결은 사용자 직접 확인 대상입니다.

## 농원 관리

안드로이드 견적 화면의 `농원 관리`는 기존 `나무팜_견적관리_운영` 파일의 `농원관리` 탭을 사용합니다. 연도별 견적·주문 탭과 분리된 연락처 목록이며 기존 Room 농원/재고 자료는 변경하지 않습니다.

- 입력 항목: 농원명, 주소, 전화번호, 메모. 농원ID·수정시각은 서버에서 관리합니다.
- `Code.gs`의 인증된 분기에 `list_farms`, `save_farm`, `delete_farm`을 추가했고, 구현은 새 `FarmManagement.gs`에 있습니다.
- 운영 서버에는 GitHub `main`의 `Code.gs`를 갱신하고 `FarmManagement.gs`를 같은 프로젝트의 새 파일로 추가한 뒤 **기존 /exec 배포를 새 버전으로 갱신**합니다. 다른 서버 파일과 Script Properties는 보존합니다.
- 탭은 운영 파일에 추가되어 있습니다. 서버는 `LEGACY_SPREADSHEET_ID`(없으면 `SPREADSHEET_ID`)의 `농원관리` 탭만 읽고 씁니다. 초기화 함수는 실행하지 않습니다.
- 새 농원은 서버가 ID를 발급하며, 수정·삭제는 가져온 버전과 현재 내용이 같을 때만 반영합니다. 저장 확인 후 화면을 갱신하고, 응답을 받지 못한 저장은 같은 작업ID로 다시 시도합니다.
- 기존 휴대폰 Room의 농원 목록을 이 탭으로 임의 이관하거나 삭제하지 않습니다. 예전 재고 관리 화면과 연락처 목록은 별도로 유지됩니다.
