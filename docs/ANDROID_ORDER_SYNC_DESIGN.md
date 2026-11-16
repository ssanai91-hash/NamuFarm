# Android 주문 서버 동기화 · 3단계 설계안

작성일: 2026-11-16. 분석 기준 main: `8ed1236bafb40dd7e21965f383078ec9ba15be14`.
**상태: 사용자 설계 승인 후 Android 1.1.44(47) 코드에 반영했다. 미빌드·미시험·운영 동작 미확인이다.** 아래는 승인한 설계의 기준이며 실제 반영·제약은 마지막 절에 기록한다. 서버 실행 코드·DB 스키마·인증·URL·배포 ID·서명은 변경하지 않았다. 컴파일·자동시험·Actions·운영 API 호출·고객 데이터 조회·시트 쓰기·발송·재배포는 수행하지 않았다.

## 1. 확인된 현재 상태

| 항목 | 확인 결과 | 코드 근거 |
| --- | --- | --- |
| 주문 전송·수신 | 미연결. `processPendingSync` 실패, 업로드·취소·배송·상태·삭제는 `false`, 주문 다운로드는 `-1` | [SyncManager.kt](../app/src/main/java/com/example/namufarm/data/SyncManager.kt):19~40 |
| 이전 Retrofit | `BASE_URL=https://example.com/`. `order/get_orders/cancel_order` 등 이전 DTO는 현재 인증 API 계약과 다름. `RetrofitClient.api` 사용처는 검색에서 확인되지 않았고 주소 검색은 `jusoApi` 사용 | [RetrofitClient.kt](../app/src/main/java/com/example/namufarm/data/remote/RetrofitClient.kt), [GoogleSheetApi.kt](../app/src/main/java/com/example/namufarm/data/remote/GoogleSheetApi.kt) |
| 재사용 가능한 연결 | 기존 `/exec` endpoint, `connect_app`, 기존 기기 토큰과 응답 검사·리다이렉트 처리·자동 네트워크 재시도 비활성화가 있음 | [OperationsMasterApi.kt](../app/src/main/java/com/example/namufarm/data/remote/OperationsMasterApi.kt)의 `OperationsMasterClient`, `OperationsMasterTransport` |
| 전체 동기화 버튼 | 실제로는 농원·묘목·재고만 처리. 주문 대기는 표시하지만 처리하지 않음 | [ManagementViewModel.kt](../app/src/main/java/com/example/namufarm/viewmodel/ManagementViewModel.kt):953~960, [OperationsMasterSync.kt](../app/src/main/java/com/example/namufarm/data/OperationsMasterSync.kt):113~132 |
| 웹 견적 입금확인 | `confirm_quote_payment` 전 작업ID·주문번호를 `quote_workflow_v1`에 저장하고, 성공 후 같은 번호로 로컬 주문 생성. 판매 장부 업로드는 없음 | [QuoteWorkflow.kt](../app/src/main/java/com/example/namufarm/quotation/QuoteWorkflow.kt):496~535 |
| 로컬 주문 저장 | 웹 주문/수기 주문 생성에 Room 트랜잭션과 주문 전송대기가 있음. 배송정보 수정은 로컬 수정과 대기 등록이 별도 호출이며 취소 외 상태 변경의 대기도 부족 | `ManagementViewModel.kt`:596~729, 765~808, 1140~1201 |
| 저장 정보 부족 | `OrderEntity`에는 서버 revision·작업ID·원견적 UUID·세액·완료시각·원가 상태 등이 없음. 현재 대기 payload는 주문 스냅샷 또는 부분 수정값만 보유 | [NamuFarmEntities.kt](../app/src/main/java/com/example/namufarm/data/NamuFarmEntities.kt), [PendingSyncEntity.kt](../app/src/main/java/com/example/namufarm/data/PendingSyncEntity.kt) |
| 번호 충돌 | `nextWebOrderNumber`와 수기 전환은 기기별 `ORDER_날짜_순번`. 같은 날짜의 다른 기기와 충돌 가능 | `ManagementViewModel.kt`:561~596, 632~640 |
| 서버의 방어 규칙 | 판매 작업ID+지문 멱등, revision, 견적-주문 연결, ScriptLock 구현이 저장소에 있음 | [SecureCommerce.gs](../server/quote-requests/SecureCommerce.gs)의 `saveSale_`, [ConfirmedQuotes.gs](../server/quote-requests/ConfirmedQuotes.gs)의 입금/판매 guard, [AnnualLedgers.gs](../server/quote-requests/AnnualLedgers.gs)의 `reserveSaleSource_` |

