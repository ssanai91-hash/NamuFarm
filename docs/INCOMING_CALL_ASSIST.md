# 전화 고객 연결

## 사용 흐름

1. 판매화면 오른쪽 위 전화 아이콘 → `전화 수신 연결` 켜기.
2. 전화·통화 기록 권한, Android 13 이상에서는 알림 권한을 허용한다.
3. 일반 전화가 오면 번호를 로컬에 보관하고 `견적 작성` / `답장 작성` 알림을 표시한다.
4. 알림을 누르면 나무팜의 전화 고객 연결 화면이 열린다. 앱 안의 전화 아이콘에서도 최근 번호를 확인할 수 있다.
5. `이 번호로 견적 작성`을 눌러 고객 번호를 적용한 뒤, 기존 판매 재고/빠른 견적 → 견적 바구니 → 문자 기능을 사용한다. 기존 고객 번호가 다르면 변경 확인을 먼저 받는다.
6. `응답 문자 작성`에서는 기본 문구를 선택·수정하고 `문자 앱 열기`를 누른다. 발송은 사용자가 문자 앱에서 직접 한다.

관리화면에서 알림을 열어도 미저장 재고 입력이나 내비게이션을 강제로 바꾸지 않는다. 관리 작업을 마치고 판매화면으로 돌아가면 선택한 번호를 사용할 수 있다. 견적 저장 중에는 번호 적용을 보류한다.

## 파일별 역할

| 파일 | 역할 |
| --- | --- |
| `phone/IncomingCallReceiver.kt` | Activity 생명주기와 분리된 manifest receiver. 사용자 opt-in 및 두 전화 권한 확인 후 RINGING만 처리. OFFHOOK 발신 통화는 제외. |
| `phone/IncomingCallStore.kt` | 최근 번호 1개와 설정을 앱 전용 SharedPreferences에 보관. 중복 RINGING 제외, IDLE 시 다음 통화 허용, 24시간 경과 시 다음 읽기에서 삭제. |
| `phone/IncomingCallNotifications.kt` | 잠금화면 번호 숨김, 직접 Activity PendingIntent, 견적/답장별 URI, 유효한 로컬 caller ID 확인에 사용하는 요청 모델. |
| `phone/SmsComposer.kt` | 단일 전화번호 검증 및 `ACTION_SENDTO` 문자 작성 화면. 기존 견적 문자에도 같은 경로 적용. |
| `ui/IncomingCallAssist.kt` | 선택적 권한 요청, 번호 변경 확인, 응답 문구, 알림/권한 설정 진입. 열린 화면의 받는 번호는 새 수신 및 화면 재생성에도 유지. |
| `MainActivity.kt` | 기존 자동 권한 요청·동적 수신기 제거, 알림의 최초 실행 및 `onNewIntent` 처리. 명시적인 번호 적용만 ViewModel에 전달. |
| `ui/SellingHome.kt`, `ui/SellingModeScreen.kt` | 전화 고객 연결 진입 버튼 및 공통 문자 작성 연결. |
| Manifest / backup XML | 알림 권한, system phone-state receiver, Activity 재사용. 수신 번호·opt-in은 클라우드 백업/기기 이전 대상에서 제외. |

## 데이터 무결성

- 수신 자체로 `customerPhone`, 견적, 주문, 재고 또는 `pending_sync`를 변경하지 않는다. 임시 전화 제안과 설정만 로컬에 보관한다.
- 이 임시 번호 저장소는 고객/견적의 기준 DB가 아니다. 견적 저장·주문 확정은 기존 Room transaction 및 local-first 순서를 사용한다.
- 수기 견적과 실제 재고 품목의 구분, 주문 취소의 1회 재고 복원 정책은 그대로다.
- 알림은 최신 1개로 갱신한다. 오래된 알림 ID를 눌러도 새 고객 번호로 바꿔치기하지 않는다.
- 답장은 현재 견적의 고객 번호를 바꾸지 않는다. 새 전화가 와도 이미 열린 답장/번호 확인 화면의 대상은 바뀌지 않는다.
- SMS 자동 발송, SEND_SMS 권한, 오버레이 또는 전체화면 알림은 사용하지 않는다.

## Android 지원 조건 및 배포 전 확인

기존 앱의 `READ_PHONE_STATE` + `READ_CALL_LOG` 경로를 확장했다. Android의 `EXTRA_INCOMING_NUMBER`는 API 29부터 deprecated이지만 공식 문서에 수신 조건이 명시되어 있다. 앱에 두 권한이 모두 실제로 허용되고 시스템이 번호를 제공할 때만 작동한다. 통화 기록 권한은 hard restricted 권한이므로 설치 프로그램의 허용 여부도 영향을 준다. 권한 허용 불가 상황을 기본 전화 앱을 강제로 바꾸는 방식으로 우회하지 않는다.

Google Play는 통화 기록 권한을 제한한다. Play 배포 시 적격 사용 사례/예외와 권한 선언 심사를 확인해야 하며, 현재 구현이 심사를 통과한다고 보장하지 않는다. 역할 기반 CallScreeningService 도입은 기존 발신자 식별 앱 교체와 연락처에 저장된 발신자의 처리 조건을 함께 검토한 후 별도로 결정한다.

일반 휴대전화 수신을 대상으로 한다. 발신번호 표시제한/알 수 없는 번호, 카카오톡 등 별도 인터넷 전화, 기기별 절전 제한에서는 번호를 받지 못할 수 있다. 사용자가 앱을 강제 중지한 상태에서는 앱을 다시 열어야 한다. 알림 권한/채널 차단 시 시스템 알림 대신 앱 안에서 저장된 번호만 확인할 수 있다. 배너/잠금화면 표시 방식은 휴대폰 알림 설정에 따른다.

실제 단말에서 다음을 확인한다: 앱이 열린 상태/백그라운드/프로세스 종료 후 수신, 전화 받기/부재중/연속 수신, 알림에서 최초 실행·재실행, 잠금화면 개인정보 숨김, 권한 거부·철회, 듀얼 SIM, 기본 문자 앱의 한글 본문 및 수신 번호. JVM/Compose 테스트만으로 통신사·OEM 동작까지 검증할 수 없다.

공식 참고 문서:

- https://developer.android.com/reference/android/telephony/TelephonyManager#ACTION_PHONE_STATE_CHANGED
- https://developer.android.com/reference/android/Manifest.permission#READ_CALL_LOG
- https://developer.android.com/develop/background-work/background-tasks/broadcasts/broadcast-exceptions
- https://developer.android.com/guide/components/activities/background-starts
- https://developer.android.com/develop/ui/views/notifications/notification-permission
- https://support.google.com/googleplay/android-developer/answer/10208820
