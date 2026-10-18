# 초기 UI 기반 Android 빌드

기준 UI: f8f2de29e6ac1755f505fc00d444480267399269 (2026-09-27).
개발 브랜치: restore/android-initial-20260927.

빌드는 초기 ui/ 디렉터리와 res/ 리소스가 원본과 동일한지 검사한다.
농원·묘목 운영 시트 연동 코드는 OPERATIONS_MASTER_SYNC.md에 설명한다.
운영 서버는 기존 농원·묘목 API를 그대로 사용한다.

빌드 중 applicationId를 com.example.namufarm.original, 표시 이름을 나무팜 초기버전으로 지정한다.
현재 앱과 나란히 설치되며, 이전에 설치한 초기버전의 기기 DB는 버전 13으로 보존 이관한다.
기존 저장소의 release 인증서로 서명하고 NamuFarm-original-release.apk를 draft release에 제공한다.

견적 모드 통합은 INITIAL_QUOTE_MODE.md 참고. 버전 1.1-quote-mode(3).
