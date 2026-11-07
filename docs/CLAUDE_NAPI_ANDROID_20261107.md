# 나무팜 · Claude 구현 지시 · 나피/Android

사용자가 **구현·main 커밋까지** 승인했다. 아래 과거 검토문의 ‘수정·커밋 금지’는 이번 구현에는 적용하지 않는다. 실제 검증은 사용자가 한다.

## 분업과 작업 기준

- GPT: `server/quote-requests/`, `website/`, 이번 서버 API와 현황 기록. 서버 구현은 이 문서를 포함하는 main 커밋에 있다.
- Claude: `desktop/`, `app/`만 구현. 서버 API를 임의로 바꾸지 말고 계약이 부족하면 먼저 사용자에게 필요한 차이를 설명한다.
- 최신 main과 루트 AGENTS.md를 읽는다. 현재 문서 작성 기준 부모는 `782f463`이다. main에 반영된 GPT 커밋을 먼저 받는다. 새 브랜치·강제 push·병합 금지. 커밋 직전 main 변경을 다시 확인한다.
- 친구 한 명의 독립 사용이다. 기존 화면과 동기화 버튼을 유지한다. 복잡한 계정·농원 관리 UI를 추가하지 않는다.
- Cloudflare 이전/추가 도입 없음. 기존 Apps Script+Sheets, URL·배포 ID·인증, Android 패키지·DB·서명을 유지한다.
- 컴파일·자동시험·Actions·운영 시트 고객자료 열람·실제 접수/문자/메일·재배포·홈페이지 게시 금지. 코드 일관성만 확인한다. 커밋에 `[skip ci]` 필수.
- 나피 패치 버전은 0.5.15 → 0.5.16 (`__init__.py`, `pyproject.toml` 동시). Android는 1.1.40(43) → 1.1.41(44). 이후 main에서 이미 증가했다면 그 최신 값에서 한 단계 올린다. 빌드하지 않는다.

## 이번 범위

우선 웹 접수 UUID가 있는 확정견적을 두 기기에서 동일하게 처리한다. 기존 수기견적, 원가, 엑셀, 초안, 발송 이력, 미전송 변경은 보존한다. **서버 원본이 없는 수기견적을 가짜 고객 접수로 만들어 올리지 않는다.** 수기견적 통합 이전은 후속이다.

1. Android 주문 저장·전송대기 등록을 같은 Room 트랜잭션으로 묶는다.
2. 두 클라이언트의 확정목록에 서버 확정견적을 병합하고, 수정은 새 버전 API로 저장한다.
3. 같은 견적의 중복 발송·입금확인을 서버 계약으로 막는다.
4. 나피는 실행 중 1분마다 head만 조회하고 바뀔 때 동기화한다. Android는 화면 복귀/실행 때 확인한다.
5. 증분 결과는 병합한다. 하루 1회 및 수동 전체 동기화는 기존 스냅샷을 사용하되 부분 실패로 로컬 자료를 지우지 않는다.

FCM 수신부, 인증 교체, 기존 저장방식 제거, 만료 행 물리 삭제, 사업자 설정 전면 분리는 이번 클라이언트 작업에 넣지 않는다.

## 서버 기능 확인과 공통 규칙

공개 서비스 정보의 `confirmedQuoteVersion:1`, `confirmedQuotePayment:1`, `syncHead:1`을 확인한다. 버전 API는 현재 운영 방식인 **annual-two-tabs**에서만 활성화된다. 다른 저장 모드를 임의로 전환하지 않는다.

실제 서버 재배포 전에는 이 플래그가 없다. 이때 기존 동작과 로컬자료를 유지하며 새 버전 기능을 활성화하지 않는다. 새 계약으로 전환한 견적에는 구버전의 상태 변경·발송 계약이 `UPGRADE_REQUIRED`로 거부될 수 있다. **양쪽 클라이언트 준비 후, 발송/입금 작업을 잠시 멈추고 기존 배포 ID로 서버를 반영하는 순서**다. 이번에는 배포하지 않는다.

인증은 모든 새 POST에 기존 `deviceToken` 또는 기존 연결키를 그대로 보낸다. 고객용 `WEB_SECRET`을 앱에 넣지 않는다. 아래 표의 공통 `requestId`는 기존 접수 UUID다.

