# 홈페이지 견적 요청 · 앱 요청함

## 구현 범위와 원칙

홈페이지에서 묘목명·규격(선택)·수량·성함·전화번호·배송 지역·요청사항을 확인한 뒤 접수합니다. 가격, 재고 보장이나 온라인 결제는 제공하지 않습니다. 묘목 카드는 문의 예시입니다.

- 고객 접수는 별도 `웹견적요청` Sheet에 저장됩니다. 기존 재고·주문 API와 분리합니다.
- Sheet 저장 및 flush가 성공한 후에만 접수번호를 반환합니다. 같은 UUID와 같은 내용의 재전송은 같은 접수로 처리합니다. 내용이 다르면 충돌로 중단합니다.
- 응답 유실 시 홈페이지는 같은 ID로 재확인합니다. 해당 탭의 sessionStorage에 최대 24시간 복구용 내용을 두며, 성공하면 지웁니다. 불확실한 접수 내용을 수정해서 새 요청을 만들지 못하도록 잠급니다. 새 탭/다른 기기의 별도 접수까지 중복으로 판정하지는 않습니다.
- 원본 JSON과 해시를 함께 보관하여 전화번호의 앞자리 0, 수식처럼 보이는 문자열, 메모를 정확히 복원합니다. Sheet의 표시 셀에 원본 재구성을 의존하지 않습니다.
- 접수 직후 FCM을 시도하며 실패하면 Sheet의 PENDING 알림을 분 단위 트리거가 재시도합니다. 알림에는 고객 개인정보를 넣지 않습니다. 푸시 지연/누락과 관계없이 요청은 남습니다.
- Android Room v14의 `web_quote_request`는 주문·견적·재고 테이블과 독립적입니다. UUID/순번 유일성, 페이지 단위 transaction, commit한 최대 순번으로 재개합니다. pending 전송 실패 시 Pull 중단 원칙도 유지합니다.
- 요청을 열면 기기에서 읽음 처리합니다. 다른 폰의 읽음 상태는 바꾸지 않습니다.
- 고객 번호 적용은 사용자가 선택하고, 기존 견적 고객과 다르면 확인받습니다. 품목을 자동으로 INVENTORY/MANUAL로 분류하거나 차감하지 않습니다. 실제 재고와 규격을 확인한 뒤 기존 견적 기능을 이용합니다.
- 문자 버튼은 기본 문자 앱의 초안을 엽니다. 자동 문자 발송이나 SMS 전송 권한은 추가하지 않습니다.

## 변경 파일

| 파일 | 역할 |
|---|---|
| `server/quote-requests/Code.gs` | 인증·검증·UUID 멱등 접수·순번 발급·기기 연결·요청 조회·FCM outbox |
| `server/quote-requests/appsscript.json` | Apps Script 권한과 한국 시간대 |
| `WebQuoteApi.kt` | 별도 Apps Script POST API 및 필수 필드/순번 검증 |
| `WebQuoteRequestEntity.kt`, `AppDatabase.kt`, `ManagementDao.kt` | Room v14 요청함·발행 문서와 12→13→14 비파괴 migration |
| `LocalDataSource.kt`, `NamuFarmRepository.kt` | 페이지 원자적 반영, 중복/변경/순번 충돌 방지 |
| `WebRequestSettings.kt` | Android Keystore AES-GCM 기기 인증정보 저장·백업 제외 |
| `WebRequestConnection.kt`, `PushRegistrationWorker.kt` | 연결 코드 등록, 연결 회복 후 토큰 등록 재시도 |
| `WebRequestPush.kt`, `AndroidManifest.xml` | FCM opt-in, 고객 정보 없는 알림 및 요청함 이동 |
| `WebInboxViewModel.kt`, `WebQuoteInbox.kt`, `MainActivity.kt`, `SellingHome.kt` | 요청함 UI·상세·고객 번호 확인 적용 |

홈페이지 소스는 같은 저장소의 [website/](../website/)에 함께 보관합니다. `website/components/quote-home.tsx`는 고객 화면, `website/lib/quote-schema.ts`는 입력 검증, `website/app/api/quote-requests/route.ts`는 비밀키를 숨긴 서버 중계입니다. 설치·빌드·배포 설명은 [홈페이지 README](../website/README.md)를 참고하세요. 현재 호스팅은 기존 Sites 프로젝트에서 유지되며, 이 GitHub 폴더의 커밋이 자동 배포되지는 않습니다.

## 운영 연결 순서

현재 저장소에는 운영용 신규 Apps Script 주소, Firebase 설정, 홈페이지 개인정보 안내가 없습니다. 빈 설정의 앱/홈페이지는 연결 준비 상태를 표시합니다. 기존 `GOOGLE_SCRIPT_URL`에 신규 API를 덮어쓰지 마세요.

