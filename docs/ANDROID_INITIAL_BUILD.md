# 초기 UI 기반 Android 빌드

기준 UI: f8f2de29e6ac1755f505fc00d444480267399269 (2026-09-27).
개발 브랜치: restore/android-initial-20260927.

빌드는 초기 ui/ 디렉터리와 res/ 리소스가 원본과 동일한지 검사한다.
농원·묘목 운영 시트 연동 코드는 OPERATIONS_MASTER_SYNC.md에 설명한다.
운영 서버는 기존 농원·묘목 API를 그대로 사용한다.

applicationId는 com.example.namufarm.original, 표시 이름은 나무팜이다.
현재 앱과 나란히 설치되며, 이전에 설치한 초기버전의 기기 DB는 버전 13으로 보존 이관한다.
기존 저장소의 release 인증서로 서명하고 NamuFarm-original-release.apk를 draft release에 제공한다.

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
- 이 환경의 빌드는 GitHub Actions 없이 Java 21·Gradle 9.3.1·SDK 36.1로 수행한다. 현재 로컬 배포용 서명키가 없어 시험용 디버그 서명이다. 기존 release 서명 APK와의 덮어쓰기 설치 가능 여부는 확인하지 않았다.
- 파일명은 `NamuFarm-original-1.1.6-inquiry-test.apk`. 이전 main의 `NamuFarm-1.1.4-inquiry-test.apk`는 초기버전 업데이트용이 아니다.
- 로컬 `:app:assembleDebug` 성공(1분 8초), APK 패키지·버전 및 v2 서명 검증 완료. 초기 ui/·res/와 기존 두 DB 소스가 기준 커밋과 동일한지 확인했다. 실기기 설치·실제 문의 조회/발송은 실행하지 않았다.
- APK SHA-256: `af5a96da9bf81a3ad9564ed1a78483fbb8409dc7dd9125126f3e100360a90de3`.
- 작업 기준과 보존 규칙은 루트 `AGENTS.md`를 따른다. 실제 기기·운영 발송은 사용자 확인 대상이다.
