# 나무팜 API v5 전환

판매자 흐름은 기기 연결 → 동기화 → 견적 작성 → 문자/메일 초안 → 실제 발송 확인 → 구매/입금/배송 확인입니다. 필수 보안키 백업 화면과 서버 공개키 등록 단계를 제거했습니다. 빠른 견적·오늘 견적·오늘 주문, 공식 견적서 원본, 로젠 BIFF8 XLS 계약은 유지합니다.

## 운영 반영 순서

1. 기존 Apps Script 프로젝트의 `Code.gs`, `SecureCommerce.gs`, `MonthlyStorage.gs`를 함께 갱신합니다. 별도 새 서버나 시트를 만들지 않습니다.
2. 소유자가 `setupQuoteRequests()`를 실행합니다. 기존 UUID·순번·이벤트·기기 인증·월별 저장 모드와 데이터를 보존하며 알려진 판매 시트 헤더만 갱신합니다. 새로 월별 저장을 켜는 경우 `setupMonthlyQuoteStorage()` → `enableMonthlyQuoteStorage()`를 사용합니다.
3. 기존 웹앱 배포를 새 버전으로 갱신하고 `/exec`에서 `version:5`, `connectionMode:device-auth`, `customerDataReady:true`를 확인합니다. 인증 없는 POST는 여전히 UNAUTHORIZED여야 합니다. `/dev`나 리다이렉트 결과 URL을 앱에 넣지 않습니다.
4. 홈페이지의 실제 `QUOTE_PRIVACY_NOTICE`를 `website/docs/production-privacy-notice.txt` 기준으로 갱신하고 홈페이지를 배포합니다. 새 본문은 비공개 시트 접근 권한자가 읽을 수 있으므로 ‘앱의 개인키로만 열 수 있다’는 이전 설명을 남기지 않습니다. `QUOTE_SELLER_KEY` 환경값은 사용하지 않습니다. `QUOTE_WEB_SECRET`은 유지합니다.
5. Android 1.1.0과 PC 0.4를 설치합니다. 기존 기기 인증은 유지하며, 새 기기만 각각 일회용 코드를 입력합니다. 구 앱이 v5 평문 행을 읽을 수는 없으므로 서버/홈페이지 전환 후 판매자 앱도 함께 갱신해야 합니다.
6. 합성 고객으로 홈페이지 접수 → Android 가져오기 → 공식 견적서 초안 → 구매/입금 확인 → PC 동기화 → 로젠 XLS 저장을 확인합니다. 실제 문자·메일 발송과 택배 접수는 운영자가 수행합니다.

## 호환 및 복구

- Android 1.1.1은 키가 없는 v4 요청을 별도 보관하고 새 v5 요청을 계속 가져옵니다. 이전 암호문·정상 요청·동기화 커서를 한 Room 트랜잭션으로 저장하므로 자료를 버리거나 저장 전에 커서를 전진시키지 않습니다. 보류 자료가 있을 때만 선택형 ‘이전 자료 열기’를 표시하며, 기존 백업으로 열어도 현재 다운로드 커서는 되돌리지 않습니다. 잘못된 형식이나 변조된 본문은 이전과 같이 동기화를 실패시킵니다.
- v4 미전송 이벤트는 기존 ID·암호문·digest 그대로 재전송해 응답 유실 후에도 중복 주문을 만들지 않습니다. 이후 수정부터 v5 본문을 사용합니다.
- 기존 암호문을 평문으로 일괄 변환하거나, 서버 키·행을 자동 삭제하지 않습니다. 암호문만 남고 기존 개인키가 없으면 새 연결 방식으로도 복호화할 수 없습니다.
- v5 본문을 저장한 뒤 서버만 v4로 되돌리면 새 기록을 읽지 못합니다. 운영 전환 전 버전과 비공개 자료 백업을 보관하고, 문제 발생 시 v5 읽기 호환을 유지한 채 수정합니다.
- PC v1 보관함은 이전 비밀번호를 한 번 확인해 DPAPI 방식으로 전환합니다. DB 내용을 다시 만드는 방식이 아니며 전송 대기와 초안을 보존합니다. 다른 Windows 계정에서는 기존 DB를 자동으로 열 수 없습니다.
- 개인정보 보관 기한과 상담 종료 처리는 동일합니다. 서버의 원본 JSON이 업무 원본이며, 시트 D:I 표시 셀을 수작업으로 고치는 것은 앱의 수정 기능을 대신하지 않습니다.

## Android 설치 파일 서명

이전 CI는 매 실행마다 임시 debug 인증서로 APK를 만들었습니다. 이 인증서의 개인키가 없으면 기존 설치본을 새 키로 직접 업데이트할 수 없습니다. 고객 암호화 제거로 해결되는 문제가 아닙니다.

이제 CI debug APK는 테스트에만 사용하고 배포 아티팩트로 올리지 않습니다. main 빌드에서 고정 release 서명과 인증서 지문 검증이 성공한 APK만 `namufarm-release-apk`로 게시합니다. 서명 설정이 없으면 테스트는 가능하지만 설치용 APK 게시를 생략하고 이유를 로그에 표시합니다. 일부 설정만 있거나 지문이 다르면 배포를 실패시킵니다.

관리자가 신뢰할 수 있는 환경에서 Android Studio의 서명 키 생성 기능으로 한 번 만든 keystore를 안전하게 보관하고, GitHub 저장소 Actions secrets에 아래 값을 등록합니다. keystore나 비밀번호를 Git에 커밋하지 않습니다.

- `NAMUFARM_KEYSTORE_BASE64`: 고정 keystore 파일의 Base64
- `NAMUFARM_STORE_PASSWORD`, `NAMUFARM_KEY_ALIAS`, `NAMUFARM_KEY_PASSWORD`
- Actions variable `NAMUFARM_SIGNING_SHA256`: 해당 alias의 공개 인증서 SHA-256 지문

키 생성은 매 업데이트마다 반복하지 않습니다. 향후 모든 배포에서 같은 키와 증가한 versionCode를 사용합니다. 기존 설치본과 다른 서명으로 최초 전환할 때는 로컬 초안·미전송 자료·v4 복구 가능 여부를 확인한 뒤 진행해야 합니다. 이 커밋은 키를 임의 생성하거나 저장소 secret을 변경하지 않습니다.

참고: [Android 공식 서명 안내](https://developer.android.com/studio/publish/app-signing), [Microsoft DPAPI](https://learn.microsoft.com/en-us/windows/win32/api/dpapi/nf-dpapi-cryptprotectdata).

## 검증

서버 테스트는 실제 Apps Script 코드를 메모리 시트에서 실행합니다. Python 통합 검사는 같은 서버 코드를 통해 신규 키 없는 접수·구매·입금·다른 PC 복구 및 기존 암호문 호환을 검사합니다. Windows CI에서는 실제 DPAPI 왕복과 패키징 실행을 확인합니다. Android CI에서 실제 판매 gateway와 Keystore 주입 기반 로컬 보호, Room/견적서/발송/상태 회귀 검사를 수행합니다.

운영 계정의 Apps Script 배포, 홈페이지 배포, 실제 전화기 설치 및 물리 프린터 출력은 코드 테스트 통과와 별도의 확인 단계입니다.

