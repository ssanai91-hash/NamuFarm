# NamuFarm

나무팜은 묘목 농원의 **재고 → 견적 → 주문 → 배송정보 → 주문상태** 흐름을 한 번에 관리하기 위한 Android 앱입니다.

## 핵심 목적

- 농원별 / 묘목별 재고 관리
- 규격(품질, 연생, 크기, 재배타입)별 수량과 원가/판매가 관리
- 전화번호를 기준으로 빠른 견적 작성
- 견적을 주문으로 확정하면서 재고 차감
- 주문취소 시 차감 재고 자동 복원
- 주문상태를 `입금확인` / `주문취소`로 보존
- 배송정보 저장
- 인터넷이 불안정해도 로컬 DB에서 계속 작업
- Google Sheets와 수동 동기화하여 백업/공유

## 현재 구조

```
Compose UI
    ↓
ManagementViewModel
    ↓
NamuFarmRepository
    ├─ LocalDataSource
    │    └─ Room / ManagementDao
    └─ SyncManager
         └─ Retrofit / Google Apps Script
```

앱은 **local-first** 방식입니다.

1. 사용자 변경사항과 `pending_sync` 기록을 Room 트랜잭션으로 함께 저장
2. 기존 pending부터 순서대로 Google Sheet 전송 시도
3. 성공하면 pending 삭제
4. 실패하면 pending 유지
5. 다음 동기화 때 pending을 순서대로 재전송
6. 앞선 pending이 하나라도 실패하면 뒤 작업 및 Pull을 중단
7. pending이 모두 처리된 뒤 재고/주문을 받아 검증하고 함께 로컬 반영

자세한 설계와 동기화 규칙은 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)를 참고하세요.

## 로컬 설정

실제 API 값은 Git에 올리지 않고 프로젝트 루트의 `local.properties`에 둡니다.

```properties
JUSO_API_KEY=...
GOOGLE_SCRIPT_URL=https://script.google.com/macros/s/.../
PAYMENT_ACCOUNT_TEXT=은행 계좌번호 예금주
```

`local.properties`는 `.gitignore`에 포함되어 있습니다.

## 빠른 견적

빠른 견적에서 만든 품목은 실제 재고와 분리된 **수기 견적 품목**으로 저장됩니다.

- 견적 저장/주문 전환 가능
- Google Sheet 주문 이력에는 포함
- 실제 농원 재고 차감은 하지 않음
- 주문취소 시 실제 재고 복원도 하지 않음

실제 판매재고에서 고른 품목만 주문확정 시 재고가 차감되고, 주문취소 시 자동 복원됩니다.

## 주문 상태 정책

현재 업무 상태는 두 가지만 사용합니다.

- `입금확인`
- `주문취소`

주문취소 시 주문 이력은 남기고, 해당 주문이 차감했던 재고는 로컬에서 자동 복원한 뒤
복원된 재고를 Google Sheet에 다시 동기화합니다.

같은 주문을 여러 번 취소해도 재고가 중복 복원되지 않도록 현재 주문상태를 확인합니다.

주문 기록은 회계/업무 이력으로 남기기 때문에 앱의 핵심 업무 흐름에서는 **영구삭제하지 않습니다**.

## 개발 원칙

데이터 무결성, 오프라인 동작, 동기화 안정성을 우선하고 UI에서는 죽은 버튼이나 가짜 데이터 경로를 두지 않습니다.

사진 기능은 현재 범위에서 제외합니다.

## 무결성 검증

`bash gradlew testDebugUnitTest assembleDebug`로 summary/금액 계산 단위 테스트와
Robolectric 기반 실제 Room transaction·동기화·migration 회귀 테스트를 실행합니다.

새 견적/주문번호는 날짜와 UUID를 사용합니다. 기존 번호와 DB v12는 유지합니다.
수정 범위와 서버 측 남은 과제는 [검토 보고서](docs/INTEGRITY_REVIEW.md)를 참고하세요.
