# 운영 구글시트·서버 복구

삭제된 운영 저장소를 새 비공개 관리 시트와 독립 Apps Script API v5로 교체했습니다.

- 관리 시트: https://docs.google.com/spreadsheets/d/1TYzm1A0qP_v_3RuRsdL7ieHOUa3abOUwkdPWdUCVx9c/edit
- 운영 Apps Script: https://script.google.com/home/projects/1PDZifBX6iBw21fmOyzq-6aI2AqqW-Ib5zpeJz-fdhiu3TeLAtt_m_IaH/edit
- API: https://script.google.com/macros/s/AKfycbzY7JKzXYmS9APLNFDt15k8dv09zb4EXKFHOOv_T1vpFmkr6TLwbqGznGLgpd2cIVgD/exec

홈페이지의 서버 주소와 비밀값은 배포 환경에 반영했습니다. 비밀값·기기 토큰·일회용 코드는 저장소에 포함하지 않습니다. 고객 자료는 비공개 시트에 저장하고, 판매자 조회는 기기 인증을 유지합니다. 신규 자료에는 판매자 RSA 키나 JSON 복원 절차가 없습니다.

## 초기화와 저장

`RecoverySetup.gs`의 `initializeRecoveredServer()`는 헤더만 있는 명시적 관리 시트에서 최초 실행합니다. 이미 자료가 있거나 다른 저장소에 초기화된 서버는 거부합니다. 동일 시트의 재실행은 기존 자료·비밀값·기록 순번을 보존합니다. 한국 시간 기준 당일 월별 파일과 일별 탭을 미리 생성합니다.

Apps Script에 Code.gs, SecureCommerce.gs, MonthlyStorage.gs, RecoverySetup.gs와 appsscript.json을 적용합니다. 운영 프로젝트에는 네 소스 파일을 합친 Code.gs가 들어 있으며 함수 구성은 동일합니다. 소유자 권한 승인을 완료하고, 소유자로 실행하는 웹 앱 배포를 사용합니다. 공개 GET은 기능 정보만 제공합니다.

접수 UUID·내용 해시·예약 행·확정 단계·견적번호 재사용은 유지합니다. 고객 응답을 알림 전송이나 월간 현황 집계까지 기다리게 하지 않습니다. 알림은 1분 트리거, 집계·정리는 기존 5분 트리거가 처리합니다. Google 웹 앱의 응답 지연 자체를 제거하는 변경은 아닙니다.

## 기기 연결

서버가 교체되었으므로 휴대폰과 PC는 각각 `createDeviceInvite()`에서 발급한 15분 유효 일회용 코드로 새 서버에 처음 한 번 연결합니다. 한 코드는 한 기기용입니다. 이후 같은 서버의 앱 업데이트에서는 연결 상태를 유지합니다. 앱 삭제·다른 휴대폰·다른 Windows 계정에서는 새 기기 인증이 필요합니다.

안드로이드 1.1.2(versionCode 5)는 기존 토큰을 보존하면서 기존 서버의 연결 완료 표식을 새 서버에 재사용하지 않습니다. PC 0.4.1은 기본 운영 주소를 변경하고, 삭제된 주소와 정확히 일치하는 기존 설정만 이전합니다. 사용자 지정 서버·초안·미전송 내역은 보존합니다.

FCM 프로젝트 설정은 아직 없는 상태이므로 자동 푸시 수신까지 검증된 것은 아닙니다. 앱의 요청 가져오기와 PC 동기화를 사용합니다. 운영 시트를 삭제하면 저장된 고객 자료를 서버에서 복구할 수 없습니다.

## 검증

- 서버 회귀 검사: 117개 통과. 초기화 재시도·잘못된 저장소 거부·접수 재시도·알림 실패·동기화·삭제 전파 검사 포함.
- 실제 서버 기기 인증과 요청 목록 조회 성공. 합성 견적 접수와 동일 UUID 재시도는 같은 견적번호를 반환.
- 홈페이지 배포 환경 교체 완료. 자동 HTTP 클라이언트의 사용자 도메인 접속은 Cloudflare에서 거부되어 실제 홈페이지 입력 화면의 최종 확인은 별도로 필요.
- Android/Windows 전체 빌드와 테스트 결과는 이 변경의 GitHub Actions에 기록.