저장소 코드의 존재와 운영 서버 배포 완료는 다르다. 이번에는 실제 `/exec`를 호출하거나 운영 배포 버전을 대조하지 않았다.

## 2. 연결 방식 — URL 치환 대신 기존 인증 클라이언트 재사용

- 신규 `OrderSync`를 `OperationsMasterGateway` 위에 추가하고 `SyncManager`가 위임한다. `MainActivity`에서 이미 사용하는 같은 클라이언트·기기 자격을 주입한다.
- `example.com`을 운영 URL로 치환해 이전 `GoogleSheetApi` 패킷을 보내지 않는다. 주문 경로에서는 그 API를 사용하지 않는다. 미사용이 확인된 이전 Retrofit 주문 객체/placeholder 접근만 제거하고 행안부 `jusoApi`와 기존 DTO 소비 코드는 보존한다. 전체 DTO 정리는 범위에서 제외한다.
- 기존 endpoint·배포 ID·인증·리다이렉트 응답 검사를 유지한다. 현재 마스터 오류 표시와 별도로 주문 충돌·결과 불명·일부 실패를 구분한다. 미지원 응답에서 이전 패킷으로 fallback하지 않는다.

| 용도 | 사용할 기존 API | 핵심 필드·조건 |
| --- | --- | --- |
| 기능 확인 | 기존 서비스 정보 GET, `get_confirmed_quote` | v5·판매번호 예약 등 작업별 지원 확인. 웹 견적에는 `annual-two-tabs`·확정견적/입금 기능도 요구. 미지원/준비 안 됨이면 로컬과 대기 유지 |
| 번호 확보 | `reserve_sold_numbers` | 영속 `operationId`, 한국 날짜, count. 번호가 확정되기 전 입금/판매 호출 금지 |
| 웹 입금 선점 | `confirm_quote_payment` | `requestId`, `operationId`, 동일 `orderNumber`, `expectedRevision`, `quoteFingerprint`, `amount` |
| 판매 생성·수정·취소·배송 | `save_sale` | `protocolVersion:5`, `eventId`, `metadata`, `personalData`, `sourceRequestId`, `fingerprint`. 웹 견적의 버전·지문도 보존 |
| 판매 가져오기 | `list_sales` | `afterSequence`, 100건 단위, `hasMore`. 모든 연도·월은 서버의 기존 조회 범위를 따름 |
| 기존 삭제 기능 | `delete_sale` | 명시적 사용자 삭제에만 작업ID·주문번호·현재 판매 revision 사용. 확인 응답 전 로컬 삭제 금지 |

수기 주문에 가짜 웹 요청 UUID를 만들지 않는다. `sourceRequestId`는 빈 문자열이다. 시트 직접 쓰기·새 서버·새 장부·새 상태 단계는 도입하지 않는다.

## 3. 로컬 영속화 — 기존 DB 버전·테이블 유지

권장안은 **기존 Room `pending_sync`의 JSON 확장 + 기존 백업 대상 `NamuFarmPrefs`의 주문 연결 기록**이다. `NamuFarm.db` v14, `NamuFarmQuotes.db` v1, 문의 DB v1과 기존 행을 유지한다. 주문 메타데이터를 영구 전송대기로 위장하지 않는다.