| action | 본문 | 응답/용도 |
|---|---|---|
| `sync_head` | 기존 인증만 | `requestSequence`, `saleSequence`, `inquirySequence` |
| `get_confirmed_quote` | `requestId` | `quote`에 기존 접수 응답과 선택적 `quoteVersion` |
| `revise_confirmed_quote` | `requestId`, `operationId`(UUID), `expectedRevision`, `quotePricing`, **`quoteDetails` 객체 필수** | `quote`, `appliedRevision`, `duplicate` |
| `reserve_quote_delivery` | `requestId`, `deliveryId`(UUID v4), `expectedRevision`, `quoteFingerprint`, `channel`(`sms`/`email`), `mode`(`server`/`external`) | `quote`, `duplicate`. external은 `payloadFingerprint`(SHA-256)도 필수 |
| `confirm_quote_delivery` | `requestId`, `deliveryId`, `expectedRevision`, `quoteFingerprint`, `confirmed:true` | 외부 앱에서 **사용자가 전송을 확인한 경우에만** 호출 |
| `cancel_quote_delivery` | 위 식별자·버전과 `notSent:true` | 아직 reserved이며 사용자가 실제로 안 보냈음을 확인한 작업만 취소 |
| `confirm_quote_payment` | `requestId`, `operationId`, `orderNumber`, `expectedRevision`, `quoteFingerprint`, `amount` | `quote`, `payment`, `duplicate`. 아직 판매 행 자체를 생성하는 API는 아님 |

`CONFLICT`면 서버 최신본을 다시 받고 로컬 초안은 보존한다. 자동 덮어쓰기 금지. `BUSY`/네트워크 오류/응답 불명은 같은 ID와 같은 본문으로 재확인한다. 서버 승인 전에 완료 표시·커서 전진·로컬 원본 삭제 금지.

### 버전과 내용

- `quote.quoteVersion`이 없으면 아직 이전하지 않은 견적이다. `quotePricing`이 있으면 기존 revision은 **1**, 없으면 **0**으로 새 수정 API에 전달한다.
- 새 API는 최초 확정도 처리한다. `save_quote_pricing`은 기존 최초 확정 계약 그대로이므로 수정용으로 반복 호출하지 않는다.
- 반환된 `quoteVersion.revision`, `fingerprint`를 그대로 저장한다. Android의 기존 로컬 revision/지문과 서버의 revision/지문은 별도 필드로 둔다. 로컬 알고리즘으로 서버 지문을 추정하지 않는다.
- 내용이 바뀌면 revision이 증가한다. `operationId`는 전송 전에 로컬에 저장하고 성공 응답을 잃어도 재사용한다. 같은 작업의 재요청은 중복 저장하지 않는다.
- `quotePricing` 형식은 기존과 같다: `items[{species,specification,quantity,unitPrice}]`, `isPrepaid`, `shippingFee`, `taxAmount`. 항목 1~50개, 기존 금액 상한 유지.
- `quoteDetails`는 판매자 수정 영역이다. 고객 원문 필드를 덮어쓰지 않는다. 전체 객체를 매번 보낸다. 저장된 항목을 읽어서 보존하고 수정한 값만 반영한다.

| quoteDetails 필드 | 형식/매핑 |
|---|---|
| `customerName`, `customerPhone`, `customerEmail` | 판매자가 수정한 고객 정보. 표시할 때 원문보다 우선 |
| `recipientName`, `region`, `notes` | 수신인, 지역, 판매자 메모. 원문 요청사항은 별도 보존 |
| `itemNotes` | 품목과 순서·개수가 같은 문자열 배열. 품목별 비고 |
| `issuer` | `businessName`, `businessNumber`, `representative`, `address`, `phone`, `email`, `businessType`, `category`, `fax` 중 사용하는 문자열 필드 |
| `validUntil`, `paymentAccount`, `documentNumber` | 유효기간·입금 안내·공식견적서 번호 |
| `issuedAt` | 발행 시각 epoch ms. 화면 열기마다 바꾸지 않는다 |
| `replyChannel`, `formalQuoteRequested` | `sms`/`email`/`both`, boolean |

받는 문서의 정보를 같은 방식으로 복원한다. Android·PC 내부 모델을 통째로 서버에 넣지 않는다. 원가·기기별 UI 상태는 각 로컬에서 보존한다. 타 기기 품목에 원가가 없으면 ‘원가 미확인’으로 남긴다.

현재본은 기존 견적 행 JSON, 과거 가격·상세는 운영 관리 장부의 `확정견적이력`에 저장된다. 이력은 현재본 저장 전에 기록되므로 중단된 수정은 원래 operationId로 재시도한다. 서버 이력만 있고 현재본 반영이 끝나지 않은 수정이 있으면 다른 수정은 BUSY가 될 수 있다. 미전송 수정 저널을 지우지 않는다.

