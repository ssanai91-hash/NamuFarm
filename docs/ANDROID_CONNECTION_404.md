# Android 연결 HTTP 404 점검

2026-10-08 휴대폰 요청함 연결에서 HTTP 404가 관찰됐다. 동일 휴대폰 브라우저의 운영 API GET은 `ok:true`, `encryptionReady:true`를 반환했다. 외부에서 같은 운영 주소에 보낸 잘못된 연결 정보 POST도 HTTP 200 / `UNAUTHORIZED`로 거부됐다. 따라서 서버 전체 장애나 연결 코드 만료로 단정하지 않는다. 설치된 APK의 실제 실행 경로는 휴대폰에서 추가 확인해야 한다.

Google ContentService는 POST 처리 후 `script.googleusercontent.com/macros/echo`의 임시 GET 주소로 결과를 반환한다. Google 이슈 541915738에는 이 결과 주소가 즉시 404를 내고 수 초 뒤 정상 응답하는 현상이 보고돼 있다. 이는 이번 증상의 가능한 원인이며 해당 휴대폰의 정확한 원인으로 입증한 것은 아니다.

## 1.0.1의 처리

- 실제 Apps Script 요청 뒤 리디렉션된 HTTPS 결과 GET의 404에만 2초, 3초 간격으로 두 번 재조회한다. 동일 결과 주소와 query를 그대로 사용하며 원래 POST를 재전송하지 않는다.
- 임시 응답 키, 기기 토큰, 연결 코드, 고객정보를 진단 메시지나 로그에 노출하지 않는다. 추가 GET에는 기존 인증 헤더와 body를 전달하지 않는다.
- 최초 운영 주소 404, 다른 호스트/경로, 응답 키가 없는 주소, 다른 HTTP 오류에는 재조회하지 않는다. 취소/인터럽트도 추가 요청을 멈춘다.
- 계속 실패하면 운영 배포 주소 404와 Google 결과 조회 404를 구분해 안내한다. 연결 화면에 앱 버전을 표시한다.
- TLS MockWebServer로 POST 1회 보장, 같은 GET 결과 재조회, 회복/반복 실패, 민감 헤더 제외, 취소와 비대상 오류를 검증한다.
- `NAMUFARM_LIVE_TRANSPORT_CHECK=1`은 실제 앱 Retrofit/OkHttp 코드로 운영 주소를 점검한다. 존재하지 않는 연결 정보만 보내므로 초대 코드 발급/소비, 고객 조회, 시트 저장을 하지 않는다. 현재 수리 브랜치의 PR CI에서만 활성화된다.

## 운영 확인

기존 백업 JSON과 비밀번호를 유지한다. 앱에서 새 연결 코드를 등록한 뒤 기존 보안 백업을 복원하고 서버 보안 연결을 진행한다. 자동 생성된 CI 디버그 서명키는 빌드마다 달라질 수 있으므로 기존 설치 위에 새 APK를 설치할 때 서명 일치 여부를 확인해야 한다. 설치 충돌만으로 기존 앱/데이터를 삭제하지 않는다.

참고: https://developers.google.com/apps-script/guides/content#redirects · https://issuetracker.google.com/issues/541915738