- 연결 기록: 로컬 주문 식별자/번호 ↔ 서버 주문번호, 마지막 확인 revision·sequence·지문, 원견적 UUID·접수번호, 서버 원본 스냅샷, 번호 예약/복구 상태. 현재 DB에 없는 고객·원가·세액·공식견적서 필드도 서버에서 읽은 원본으로 보존한다.
- 대기 기록: payload 버전, 동작, 내부 operationId, 서버 `eventId`, 기준 판매 revision, 견적 revision/지문, 변경 전후 스냅샷, **최초 전송 직전에 확정한 동일 본문**과 시각·해시. 서버에 보내는 JSON은 서버 허용 필드만 포함한다.
- 신규 주문·상태/배송 수정·필요한 로컬 재고 변경과 해당 대기 등록을 같은 Room 트랜잭션으로 처리한다. 네트워크는 밖에서 실행한다. 화면의 즉시 시도와 수동 동기화 모두 같은 큐 처리기를 사용한다.
- 최신 상태를 가져오기 전까지 확정할 수 없는 revision·서버번호는 준비 단계에서 기록한다. 네트워크로 최초 제출한 뒤에는 본문·ID·시각·지문을 바꾸지 않는다. 같은 주문의 뒤 작업은 앞 작업 완료 후 순서대로 준비한다.
- 서버 ACK의 주문번호·revision·지문을 보낸 값과 대조 → 연결 기록을 `commit()`으로 저장 → 해당 Room 대기만 삭제한다. 저장 실패면 대기를 남긴다. 과거 작업 ACK가 더 최신 서버 캐시를 덮어쓰지 않게 revision을 비교한다.
- Room과 SharedPreferences를 하나의 트랜잭션으로 취급하지 않는다. ACK 기록 후 대기 삭제 전 종료돼도 원래 본문을 다시 확인할 수 있어야 한다. 다운로드 커서는 해당 페이지의 Room 반영과 연결 기록 저장이 끝난 뒤에만 전진한다.
- `DataBackup.kt`는 현재 `NamuFarmPrefs` 전체와 Room 주문·대기를 백업한다. 같은 저장소 안의 버전 있는 키로 추가하고 기존 백업도 읽는다. 복원 후 기록·대기·서버가 다르면 재전송보다 조회·충돌 확인을 먼저 한다. 기록 형식 오류는 빈 상태로 초기화하지 않는다.

대량 원본을 SharedPreferences에 보존하는 비용은 남는다. 이번 승인안은 DB 불변·기존 백업 호환을 우선하며, 장기 보관량 때문에 별도 테이블이 필요해지면 후속 설계로 따로 승인받는다.

## 4. 번호 — 기존 번호 보존과 신규 충돌 방지

1. **이미 서버 입금확인된 웹 주문**: `sharedPayment`/서버 `payment.orderNumber`를 그대로 쓴다. 기존 `ORDER_...`도 새 번호로 바꾸지 않는다.
2. **아직 선점하지 않은 새 웹 주문**: 기존 예약 API에서 `SOLD-YYYYMMDD-NNNN`을 확보·영속화한 뒤 claim을 만든다. 번호 콜백은 비동기 예약이 가능하도록 바꾼다. 예약 응답이 불명확하면 claim을 보내지 않는다. 예약만 된 번호의 공백은 허용하며 재사용하지 않는다.
3. **새 수기 매매의 오프라인 저장**: 현재 로컬 저장을 막지 않는다. 임시 로컬 번호·주문을 보존하고 첫 서버 전송 전에 예약한 SOLD 번호와 연결 기록을 만든다. 이후 서버 작업은 같은 SOLD 번호만 사용하고 로컬 원번호는 보존한다. 서버 연결 후 표시 번호를 SOLD로 제공하되 로컬 식별자를 덮어쓰지 않는다.
4. **업데이트 전 주문·전송대기**: 일괄 재번호·자동 업로드하지 않는다. 먼저 서버 주문과 정확한 식별 연결을 확인한다. 동명이인·같은 전화/금액이나 `ORDER_...` 문자열만 같다는 이유로 병합하지 않는다. 충돌·출처 불명은 ‘기존 주문 연결/로컬 보관’ 확인 대상으로 남긴다. 확인된 동일 작업은 기존 번호·내용으로 복구한다.