### 수정 후 미발송과 발송 선점

`quoteVersion`에 `delivery`, `lastDelivered`, `payment`가 있다. 현재 revision/지문이 `lastDelivered`와 다르면 **수정 후 미발송**이다. 서버 상태는 `견적확정`으로 돌아가고 과거 전달 정보는 남는다. 단순히 예전의 `sent=true`를 가져와 현재본을 발송완료로 표시하지 않는다.

- 전송 직전 최신 견적을 저장하고 서버에서 받은 revision/지문으로 예약한다. BUSY면 ‘다른 기기에서 발송 처리 중’으로 안내한다.
- 같은 견적은 한 번에 한 발송 작업만 진행한다. 문자+메일은 순서대로 처리한다. 예약/발송/결과 불명은 시간 경과만으로 풀지 않는다.
- **나피의 서버 발송:** 예약 `mode:server` → 기존 `send_quote_sms`/`send_quote_email`에 기존 본문+동일 `deliveryId`, `expectedRevision`, `quoteFingerprint` 추가. 기존 PDF·메일·문자 양식을 유지한다.
- 메일은 서버 발송 성공 기록으로 버전에 연결된다. SMS의 `accepted:true`나 발송 저널의 SENT는 사업자 접수일 수 있다. 기존 `quote_sms_status` 호출에 **`requestId`, `deliveryId`, `messageId`를 모두** 보내 최종 성공을 반영한다. pending/unknown을 완료로 바꾸지 않는다.
- `quote_delivery_status`로 결과가 확인돼도 메시지를 다시 보내지 않는다. `found:false` 역시 자동으로 새 ID를 발급할 근거가 아니다.
- **Android 외부 문자/메일 앱:** 실제 보낼 텍스트/첨부 내용의 로컬 SHA-256을 `payloadFingerprint`로 예약(`mode:external`) → 외부 앱 실행 → 기존 ‘실제 전송 확인’에서만 `confirm_quote_delivery`. 돌아왔다고 자동 확인 금지.
- 예약 후 전송을 하지 않았다면 사용자 확인을 거쳐 `cancel_quote_delivery`를 호출할 수 있다. server sending/pending/unknown은 이 취소 API가 거부한다.
- 이전 발송 이력은 로컬에 계속 보존한다. 서버 이관만으로 과거 발송 성공을 만들어내지 않는다. 과거 발송본이 지금 내용과 같다는 것을 사용자가 확인한 경우에만 외부 발송 확인 절차로 연결하며 실제 재발송은 하지 않는다. 과거 실제 발송시각과 이번 확인시각은 구분한다.

### 이중 입금확인 방지

`update_request_status`에 입금확인을 그냥 추가하는 방식은 사용하지 않는다. 새 API가 같은 잠금 안에서 기존 `주문견적연결`까지 선점한다. 따라서 Android가 임시 입금확인만 기록해도 PC의 다른 주문번호로 `save_sale`이 거부된다.

1. 웹 견적의 입금확인 버튼에서 최신 서버 견적을 확인한다. 이미 `payment`가 있으면 새 주문을 만들지 않고 기존 주문번호를 보여준다.
2. 현재 revision과 전달 revision이 같아야 한다. 금액은 `Σ(수량×단가) + 선불배송비 + 세액`이다. 수정 후 미발송이면 새 견적 전달을 먼저 안내한다. 이전본 금액 선택 예외 UI는 이번에는 만들지 않는다.
3. 주문번호·operationId·견적 버전·금액·로컬 주문 생성용 스냅샷을 **호출 전에** 보존한다. 기존 주문번호 체계를 유지한다. SOLD 번호라면 기존 예약 API에서 먼저 확보한다.
4. `confirm_quote_payment` 성공 후 그 **동일 주문번호**로 로컬 주문을 생성한다. 응답이 끊기면 새 번호를 만들지 않고 같은 요청 또는 get API의 `payment`로 복구한다.
5. 나피의 실제 주문 업로드는 기존 `save_sale`을 사용한다. 새 웹 견적에는 `expectedRevision`, `quoteFingerprint`도 추가한다. 서버는 현재 견적과 주문의 품목/수량/단가/배송비/세액도 대조한다.
6. Android 판매 전체 연동을 이번에 완성하지 못해도 서버 입금확인과 공통 주문번호를 남긴다. 앱을 종료했다 돌아와도 성공한 서버 입금확인을 같은 주문으로 마무리한다. 서버에 성공했지만 로컬 저장이 실패한 상태를 새 입금확인으로 재처리하지 않는다.

