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

주문 품목 원본은 다음 9필드 summary 형식을 사용합니다.

```
species|quality|age|size|plantType|qty|retail|farm|wholesale
```

초기 7필드 포맷은 `OrderSummaryCodec`에서 읽기 호환합니다.

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
재고 Pull
   ↓
주문 Pull
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
원격 재고를 현재 스냅샷으로 보고 로컬 재고를 교체합니다.

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

Android는 pending ORDER 재시도 전에 동일 주문번호가 Sheet에 존재하는지 확인해 1차 방어합니다.

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

현재 농원/묘목 ID는 로컬 정수 자동증가이고 주문번호도 기기 로컬 순번입니다.

여러 폰에서 동시에 신규 데이터를 생성할 계획이면 서버 발급 ID 또는 UUID 전략이 필요합니다.

### D. UI

데이터 계층 안정화가 우선이며 UI 정리는 다음 단계에서 진행합니다.

현재 UI에 남아 있는 주문 영구삭제 버튼은 백엔드에서 실제 삭제가 차단되어 있고,
UI 보완 단계에서 버튼 자체를 제거할 예정입니다.

### E. 테스트

`OrderSummaryCodec`에는 현재/레거시 포맷 단위 테스트가 있습니다.

추가로 필요한 테스트:

- 주문확정 transaction rollback
- 주문취소 재고복원
- 동일 주문 2회 취소 idempotency
- pending 순서 중단
- Room v10/v11 → v12 migration