번호 예약 도중 날짜가 바뀌고 결과도 불명확하면 이전 예약을 확정한 것처럼 쓰지 않는다. 아직 claim/판매를 제출하지 않은 예약 단계라는 사실을 보존해 새 예약을 준비할 수 있지만, **이미 입금/판매 제출 이력이 있는 작업은 번호를 다시 만들지 않는다.**

## 5. 웹 견적 입금 → 실제 판매 업로드

1. 서버 최신 견적·현재 revision·실제 전달 완료 지문·금액을 확인한다. 수정 후 미발송이면 입금확인을 막는다.
2. 번호와 claim 작업ID·견적 버전·금액·주문 생성용 초안을 먼저 보존한다. 기존 `quote_workflow_v1` 기록을 재사용한다.
3. `confirm_quote_payment`가 같은 ScriptLock 안에서 `주문견적연결`과 입금 정보를 선점한다. 다른 기기의 다른 주문번호 선점은 충돌로 멈춘다.
4. claim 성공 후 **동일 번호**로 Room 주문과 주문 업로드 대기를 저장한다. `createPaidWebOrder`에 원견적 UUID·가격/세액·서버 확정시각을 넘긴다. 재개 시 주문 존재 확인으로 중복 생성하지 않는다. 웹 주문은 기존처럼 농원 재고를 차감하지 않는다.
5. 같은 번호의 `save_sale`로 실제 판매 행을 저장한다. claim 성공은 ‘입금확인’, 판매 업로드 ACK는 ‘서버 반영 완료’로 구분해 표시한다. 업로드 실패 시 입금확인을 반복하지 않고 주문 대기를 유지한다.
6. 다른 기기에서 먼저 입금확인했다면 기존 번호를 안내하고 `list_sales`에서 그 주문을 가져온다. 현재 휴대폰 초안으로 새 주문·재고 차감·새 claim을 만들지 않는다. claim만 있고 판매 행은 아직 없으면 ‘입금확인됨·주문 반영 대기’로 남긴다.

서버 근거: `ConfirmedQuotes.gs`의 `confirmedPaymentCheck_`, `confirmQuotePayment_`, `confirmedSaleGuard_`; `AnnualLedgers.gs`의 `reserveSaleSource_`; `SecureCommerce.gs`의 `saveSale_`.

## 6. 지문·판매 revision·금액

- 요청 UUID, 번호 예약 operationId, 입금 claim operationId, 판매 eventId를 구분한다. 각 네트워크 작업은 한 번 만든 ID를 계속 쓰며 재시도 횟수로 ID나 revision을 늘리지 않는다.
- 판매는 신규 revision=1, 후속 변경은 확인한 서버 판매 revision+1이다. 견적 expectedRevision은 별도 값이다. 서버가 반환한 견적 지문을 임의로 다시 만들지 않는다.
- 판매 fingerprint는 서버 `saleDigest_`와 동일하게 `{metadata, personalData: normalizedPersonal_(...)}`의 키 정렬·배열 순서·문자·정수 규칙으로 SHA-256을 계산한다. `orderId` 제거, null `quoteDocumentJson` 생략, U+2028/U+2029 이스케이프도 맞춘다. 기존 견적용 trim 옵션 해시를 그대로 쓰지 않는다.
- 정상적인 `save_sale` ACK의 `saved=true`만 보고 대기를 삭제하지 않는다. 요청한 주문번호·revision·fingerprint 모두 맞아야 완료다. 판매의 `personalData.order`와 metadata의 공통 필드도 일치해야 한다.
- 웹 `createPaidWebOrder`는 현재 `sharedAmount`(공급가+선불배송비+세액)를 로컬 `totalPrice`로 저장한다. 이것을 서버 `metadata.totalPrice`에 복사하고 세액을 또 더하지 않는다. 웹 초안/서버 pricing으로 **공급가와 세액을 분리**하고 합계가 claim 금액과 같은지 확인한다.
- 수기 주문은 기존 품목·선불배송비 계산을 유지한다. 과거 웹 주문의 세액/원가를 복구할 근거가 없으면 `0`으로 꾸미지 않고 보류한다. PC에서 받은 주문의 이메일·고객명·원가·세금·공식견적서 등 이 화면에 없는 정보도 덮어쓰지 않는다.
- 시각은 최초 작업 준비 때 고정한다. 웹 paidAt은 서버 입금 기록, 신규 수기는 저장한 실제 입금확인 시각을 사용한다. 기존 문자열 시각을 추측해 새 현재시각으로 덮어쓰지 않는다. 연도·월은 기존 서버 paidAt 기준이며 구매확정=입금확인 규칙은 유지한다.

