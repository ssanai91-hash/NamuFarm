# NamuFarm Architecture

## 1. 프로그램 목적

NamuFarm은 단순 재고 앱이 아니라 다음 업무 흐름을 하나로 연결하는 현장용 판매 관리 앱입니다.

```
농원/묘목 마스터
      ↓
재고 등록 및 수정
      ↓
판매용 재고 조회
      ↓
견적 작성
      ↓
주문 확정
      ↓
재고 차감
      ↓
배송정보 관리
      ↓
입금확인 / 주문취소
      ↓
주문취소 시 재고 자동 복원
```

현장에서는 네트워크가 항상 안정적이지 않을 수 있으므로 **폰의 Room DB가 작업 중 기준 데이터**가 되고,
Google Sheets는 원격 공유/백업 및 수동 동기화 대상으로 사용합니다.

---

## 2. 레이어 구조

```
Compose UI
   ↓
ManagementViewModel
   ↓
NamuFarmRepository
   ├─ LocalDataSource
   │    └─ Room / ManagementDao
   └─ SyncManager
        └─ Retrofit / Google Apps Script / Google Sheets
```

### ManagementViewModel

화면 상태와 사용자 입력을 관리합니다.

DB 트랜잭션, pending 큐 순서, Google Sheet 저장 순서는 직접 다루지 않습니다.

### NamuFarmRepository

업무 규칙을 담당합니다.

- 농원/묘목/재고 저장
- 견적 → 주문 확정
- 주문취소
- 취소 재고복원
- 배송정보 저장
- pending 큐 처리
- 전체 동기화 순서

### LocalDataSource

Room 접근을 한 곳으로 모읍니다.

- DAO 호출
- Room transaction
- 로컬 조회/저장

### SyncManager

Google Sheet와의 통신 및 Pull/Push 변환을 담당합니다.

---

## 3. 주요 데이터

- `farm_info`: 농원 마스터
- `species`: 묘목 마스터
- `farm_inventory`: 농원/묘목/규격별 재고
- `quote_list`: 견적
- `order_list`: 주문
- `pending_sync`: 원격 전송 대기 작업

주문 품목 원본은 다음 9필드 summary 형식을 유지합니다.

```
species|quality|age|size|plantType|qty|retail|farm|wholesale
```

`OrderSummaryCodec`가 저장/파싱을 한 곳에서 담당하며 초기 7필드 포맷도 읽기 호환합니다.

빠른 견적은 실제 재고가 없는 수기 품목입니다. 수기 품목은 예약 farm 라벨 `수기견적`으로 구분하고
주문확정/주문취소 때 재고 차감·복원 대상에서 제외합니다.

---

## 4. Local-first 저장 규칙

원격 동기화 대상 데이터를 변경할 때는:

1. 로컬 데이터 변경
2. 같은 Room transaction 안에서 `pending_sync` 등록
3. transaction commit
4. Google Sheet 즉시 전송
5. 성공 시 pending 삭제
6. 실패 시 pending 유지

따라서 앱이 전송 직전 종료되더라도 **로컬 변경만 있고 pending이 없는 상태**를 최대한 만들지 않습니다.

---

## 5. 전체 동기화 순서

```
pending 1 전송
   ↓ 성공
pending 2 전송
   ↓ 성공
...
   ↓ 전부 성공
재고 + 주문 응답 모두 수신
   ↓
같은 Room transaction 안에서 pending 재확인 + 전체 데이터 검증
   ↓
재고 교체 + 주문 병합 함께 commit
   ↓
화면 상태 갱신
```

pending 처리 중 하나라도 실패하면 즉시 멈추고 Pull을 하지 않습니다.

이 규칙은 특히 다음 복합 작업 순서를 지킵니다.

```
주문취소
  ↓
재고복원 save
```

취소 전송이 실패했는데 재고복원만 먼저 원격에 반영되는 모순을 방지합니다.

---

## 6. 주문 확정

견적을 주문으로 바꾸는 로컬 작업은 하나의 Room transaction입니다.

```
order_list INSERT
quote_list DELETE
farm_inventory 차감
pending_sync ORDER INSERT
```

중간에 하나라도 실패하면 전체 transaction이 rollback됩니다.

주문 품목 파싱 오류나 재고 부족도 주문확정 실패로 처리합니다.

---

## 7. 주문취소와 재고 자동복원

현재 주문 상태:

- `입금확인`
- `주문취소`

취소 처리 로컬 transaction:

```
현재 주문상태 확인
   ↓
이미 주문취소면 종료
   ↓
원본 item_summary 파싱
   ↓
각 품목 재고 + 주문수량
   ↓
CANCEL_ORDER pending 등록
   ↓
복원 재고 INVENTORY pending 등록
   ↓
order.status = 주문취소
```

주문 당시 재고가 0이 되어 해당 재고행이 삭제되어 있더라도,
주문 원본의 농원/묘목/규격/가격 정보를 이용해 재고행을 다시 만듭니다.

원격 즉시 전송도 반드시:

1. 주문취소
2. 복원 재고 save

순서로 시도합니다.

같은 주문을 다시 취소하면 상태 검사를 통해 재고를 두 번 복원하지 않습니다.

취소된 주문을 다시 `입금확인`으로 되돌리는 기능은 현재 지원하지 않습니다.
이 기능은 재고를 다시 차감해야 하므로 별도의 업무 규칙으로 구현해야 합니다.

---

## 8. 마스터/재고/주문 Pull

### 농원 / 묘목

전체 테이블을 지우지 않고 원격 항목을 upsert 합니다.