1. 기존 재고·주문 데이터의 백업을 확보하고, 접수용 Google Spreadsheet를 지정합니다. 같은 문서에 별도 탭을 사용할 수 있습니다. 기존 탭은 수정/삭제하지 않습니다.
2. 별도 Apps Script 프로젝트에 `server/quote-requests/Code.gs`와 manifest를 추가합니다. Script Properties에 `SPREADSHEET_ID`, 32자 이상 무작위 `WEB_SECRET`, `FIREBASE_PROJECT_ID`를 설정합니다. WEB_SECRET은 소스/브라우저/APK/채팅에 넣지 않습니다.
3. Firebase Android 앱의 package name은 `com.example.namufarm`입니다. Firebase Cloud Messaging API v1을 활성화하고 Apps Script 실행 주체에 해당 프로젝트의 메시지 발송 권한을 부여합니다. 필요하면 Apps Script를 표준 Google Cloud 프로젝트에 연결합니다. 서비스 계정 개인키를 앱에 넣지 않습니다.
4. Apps Script 편집기에서 `setupQuoteRequests()`를 실행하고 권한을 승인합니다. 함수는 빈 경우에만 헤더를 작성하고 재시도 트리거를 한 번 등록합니다. 기존 헤더가 다르면 중단합니다. 접수 원본·순번 열을 수동 변경하거나 행을 삭제하지 마세요. 원본은 추가형입니다.
5. 웹 앱을 소유자로 실행하도록 배포합니다. 홈페이지 서버와 앱에서 호출할 수 있도록 접근 대상을 설정하되 모든 쓰기/읽기는 별도 비밀키/기기 토큰으로 인증됩니다. `/exec` URL을 사용합니다. 소스 변경 후에는 새 배포 버전을 반영해야 합니다.
6. 홈페이지 비밀 설정: `QUOTE_SCRIPT_URL` = 위 `/exec`, `QUOTE_WEB_SECRET` = 같은 WEB_SECRET. `QUOTE_PRIVACY_NOTICE`에는 실제 운영자·연락 창구·견적 요청 처리의 법적 근거·목적과 항목·보유기간·파기 기준 등 운영자가 확정한 안내문을 넣습니다. 설정이 모두 있어야 실제 전송을 허용합니다. 자동으로 작성한 문구를 운영 정책으로 확정하지 않습니다.
7. `local.properties.example`을 참고해 Android `WEB_REQUEST_SCRIPT_URL`과 Firebase Android 공개 구성값 네 가지를 설정하고 앱을 빌드합니다. 값이 바뀌면 재빌드/재설치가 필요합니다. 기존 재고/주소/계좌 설정은 유지합니다.
8. Apps Script 편집기에서 `createDeviceInvite()`를 실행합니다. 15분 유효한 1기기용 코드를 친구의 앱 **홈페이지 요청함 → 요청함 연결**에 입력합니다. 코드 유실/만료 시 새로 발급합니다. 분실 폰은 편집기에서 `revokeDevice(deviceHash)`로 차단합니다.
9. 앱에서 새 요청 알림을 허용합니다. 테스트 요청 한 건으로 Sheet 1행 → 앱 알림 → 요청함 내용 → 전화번호 확인 → 문자 초안 순서를 실폰에서 확인합니다. 설정만으로 푸시 전달 성공을 보장하지 않습니다.
10. 접수와 알림 검증 후 홈페이지 공개 범위와 구매한 도메인의 DNS를 연결합니다. 이번 작업에서 DNS나 기존 공개 범위를 변경하지 않습니다.

## 검증과 한계

- 서버 Node 회귀 테스트는 Apps Script 서비스를 대역으로 실행합니다. 중복, 응답 유실, 저장 실패, 원본 왕복, 정렬, 인증, 기기 연결 재시도, 알림 실패, rate limit을 검증합니다. Google 운영 서버 실행 테스트를 대체하지 않습니다.
- Android CI에서 기존 업무 테스트와 신규 요청함·migration·수신번호 보호 테스트, 디버그 APK 빌드를 실행합니다.
- 운영 Sheet/FCM/실폰 종단 간 검증은 운영 설정 이후 필요합니다. CI는 빈 환경 설정으로 기존 업무 기능의 회귀를 검증합니다.
- 푸시 알림은 통신·권한·OS 설정에 따라 지연될 수 있습니다. 요청함에서 새 요청 가져오기를 제공합니다.
- 서버 rate limit은 주소를 HMAC으로 가린 키와 Apps Script cache를 사용합니다. 공개 트래픽/스팸 증가 시 별도 스팸 방어와 저장소 확장을 검토해야 합니다.
- 기존 재고·주문 API의 서버 멱등성 및 여러 폰의 절대 재고 충돌 문제는 이 접수 API로 해결되지 않습니다. 기존 검토 보고서의 제한이 유효합니다.

참고: https://firebase.google.com/docs/cloud-messaging/android/receive-messages , https://firebase.google.com/docs/cloud-messaging/auth-server , https://developers.google.com/apps-script/reference/spreadsheet/range

## 개인정보 체크 없는 견적 처리 (서버 v3)