## 7. 주문 가져오기·충돌·재고

- 기존 전체 동기화 동작에 주문을 연결하고 영역별 결과를 보고한다. 농원·묘목 전용 동기화까지 주문을 보내도록 넓히지 않는다. 자동 폴링/WorkManager 주문 재전송은 추가하지 않는다.
- 최초 연결은 `list_sales(afterSequence=0)`, 이후는 확정 커서부터 읽는다. 최대 100건 페이지의 형식·증가 순번·revision·금액·지문을 검증하고 주문별 병합한다. 한 번의 처리 상한에 도달해도 완료로 표시하거나 커서를 끝으로 이동하지 않고 이어서 처리한다.
- 로컬 미전송 변경이 있는 주문은 서버 자료로 덮어쓰지 않는다. 충돌 행의 해당 페이지 커서는 보류하고 로컬/서버 사본을 보존한다. 같은 작업으로 확인된 ACK만 해소하고 다른 주문 처리는 가능한 범위에서 분리한다.
- 결과 불명/timeout: 대기와 원본문 유지, 사용자 수동 동기화에서 조회/동일 요청 확인. 새 eventId·번호 생성, 무조건 성공 처리, 자동 전체 재전송 금지.
- 원견적 만료·삭제로 입금 claim 조회/복구가 막히면 기존 번호의 판매 장부·연결 기록부터 확인한다. 확인할 수 없는 과거 claim을 새 UUID·새 입금으로 다시 만들지 않고 보류한다.
- `CONFLICT`: 현재 서버 자료를 확인하고 사용자 선택 전 덮어쓰기 금지. `UNAUTHORIZED/NOT_READY/INVALID_ACTION/UPGRADE_REQUIRED`: 대기 유지 및 연결/배포 안내. 취소·배송정보 변경도 Room 수정과 대기를 같은 트랜잭션으로 기록한다.
- `deleted=true`·주문번호가 있는 삭제 표식은 로컬 미전송/백업과 대조 후 처리한다. 일반 `expired=true`(판매 집계 보관 행은 번호 없이 sequence만 올 수 있음)는 로컬 원주문을 추측해서 삭제하지 않는다. 한 페이지/수신 목록에 없다는 이유로 전체 로컬 주문을 지우지 않는다.
- `hidden=true`는 작업 목록 숨김으로 보존하며 삭제·재무 자료 제거로 바꾸지 않는다. 과거 `encryptedPersonal`은 기존 키/형식으로 열 수 있을 때만 병합하고, 키가 없거나 내용 검증이 실패하면 빈 고객정보로 덮어쓰지 않고 해당 페이지를 보류한다.
- 판매 업로드/다운로드 재시도는 재고를 차감하지 않는다. 수기 주문 생성 때의 기존 `InventorySync` 변경 한 번만 유지하고 기존 재고 API가 전송한다. 서버 `save_sale`에 이전 `itemsToDeduct` 차감 패킷을 붙이지 않는다. 주문·재고 중 하나만 성공하면 다른 대기를 남기고 부분 완료를 표시한다.