온라인 확인이 필요한 **웹 견적 입금확인**만 오프라인 완료를 막는다. 기존 수기 매매 등 무관한 업무를 전부 서버 필수로 바꾸지 않는다.

### Android 로컬 트랜잭션

`ManagementViewModel.kt` 주문 확정 경로에서 `insertOrder`, 견적 삭제, 로컬 재고 반영, 해당 주문의 `enqueuePendingSync`를 **같은 실제 Room DB의 `withTransaction`**에 넣는다. 네트워크·메일·문자·전체 동기화는 트랜잭션 밖이다. 기존 전송대기 데이터를 비우지 않는다. 같은 주문 재개 시 중복 주문·중복 재고 차감이 없도록 기존 ID를 사용한다.

## 기존 자료 병합

전환 전: 나피 vault·확정 엑셀·초안·전송대기, Android DB·`quote_workflow_v1`·발송 이력을 기존 로컬백업 방식으로 보존한다. 운영 고객자료를 도구로 열어 검증하지 않는다. 코드는 사용자가 실제 업데이트할 때 백업/병합하도록 만든다.

- 같은 웹 `requestId`로 묶는다. 로컬 draft ID·확정일·발송 기록은 그대로 둔다. 같은 이름/전화/시간만으로 합치지 않는다.
- 내용이 같으면 서버 revision과 연결한다. 다른 로컬본은 삭제하거나 최신 시각으로 자동 선택하지 말고 **‘서버 내용 사용 / 이 기기의 수정본 저장’** 정도의 간단한 선택을 제공한다. 선택하지 않은 사본은 기존 보관함/백업에 남긴다.
- 서버에서만 확정된 견적도 기존 `new_draft` 계열 변환을 사용해 목록에 넣는다. `_pages inbox_only`로 서버 확정본을 배제하지 않는다. 단, 서버 원문만으로 단가를 꾸며 확정하지 않는다.
- 서버 새 버전으로 연결 성공하기 전에는 로컬 원본을 이관 완료로 표시하지 않는다. 기존 순수 로컬 수기견적과 만료된 원본을 억지로 서버에 덮어쓰지 않는다.
- **기존 30일 접수 보관 정책은 이번에 변경하지 않았다.** 오래된/만료된 서버 원본을 찾지 못해도 로컬 확정견적이나 엑셀을 자동 삭제하지 않는다. 기존 `deleted:true` 관리자 삭제 정책과 단순 `expired:true`를 구분한다. 장기 공유 보관은 후속 결정이다.

## 변경 확인과 증분 동기화

- `sync_head`는 Script Properties만 읽는 힌트다. 시트 조회·전역 잠금이 없으며, 서버 시트에 수동 편집한 내용은 이를 증가시키지 않는다.
- `requestSequence` 등은 **쓰기 시작 때 먼저 증가할 수 있다. head 값을 동기화 커서로 저장하지 않는다.** 실제 받아 영구 저장한 행의 순번만 커서로 확정한다. head가 앞서는데 행이 없으면 다음 확인 때 다시 받는다.
- 견적 증분은 기존 `list_requests` + `afterSequence`, `snapshot:false`. 100개 페이지의 `hasMore`를 따른다. 수신 순번이 전진하는지 확인하고 UUID 기준 병합한다. 낮은 순번의 오래된 응답이 새 버전을 덮지 않게 한다. 만료/삭제 표시도 전달된다.
- 증분 결과를 전체 목록으로 교체하지 않는다. 페이지 병합+커서 저장은 함께 원자적으로 처리한다. 실패하면 기존 목록·미전송 변경을 유지한다.
- 전체 동기화는 기존 `snapshot_requests` 계약(동일 snapshotId, total, watermark)을 유지한다. 중간 BUSY/오류면 임시 수신본만 폐기하고 로컬 원본을 보존한다. 서버는 아직 고정 스냅샷이나 만료행 물리 삭제 기능을 추가하지 않았다.
- 나피 head 타이머는 실행 중 1분, 작업 중이면 중첩 실행하지 않는다. 앱 실행/복귀, 하루 1회 전체 확인, 수동 동기화는 별도로 유지한다. Android는 실행/화면 복귀에 확인하고 백그라운드 1분 폴링은 추가하지 않는다.
- 문의/판매 API는 기존 형식을 유지한다. 해당 영역의 다운로드가 성공했을 때만 그 영역 head 확인값을 저장한다. 단순 head 변화만으로 변경분 지원이 없는 API에 afterSequence를 붙이지 않는다.

