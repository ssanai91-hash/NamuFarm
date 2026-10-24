# 초기 UI 기반 Android 빌드

기준 UI: f8f2de29e6ac1755f505fc00d444480267399269 (2026-09-27).
개발 브랜치: restore/android-initial-20260927.

빌드는 초기 ui/ 디렉터리와 res/ 리소스가 원본과 동일한지 검사한다.
농원·묘목 운영 시트 연동 코드는 OPERATIONS_MASTER_SYNC.md에 설명한다.
운영 서버는 기존 농원·묘목 API를 그대로 사용한다.

applicationId는 com.example.namufarm.original, 표시 이름은 나무팜이다.
현재 앱과 나란히 설치되며, 이전에 설치한 초기버전의 기기 DB는 버전 13으로 보존 이관한다.
초기 앱의 고정 인증서로 서명한다. main용 인증서와 다르므로 기존 초기버전 APK와 대조하며, 현재는 GitHub Actions 없이 로컬에서 빌드·서명한다.

견적 모드 통합은 INITIAL_QUOTE_MODE.md 참고. 현재 버전 1.1.6(9).

Android의 수정·빌드 기준은 이 브랜치다. main의 Android UI를 병합하거나 main을 대신 빌드하지 않는다.
공식견적서 생성·품목별 비고·판매자 요청사항과 확정견적 주문내역 화면은 quotemode/ 및 quotation/에 추가했다.
초기 ui/의 매매·농원·묘목 화면, NamuFarm.db 및 NamuFarmQuotes.db 스키마는 유지한다.

## 1.1.6 고객문의 (2026-10-24)

- 잘못 main에 적용했던 Android 문의 기능을 이 브랜치의 1.1.5 위로 이식했다. main 전체를 병합하지 않았다.
- 견적 → 문의처리 → 매매 → 농원·묘목. 기존 공식견적서·견적·매매·농원/묘목 코드는 보존한다.
- 문의는 기존 DB를 변경하지 않고 앱의 백업 제외 영역에 `NamuFarmInquiries.db` v1로 저장한다.
- 최초 실행/재실행은 기기 저장 목록만 표시한다. 불러오기와 답변 작업은 기존 운영 기기 인증을 재사용한다.
- 서버 버전 36의 문의 장부·API를 나피와 공유한다. 상대 기기의 변경은 다음 불러오기에서 반영된다.
- 이 환경의 빌드는 GitHub Actions 없이 Java 21·Gradle 9.3.1·SDK 36.1로 수행한다. 처음에는 키를 찾지 못해 디버그 서명 시험용을 전달했으나, 이후 보관된 초기 앱 키를 복구했다. 아래 서명 복구 기록이 최신 상태다.
- 최초 시험용 `NamuFarm-original-1.1.6-inquiry-test.apk` 및 main의 `NamuFarm-1.1.4-inquiry-test.apk`는 초기버전 업데이트에 사용하지 않는다. 동일 인증서로 서명한 `NamuFarm-initial-1.1.6-update.apk`가 대체한다.
- 로컬 `:app:assembleDebug` 성공(1분 8초), APK 패키지·버전 및 v2 서명 검증 완료. 초기 ui/·res/와 기존 두 DB 소스가 기준 커밋과 동일한지 확인했다. 실기기 설치·실제 문의 조회/발송은 실행하지 않았다.
- APK SHA-256: `af5a96da9bf81a3ad9564ed1a78483fbb8409dc7dd9125126f3e100360a90de3`.
- 작업 기준과 보존 규칙은 루트 `AGENTS.md`를 따른다. 실제 기기·운영 발송은 사용자 확인 대상이다.

## 초기 앱 서명 복구

- 보관된 `NamuFarm-signing-backup-20261019.zip`의 `namufarm-original-release.jks`를 복구했다. 다른 `NamuFarm-Android-Signing-Backup.zip`의 main용 키와 구별한다.
- 기존 `NamuFarm-original-1.1.3-install.apk`와 `NamuFarm-initial-1.1.5-053f19c.apk` 모두 인증서가 일치했다.
- 고정 인증서 SHA-256: `a31a78a895be8f89bacb2c7b355f413e1cacecfbfd769b257801fcb619e616e1`.
- 로컬 서명 도구 `scripts/sign_initial_apk.py`는 위 인증서·기존 APK·패키지·상향 versionCode·release 여부를 검사한다. 비밀번호는 환경변수로만 전달하며, 키·비밀번호를 저장소 또는 로그에 남기지 않는다.
- 환경변수: `NAMUFARM_INITIAL_KEYSTORE`, `NAMUFARM_INITIAL_KEY_ALIAS`, `NAMUFARM_INITIAL_STORE_PASSWORD`, `NAMUFARM_INITIAL_KEY_PASSWORD`, `JAVA_HOME`.
- `python3 scripts/sign_initial_apk.py --unsigned-apk <release-unsigned.apk> --reference-apk <이전-초기버전.apk> --build-tools <SDK-build-tools-디렉터리> --output <새-update.apk>`.
- 키가 없거나 인증서가 다르면 업데이트 파일을 만들지 않는다. 새 키·디버그 키로 대체하거나 기존 앱을 삭제하도록 안내하지 않는다.
- 휴대폰 설치 실패 화면만으로 원인을 단정하지 않는다. 실제 기기 업데이트 및 자료 유지 확인은 사용자 확인 대상이다.
- 2026-10-24 로컬 `assembleRelease` 성공(3분 7초), release 기본 검사(lintVital) 통과. 동일 인증서로 서명한 `NamuFarm-initial-1.1.6-update.apk` 및 ZIP을 전달했다. 패키지 `com.example.namufarm.original`, versionCode 9, 기존 1.1.5는 8, debuggable=false, 서명·정렬 검증 완료.
- 정식 서명 APK SHA-256: `0f8f2f78fae0520155d463b828efb40312305a4ad06d7f2d56ab4f0a33da224d`.
