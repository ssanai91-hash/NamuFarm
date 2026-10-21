# 초기 UI 기반 Android 빌드

기준 UI: f8f2de29e6ac1755f505fc00d444480267399269 (2026-09-27).
개발 브랜치: restore/android-initial-20260927.

빌드는 초기 ui/ 디렉터리와 res/ 리소스가 원본과 동일한지 검사한다.
농원·묘목 운영 시트 연동 코드는 OPERATIONS_MASTER_SYNC.md에 설명한다.
운영 서버는 기존 농원·묘목 API를 그대로 사용한다.

applicationId는 com.example.namufarm.original, 표시 이름은 나무팜이다.
현재 앱과 나란히 설치되며, 이전에 설치한 초기버전의 기기 DB는 버전 13으로 보존 이관한다.
기존 저장소의 release 인증서로 서명하고 NamuFarm-original-release.apk를 draft release에 제공한다.

견적 모드 통합은 INITIAL_QUOTE_MODE.md 참고. 현재 버전 1.1.5(8).

Android의 수정·빌드 기준은 이 브랜치다. main의 Android UI를 병합하거나 main을 대신 빌드하지 않는다.
공식견적서 생성·품목별 비고·판매자 요청사항과 확정견적 주문내역 화면은 quotemode/ 및 quotation/에 추가했다.
초기 ui/의 매매·농원·묘목 화면, NamuFarm.db 및 NamuFarmQuotes.db 스키마는 유지한다.