## 사용자에게 넘길 확인 항목

사용자가 확인할 내용만 적고 직접 실행하지 않는다: 양쪽 확정목록 인계, 휴대폰 수정 후 PC 금액·전화 갱신, 동시 수정 충돌, 수정 후 미발송, 동일 견적 발송 예약 충돌, Android 입금 후 PC 중복 차단, 응답 단절 후 같은 주문 복구, 기존 엑셀·DB·발송 이력·전송대기 보존.

완료 보고는 수정 핵심·버전·main SHA·미빌드/미배포·사용자 확인 항목만 간단히 한다. GPT와 같은 파일을 덮어쓰지 않도록 결과는 `docs/CLAUDE_CLIENT_RESULT_20261107.md`에 기록하고 main 최신을 조건으로 커밋한다.

## 최종 18항목 반영 구분

| 항목 | 이번 GPT 코드 / Claude 작업 / 후속 |
|---|---|
| 1 | 서버 버전·상세·이력 API 완료. 클라이언트 목록 병합·수정·기존자료 연결은 Claude |
| 2 | 서버 입금 선점·기존 save_sale 중복 차단 완료. 양쪽 버튼 연동은 Claude |
| 3 | 신규 견적 rateKey+전화 해시 제한, 같은 요청 재시도 제외 코드 완료 |
| 4 | 원본 행/삭제 기록 물리 이관은 후속. 현재 삭제 동기화 보존 |
| 5 | Android 트랜잭션은 Claude |
| 6 | 서버 sync_head 완료. 타이머/화면 복귀 연동은 Claude |
| 7 | 기존 증분 API 유지. 안전한 로컬 병합은 Claude |
| 8 | FCM 수신부는 후속. 우선 실행/복귀 head 확인 |
| 9 | 2탭 상태 변경 중복 조회 축소·조회 중 삭제 제거·알림 idle 조회 생략·시간별 정리 함수 완료. 현재/전년도만 조회하는 제한은 오래된 미완료 건 누락 때문에 적용 안 함 |
| 10 | 문의 실제 발송을 전역 잠금 밖으로 분리 완료 |
| 11 | STACKDRIVER·API 오류코드만 기록 완료 |
| 12 | 모르는 탭 유지/무시 완료. 알려진 업무 탭 헤더 검증은 유지 |
| 13 | 현재/다음 연도 장부 사전 준비 함수, HTTP 중 신규 연도 파일 생성 차단 완료 |
| 14 | 신규 기기 일회용 코드 도입은 후속. 기존 인증 유지 |
| 15 | 새 버전 견적의 쓰기에만 새 계약 요구. 전역 구형 계약 폐지·미사용 앱 함수 삭제는 후속 |
| 16 | 공개 서비스 응답 발신메일 제거 완료. shared-key 모드는 유지 |
| 17 | 운영 모드 제거 없음. 기존 모드/자료를 그대로 유지 |
| 18 | 판매자별 새 설정 UI는 후속. 견적별 발행자/계좌는 quoteDetails로 공유 가능 |

## 사용자가 나중에 적용할 서버 관리 작업

서버 커밋은 아직 배포가 아니다. 새 `.gs` 파일 두 개와 변경 파일 전체를 같은 프로젝트에 반영하고 **기존 배포 ID를 갱신**해야 API가 생긴다. URL·실행 주체·접근 설정은 유지한다.

- 편집기에서 `prepareCurrentYearLedgers`로 현재 연도 장부를 준비할 수 있다. 12월에는 `prepareNextYearLedgers`를 실행한다. 준비하지 않은 새 연도의 요청은 NOT_READY로 거부하며 고객 요청에서 Drive 파일을 만들지 않는다.
- 편집기에서 `configureHourlyCustomerCleanup`을 한 번 실행해야 설치된 5분 트리거가 1시간으로 바뀐다. 코드 배포만으로 기존 트리거 주기가 바뀌지는 않는다.
- 업무 탭 A:R(견적), A:T(주문), 문의 업무 헤더·ID·순번은 직접 편집하지 않는다. 필요하면 Sheets ‘데이터 → 시트 및 범위 보호’에서 편집 경고를 설정한다. 메모는 별도 탭에 쓸 수 있다. 헤더가 깨졌을 때 오류를 무시하고 다른 자료를 쓰는 방식은 적용하지 않았다.
- 이번 GPT 작업에서는 위 함수 실행, 운영 속성/시트/트리거 확인, 재배포를 하지 않았다.