또한 아직 pending인 로컬 농원/묘목 ID는 원격 Pull로 덮어쓰지 않습니다.

### 재고

일반 전체 동기화에서는 pending이 전부 성공한 뒤에만 수행됩니다.
원격 재고를 현재 스냅샷으로 봅니다. 재고와 주문 응답을 모두 받은 뒤 하나의 Room transaction으로 반영합니다.
네트워크 대기 중 발생한 로컬 변경의 pending이 있으면 전체 적용을 중단합니다.
마스터 누락/중복, 중복 재고 키, 잘못된 수량/금액이 있으면 기존 데이터를 보존합니다.
비활성 묘목도 재고 매핑에 포함하지만 판매 목록에서는 숨깁니다.

재고 Pull 과정에서 없는 농원/묘목 마스터를 임의 생성하지 않습니다.

### 주문

같은 주문번호의 로컬 주문을 삭제/재삽입하지 않습니다.

기존 주문의:

- 원본 `item_summary`
- 배송비/선불여부
- 구조화된 주소

를 보존하면서 서버에서 안전하게 가져올 수 있는 값만 병합합니다.

---

## 9. Room 마이그레이션

현재 DB 버전은 12입니다.

- v1~v6 → v7
- v7 → v8
- v8 → v9
- v9 → v10
- v10 → v12
- v11 → v12

데이터 보존 migration을 사용하며 destructive fallback은 사용하지 않습니다.

DB 변경은 반드시:

1. Entity 수정
2. DB version 증가
3. Migration 작성
4. 구버전 DB 업그레이드 테스트

를 한 세트로 처리합니다.

---

## 10. 남은 핵심 과제

### A. Apps Script 주문 idempotency

Android는 ORDER 전송 전 동일 주문번호를 조회하며, 재전송 시 원본 품목·고객·수량·금액까지 일치해야 기존 전송의 성공으로 인정합니다.
불일치/원본 누락/조회 실패 시 pending을 보존합니다. 이 검사는 서버의 원자적 멱등 처리를 대체하지 않습니다.
Apps Script 소스는 이 저장소에 없어 서버 구현 및 배포는 검증하지 않았습니다.

최종 방어는 Apps Script에서도 반드시 해야 합니다.

- 같은 `order_number`가 이미 있으면 주문 행 append 금지
- 같은 주문 때문에 재고를 두 번 차감하지 않기

### B. 주문 데이터 완전 왕복

다른 기기에서도 주문을 100% 복원하려면 Sheet/API 규격에 다음 구조화 필드를 포함하는 것이 좋습니다.

- 원본 `item_summary`
- `is_prepaid`
- `shipping_fee`
- `zip_no`
- `road_address`
- `detail_address`

### C. 다중 기기 ID

농원/묘목 ID는 로컬 정수 자동증가를 유지합니다. 새 견적/주문번호는 날짜 + UUID이며, 기존 번호는 변경하지 않습니다.

여러 폰에서 동시에 신규 데이터를 생성할 계획이면 서버 발급 ID 또는 UUID 전략이 필요합니다.

### D. UI

판매/관리/빠른견적/오늘견적/오늘주문/배송정보 화면은 공통 Material 테마로 정리되었습니다.

주문 영구삭제 UI와 동작은 제거했고 주문은 상태 이력으로만 관리합니다.
사진 기능은 현재 제품 범위에서 제외합니다.

### E. 테스트

`OrderSummaryCodec`에는 현재/레거시 포맷 단위 테스트가 있습니다.

테스트는 `bash gradlew testDebugUnitTest assembleDebug`로 실행합니다.

- 현재/레거시 summary, 수기 품목, 잘못된 출처/구분자/수량/단가
- 배송비 포함 합계와 Int 저장 범위 초과 방어
- Room transaction 중간 오류 rollback, 재고 부족, 견적 중복 확정
- 수기/실재고 혼합 주문, 품절 재고 복원, 두 번 취소, 복원 실패 rollback
- pending 순서, 실패 시 Pull 중단, 응답 유실, 주문번호 충돌
- 동시 동기화, 느린 Pull 도중 로컬 저장, 코루틴 취소
- 원격 주소 빈 값/필드 누락, 원본 복원, 취소 주문 역전 방지
- 실제 Room 스키마 검증을 포함한 v10/v11 → v12 migration

Room 통합 테스트는 Robolectric에서 실행하며, 실폰 및 실제 Apps Script 종단 간 테스트는 별도로 필요합니다.

## 11. 이름 기반 재고 참조 보호

summary 호환 때문에 주문의 재고 연결은 여전히 농원명/묘목명에 의존합니다.
재고가 연결된 마스터 이름 변경, 견적/취소 가능한 주문이 연결된 농원 삭제·이름 변경은 오류로 막습니다.
주소·연락처 수정, 미사용 마스터 변경/삭제, 묘목 비활성화는 계속 지원합니다.
이 보호는 정상 주문의 재고 복원이 불가능해지는 것을 방지하며, 장기적으로는 안정된 ID 참조로 바꿔야 합니다.

## 12. 아직 보장하지 않는 원격 동시성

이 수정은 한 Room DB를 공유하는 작업의 순서를 보장합니다.
두 폰의 동시 판매나 재고 수동 수정/취소가 만드는 서버 충돌을 해결하지는 않습니다.
특히 INVENTORY는 절대 수량 덮어쓰기이므로, 다중 기기 운영에는 서버 재고 version/충돌 처리와
멱등한 증감 ledger 등 API 계약 변경이 필요합니다. 두 GET 응답 또한 서버 측 원자적 스냅샷은 아닙니다.

