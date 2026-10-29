# 새 견적·문의 휴대폰 알림(Firebase) 설정

코드: Android 1.1.34 이상, Apps Script `Code.gs`·`CustomerInquiries.gs`(2026-10-30). 알림 내용은 “새 견적요청/새 고객문의가 들어왔어요”뿐이며 고객 이름·전화번호를 보내지 않는다.
키·비밀번호를 저장소에 넣지 않는다. `google-services.json`과 `local.properties`는 커밋하지 않는다.

## 1. Firebase 프로젝트 (사용자, 1회)
1. Apps Script 소유 구글 계정으로 https://console.firebase.google.com → 프로젝트 추가(예: `namufarm-push`), Google 애널리틱스는 꺼도 됨.
2. Android 앱 추가: 패키지 이름 **`com.example.namufarm.original`**(정확히). SHA-1은 비워도 됨.
3. `google-services.json` 다운로드 → 빌드 PC에만 보관.

## 2. 빌드 PC `E:\NamuFarm_Project\local.properties`에 추가 (빌드 담당)
`google-services.json`에서 값을 옮긴다.
```
firebase.appId=<client[0].client_info.mobilesdk_app_id>
firebase.apiKey=<client[0].api_key[0].current_key>
firebase.projectId=<project_info.project_id>
firebase.senderId=<project_info.project_number>
```
값이 없으면 앱은 알림 없이 기존과 똑같이 동작한다. google-services 플러그인은 쓰지 않는다(AGP 9 호환 위험 회피, 앱이 직접 초기화).

## 3. Apps Script (사용자)
1. 프로젝트 설정 → 스크립트 속성에 `FIREBASE_PROJECT_ID` = Firebase 프로젝트 ID(`project_info.project_id`).
2. `Code.gs`·`CustomerInquiries.gs`를 최신 main으로 바꾸고 **기존 배포 ID로 새 버전 재배포**(URL 유지).
3. 편집기에서 `setupPushNotifications` 실행 → 권한 승인(Firebase 메시지 보내기). 1분 트리거 확인.

## 4. 휴대폰
1. Android 1.1.34 업데이트(앱 삭제 금지) → 앱을 한 번 열고 알림 허용.
2. 삼성 휴대폰은 설정 → 애플리케이션 → 나무팜 → 배터리 → **제한 없음** 권장(늦게 오는 것 방지).

## 5. 시험 (실제 고객 접수 없이)
편집기에서 `sendTestPushNotification` 실행 → 휴대폰에 “새 견적요청이 들어왔어요”가 오면 완료. 누르면 견적 탭이 열리고 목록을 동기화한다.
- 로그가 `실패 403 PERMISSION_DENIED`/`SERVICE_DISABLED`이면: Apps Script 프로젝트 설정 → Google Cloud Platform(GCP) 프로젝트 → **프로젝트 변경**에 Firebase 프로젝트 번호(`project_number`)를 입력(그 Cloud 프로젝트의 OAuth 동의 화면 설정이 먼저 필요). 변경 직후 `setupPushNotifications`를 다시 실행해 권한을 승인하고, 홈페이지 견적·문의 접수와 앱 동기화가 정상인지 확인한다. 배포 ID·URL은 바뀌지 않는다. 손님이 적은 시간에 한다.
- 처음 켜면 최근 30일 안의 미확인 ‘신규’ 견적이 있으면 그 알림이 한 번씩 올 수 있다.
