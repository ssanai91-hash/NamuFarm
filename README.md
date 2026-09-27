# NamuFarm

나무팜은 묘목 농원의 **재고 → 견적 → 주문 → 배송정보 → 주문상태** 흐름을 한 번에 관리하기 위한 Android 앱입니다.

## 핵심 목적

- 농원별 / 묘목별 재고 관리
- 규격(품질, 연생, 크기, 재배타입)별 수량과 원가/판매가 관리
- 전화번호를 기준으로 빠른 견적 작성
- 견적을 주문으로 확정하면서 재고 차감
- 주문상태를 `입금확인` / `주문취소`로 보존
- 배송정보 저장
- 인터넷이 불안정해도 로컬 DB에서 계속 작업
- Google Sheets와 수동 동기화하여 백업/공유

## 현재 구조

```
Compose UI
    ↓
ManagementViewModel
    ├─ Room / ManagementDao
    └─ SyncManager
         └─ Retrofit / Google Apps Script
```

앱은 **local-first** 방식입니다.

1. 사용자 변경사항을 Room DB에 먼저 저장
2. `pending_sync`에 전송 작업을 기록
3. Google Sheet 전송 즉시 시도
4. 실패하면 pending 항목을 남김
5. 다음 동기화 때 pending을 먼저 재전송
6. pending이 모두 처리된 뒤 원격 데이터를 Pull

자세한 설계와 동기화 규칙은 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)를 참고하세요.

## 로컬 설정

실제 API 값은 Git에 올리지 않고 프로젝트 루트의 `local.properties`에 둡니다.

```properties
JUSO_API_KEY=...
GOOGLE_SCRIPT_URL=https://script.google.com/macros/s/.../
```

`local.properties`는 `.gitignore`에 포함되어 있습니다.

## 주문 상태 정책

현재 업무 상태는 두 가지만 사용합니다.

- `입금확인`
- `주문취소`

주문 기록은 회계/업무 이력으로 남기기 때문에 앱의 핵심 업무 흐름에서는 **영구삭제하지 않습니다**.

## 개발 원칙

UI 개선 전에는 데이터 무결성, 오프라인 동작, 동기화 안정성을 우선합니다.