## 8. 승인 후 예상 변경 범위

| 파일·영역 | 변경 목적 |
| --- | --- |
| 신규 `data/OrderSync.kt`, 주문 프로토콜/기록 클래스 | DTO 변환, 지문·응답 검증, 주문 큐/수신/연결 기록 |
| `data/SyncManager.kt` | 주문 stub을 새 처리기로 위임 |
| `data/PendingSyncEntity.kt`, `ManagementDao.kt` | JSON payload 확장, 번호 조회·갱신, 상태/삭제 작업 종류. 테이블 변경 없음 |
| `viewmodel/ManagementViewModel.kt`, `MainActivity.kt` | 주입, 저장과 대기 트랜잭션, 전체 동기화, 결과 안내 |
| `quotation/QuoteWorkflow.kt`, `SharedQuote.kt`와 입금 콜백 화면 | 예약 번호·기존 payment 복구·원본/세액 전달. 기존 발송 완료 기준 유지 |
| `ui/SellingModeScreen.kt` 등 기존 주문 표시 | 연결된 SOLD 표시번호·숨김·반영 대기/충돌 안내만 연동. 화면 구조 개편 없음 |
| `remote/RetrofitClient.kt`, 필요 시 기존 transport의 서비스정보 조회 | 미사용 placeholder 경로 제거, 기존 인증 연결 재사용. 주소 API 유지 |
| `domain/order`와 백업/복원 관련 코드 | 기존 codec·정수 범위 재사용, 연결 기록의 복구 일관성. 기존 백업 호환 유지 |
| `app/build.gradle.kts`, 관련 문서 | 구현 시점 최신 Android 버전에서 한 단계 증가, 미실행 검증과 미배포 구분 |

`desktop/`, 홈페이지, Apps Script 실행 코드, 기존 DB 스키마·서명은 변경하지 않는 안이다. 소스 분석 중 계약 부족이 드러나면 임의로 우회하지 않고 필요한 서버 차이를 먼저 보고한다.

## 9. 재배포와 승인할 결정

- **이번 설계 문서 반영:** 운영 서버 재배포 불필요. 앱/APK 빌드·배포 없음.
- **승인 후 Android 구현:** 저장소의 기존 API만 재사용하므로 새로운 서버 코드 변경을 기본 전제로 하지 않는다. 단, 운영 서버가 현재 API/설정을 지원하지 않으면 사용 전 **기존 배포 ID로 서버 재배포/설정 확인이 필요**하다. 운영 코드 버전은 아직 미확인이다. 자동 배포하지 않는다.
- **승인 대상:** 기존 인증 클라이언트 재사용, DB 불변과 기존 prefs+대기 JSON 방식, 새 주문 SOLD 예약·기존 번호 보존, 과거 미전송 주문의 확인 후 연결, 로컬 우선+수동 동기화.
- **당시 구현 승인 범위:** 실제 주문 업로드·수신, 번호 예약 연결, payload 전환·기존 자료 연결, 충돌/복구 안내. 반영 결과는 아래 10절에 기록한다. 4단계 만료 행 정리는 별도 승인 범위다.

설계 승인을 받아 아래 구현을 반영했다. 사용자는 동시 입금확인·같은 작업 재개·공급가/세액·기존 번호/자료·주문/재고 부분 실패·PC 왕복 동기화를 직접 확인한다. 소스 반영은 정상 동작 검증이 아니다.

## 10. 3단계 구현 반영 — 1.1.44(47), 미빌드

