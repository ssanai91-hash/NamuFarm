# 2026-10-07 견적 서버 운영 전환 기록

사용자 승인: 운영 Google Sheets와 Google 권한/API 설정을 적용하고, 실제 고객정보가 없는 기존 개발용 견적 시트를 삭제한다. PC·Android 기능 구현은 후속 작업이다.

## 적용 결과

- 독립 Apps Script: `나무팜_견적접수_운영서버`, script ID `1hdZGAvo1mrONSDOxjMeFBXbgOxzA_myllhJFfJR_2iNjlpEJW_f5ZuxB`.
- 기존 표준 Google Cloud 프로젝트 `179104120134`에 연결했다. 소유자 OAuth 승인 완료, Google Drive API v3 활성화를 실제 파일 생성으로 확인했다.
- 웹 앱 버전 1: 실행 주체는 소유자, 접근 방식은 기존과 동일한 공개 HTTP 진입점이다. 쓰기에는 홈페이지 공유 비밀값, 기기 작업에는 기기 토큰 인증을 유지한다.
- 새 API: `https://script.google.com/macros/s/AKfycbw8uqBWveIhqmXielALjSLnY1yDenKAwl070FT6Lsxsj7Xiod1C5JSBDZGfQtlrl6A-CQ/exec`
- 관리 파일: [나무팜_견적관리](https://docs.google.com/spreadsheets/d/1dpVmcvHgq7byrWhJXSrp8m1Kiww3S2ZC6jBsY4tW_3M/edit).
- 월 파일: [나무팜_견적요청_2026-10](https://docs.google.com/spreadsheets/d/1FgGcWoISrm_Ufi5gTvnD1GacyWZBxc-cshjG-8iLCEA/edit).
- 두 파일 모두 소유자만 접근 가능하며 `Asia/Seoul`을 사용한다. 최초 감사: 요청 0, 예약 0, 월 1, 미완료 월 0.
- `QUOTE_STORAGE_READY=1`, `QUOTE_STORAGE_MODE=monthly-v1`. 고객 접수일의 일자 시트는 첫 실제 접수 때 생성된다.
- 기존 공개 암호화 키, 홈페이지 비밀값, 기기 등록, 전역 순번 7과 접수번호 카운터를 보존했다. 서버에 개인키를 추가하지 않았다.
- 새 트리거: `dispatchQuoteNotifications` 1분, `purgeExpiredCustomerData` 5분, `maintainMonthlyQuoteStorage` 5분.
- 홈페이지는 기존 버전 18의 소스를 유지하고 `QUOTE_SCRIPT_URL`만 변경했다. 환경 revision 6, 배포 `appgdep_6ac61ebf25988191ad382da0fa6d3728` 성공.
- PC·Android 소스의 기본 API 주소도 새 주소로 맞췄다. 앱 기능 구현이나 새 앱 빌드·설치는 수행하지 않았다. 기존 설치 앱이 별도로 저장한 서버 주소는 새 주소로 다시 연결해야 한다.

## 검증과 수정

- 서버 자동 테스트 108개 통과. 홈페이지 코드 변경 없음; 앞서 확인한 홈페이지 테스트 19개 통과 결과를 유지한다.
- 실제 Google 시험에서는 별도 관리 파일과 두 월 파일, 시험용 RSA 키·비밀값·기기 토큰을 사용했다. 운영 알림 기기와 연결하지 않았다.
- 암호화 접수, 동일 UUID 재전송의 접수번호 보존, 내용 충돌 거절, 기기 인증 거절/성공, 전역 커서 조회, 조기 종료 tombstone, 반복 종료의 순번 불변, 월별 유지보수, 감사가 통과했다.
- Google Sheets 일반 텍스트 셀이 boolean `false`를 문자열 `"false"`로 영속화하는 문제를 발견했다. 쓰기 전 문자열로 명시적 변환하고 읽기 시 정규화하도록 수정했다. 기존 논리값 호환과 잘못된 플래그 거절을 회귀 테스트에 추가했다.
- 시험 파일은 모두 ID를 대조하여 휴지통 이동했고, 시험 트리거를 제거하고 원래 설정을 복원했다. 임시 소유자 검증 코드는 배포 전에 삭제했다.
- 새 `/exec` GET: `ok=true`, version 4, `encryptionReady=true`, `retentionDays=30`, `requestDeletionSync=true`, `receiptTimeZone=Asia/Seoul` 확인.
- 인증 없는 `list_requests` POST: `UNAUTHORIZED` 확인. 운영에 가짜 고객 접수를 생성하지 않았다.
- Firebase는 `validate_only=true`와 의도적으로 잘못된 시험 토큰으로 호출했다. HTTP 400 `INVALID_ARGUMENT`(유효하지 않은 등록 토큰) 응답으로 API와 인증 경로가 처리됨을 확인했다. 실제 메시지는 보내지 않았으며 실기기 알림 도달을 입증하는 시험은 아니다.
- 실제 병렬 부하·서비스 한도·장시간 안정성·PC/Android 실기기 수신은 별도 후속 검증이다.

## 기존 저장소 정리

- 기존 bound 서버의 분 단위 알림 및 5분 파기 트리거 2개를 제거하고 목록 0개를 확인했다.
- 기존 배포 `AKfycby0dlMj7qKxXZDsShc92WQn0DW0YyOHjJIEsVCM4xrd8PDzVqVL1JuQLkPuxPxxyGDR`를 보관처리하여 구 URL을 중지했다.
- 기존 견적 파일 `1v--nBeLaSCnfhtawzTSngWFkjNbNCyBQpRZ54kRkztg` (`나무팜_홈페이지_견적접수`)은 휴지통 이동 완료. UI에서 30일 후 완전삭제 안내를 확인했다.
- 별개 재고/품종 시트와 업로드된 품종 Excel 파일은 이 정리 대상이 아니다.

## 남은 Google 콘솔 설정

OAuth 승인 화면에 앱이 테스트 중이라고 표시됐다. 장기 운영 전에 소유자가 Google Cloud의 **Google 인증 플랫폼 → 대상 → 게시 상태**를 확인하여 **프로덕션**으로 게시해야 한다. 현재 작업 브라우저에서 Cloud 콘솔은 `Site Unavailable`을 반환하므로 이 설정은 직접 변경하지 못했다. Sheets·Drive 권한 승인과 API 활성화는 완료됐지만 OAuth 게시 상태까지 완료했다고 보고하지 않는다.

Google 공식 문서에 따르면 외부 사용자 유형의 테스트 상태 앱은 기본 프로필 외 범위를 요청할 때 refresh token이 7일 뒤 만료된다. 앱 게시와 Google 검증 승인은 다른 절차다. 이 서버의 OAuth 주체는 소유자이며 홈페이지 고객에게 Google 로그인을 요구하지 않는다.

참고: https://developers.google.com/identity/protocols/oauth2#expiration