홈페이지는 필수 동의 체크박스 대신 처리 안내와 `/privacy` 링크를 표시합니다. 새 요청은 `processingBasis: "quote_request"`를 보내며 동의를 받았다는 값을 생성하지 않습니다. 접수 서버는 과거의 실제 동의 기록과 접수번호를 그대로 지원하여 불확실한 접수의 재시도와 중복 방지를 유지합니다.

기존 Apps Script 프로젝트의 `Code.gs`를 최신 소스로 교체하고 기존 웹 앱 배포를 **새 버전**으로 업데이트해야 합니다. Script Properties, 기존 접수 Sheet, 배포 URL은 유지합니다. `/exec`의 GET 응답에 `version: 3`, `processingBases: ["quote_request"]` 및 `requestFields`의 새 접수 항목가 표시되는지 확인하세요.

개인정보 처리방침에 문의 연락처·실제 보유기간·파기 기준을 확정하기 전에는 `QUOTE_PRIVACY_NOTICE`를 설정하지 않습니다. 새 서버의 처리 근거 응답이 없거나 운영 안내가 비어 있으면 접수를 열지 않습니다. 상담 종료 후 90일은 제안된 운영 기준이며 자동 삭제가 구현되었거나 법정 보유기간이라는 의미가 아닙니다. Google Sheet와 Android에 보관한 사본을 포함하여 실제 파기 방식을 정해야 합니다.

## 이메일과 공식 견적서

전화번호는 필수, 이메일은 선택입니다. 공식 견적서를 요청하면 수신인·회사명과 전달 방법(`sms`, `email`, `both`)을 선택합니다. 이메일 또는 둘 다를 선택할 때만 이메일이 필수입니다. 새 접수의 `customerEmail`, `formalQuoteRequested`, `recipientName`, `replyChannel`은 원본 JSON에 저장하며 기존 Sheet 열을 바꾸지 않습니다. 서버 v1/v2의 기존 접수 원본 JSON과 해시를 유지합니다.

운영 홈페이지의 새 접수를 열려면 Apps Script **v3**를 기존 배포의 새 버전으로 적용해야 합니다. GET 응답의 `requestFields`에 위 네 항목이 모두 있어야 홈페이지가 새 접수를 허용합니다. 개인정보 처리방침의 선택 항목에도 이메일·수신인·회사명 및 견적서 전달 목적을 포함하세요.

앱 요청함의 **이 고객 번호로 견적 작성**은 고객 성함·이메일·견적서 수신인과 희망 전달 방법도 적용합니다. 문의 품목은 재고에 자동 등록하거나 예약하지 않습니다. 운영자가 판매 재고 또는 수기 견적에서 실제 규격과 가격을 선택하고 견적을 저장합니다. 연락처 정보와 견적은 같은 Room transaction으로 저장합니다. 다른 전화번호로 바꾸면 이전 고객의 이메일 등 작성 중인 연락 정보를 초기화합니다.

공식 견적서를 선택한 견적은 저장 후 문서 작성 화면이 열립니다. 기존 견적도 **오늘 견적 → 견적 상세 → 공식 견적서 · PDF / 이미지**로 발행할 수 있습니다. 상호·사업자번호·대표자·주소·연락처를 직접 설정하고 추가 세액, 유효기간, 계좌, 주요 사항을 확인한 후 **견적서 확정 · 저장**을 누릅니다. 첨부 예시의 실제 사업자 정보나 직인을 임의로 복제하지 않습니다. 직인 이미지 삽입은 이번 기능에 포함되지 않습니다. 세율은 자동 적용하지 않으며 추가 세액은 운영자가 직접 확인해 입력합니다.

발행 문서는 `quote_document`의 변경 없는 JSON 기록입니다. 품목·단가·운반비·추가 세액·합계와 수신인·공급자·조건을 모두 저장합니다. 동일 견적의 발행 재시도는 기존 기록을 반환합니다. 견적이 주문으로 전환되거나 삭제되어도 발행 문서는 **오늘 견적 → 발행 견적서 보관함**에서 다시 열 수 있습니다. 내용을 수정하려면 새 견적을 저장해 발행합니다.

동일한 그리기 함수로 A4 PDF와 페이지별 JPG를 생성합니다. 여러 페이지의 문자 견적서는 여러 이미지로 첨부합니다. 출력 파일은 앱의 전용 캐시에 생성하고 제한된 FileProvider URI로 일시 공유합니다. 파일 공유·메일·문자 앱을 열었다는 이유로 발송 완료 상태를 기록하지 않습니다. 받는 사람과 첨부파일을 확인한 후 해당 앱에서 직접 보내야 합니다. 기본 문자 앱의 수신인 채움·이미지 첨부와 통신사 압축은 실제 휴대폰에서 확인해야 합니다.

Android 변경을 적용하려면 기존 `local.properties`와 서명키를 유지해 업데이트 빌드를 설치합니다. 서버 v3 적용, 운영 개인정보 처리방침 게시, 새 Android 앱 설치는 각각 필요합니다. 운영 서버 및 실제 휴대폰에 자동으로 반영된 것으로 간주하지 않습니다.