- `OrderProtocol.kt`: v5 판매 JSON·엄격한 정수/금액/지문 검사, 한국 시각, 공급가/세액 분리와 PC 원본 보존. `OrderSync.kt`: 서버번호 예약, 영속 작업/본문, ACK 대조, 주문 페이지 병합, 사용자 연결 선택, 중단된 연결 복구를 추가했다.
- `MainActivity`에서 기존 OperationsMasterClient와 기존 백업 대상 prefs를 주입한다. 공개 서비스 정보에서 v5·plain·연도별 장부·준비 상태·번호 예약 지원을 확인한다. 미지원 상태에서 legacy 패킷으로 fallback하지 않는다. `RetrofitClient`의 미사용 example.com 주문 객체만 제거했고 행안부 주소 API는 유지한다.
- 새 웹 입금은 같은 claim 작업을 보존하고 서버의 operationId·번호·견적 revision/지문·금액·확정시각을 대조한다. 이전 입금 번호도 그대로 사용한다. 이미 생성했거나 삭제 확인된 주문을 새 주문으로 다시 만들지 않는다. 보관된 공식견적서가 있으면 기존 발행 스냅샷을 재사용한다.
- 새 수기 주문은 기존 로컬 임시 번호를 보존하고 서버 전송 전 SOLD 번호와 연결한다. 주문 생성·수정과 대기는 Room 트랜잭션으로 묶는다. 실제 전송은 매매의 수동 동기화에서 수행한다. 기존 재고 전송 경로를 유지하며 판매 전송/수신에서 재고를 다시 차감하지 않는다.
- 매매 목록/상세에 서버 표시번호와 반영 상태를 표시한다. 전송대기 및 주문 상세의 ‘주문 연결 확인’에서 기존 서버 후보의 번호·시각·전화·금액·품목을 보고 선택한다. 서버 사본으로 바꾸기 전 원래 기기 주문·대기를 prefs에 보관한다. 새 수기 등록은 서버 전체 조회가 끝나고 유사 후보가 없을 때 두 번 확인한다.
- 수동 동기화는 커서 없는 숨김 변경도 놓치지 않도록 전체 읽기를 시작한다. 이미 진행 중인 전체 읽기는 확정 커서부터 재개하며, 한 번의 읽기는 최대 30페이지로 나눈다. 전송 후에도 한 번 더 읽는다. 도중 실패/충돌/상한은 완료로 표시하지 않는다. 기존 주문 연결 조회는 200페이지를 넘으면 새 등록을 허용하지 않는다.
- 제출한 주문 본문은 일괄 전송대기 비우기로 지우지 않는다. 결과 불명은 동일 작업으로 확인한다. 확정 거절된 작업은 주문 연결 선택에서 원본을 보관하고 정리할 수 있다. 미제출 대기를 사용자가 백업 후 비울 때는 ACK 없는 준비 연결만 정리하고 번호 예약 기록은 보존한다.
- 기존 NamuFarmPrefs·Room 백업 형식에 포함되므로 DB·백업 파일 형식 변경이 없다. 서버 실행 코드·나피·홈페이지·서명·기존 접수/판매 원문은 변경하지 않는다. 새 주문 정보 외의 기존 원문을 재작성하지 않는다.
- **제약:** 운영 서버 배포 버전은 미확인이다. 과거 암호화 판매 응답이나 자료형/지문 검증 실패는 해당 페이지를 보류한다. 원견적 만료·누락된 입금 시각, 출처 불명 과거 배송/취소 주문은 신규 주문으로 추측해 올리지 않는다. 기존 전송대기는 자동 일괄 이전하지 않는다. 이런 자료는 기존 주문 연결·백업 보존 대상으로 남는다.
- **미실행:** 컴파일·단위/자동/기기 시험·Actions·실제 주문 API 호출·문자/메일·홈페이지 게시·Apps Script 재배포. 신규 서버 코드 변경은 없으나 현재 API가 운영에 배포되지 않았다면 기존 배포 ID로 반영·설정 확인이 필요하다. 4단계 만료 행 정리는 착수하지 않았다.
