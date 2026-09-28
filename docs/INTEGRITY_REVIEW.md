# NamuFarm local-first 무결성 검토

기준: main `134207cec3daf1e526e90c99731cf4a0c63164df` (58개 파일).
범위: Kotlin 데이터 계층, UI 입력/계산 경로, Room migration, Retrofit 계약, CI.
Apps Script 서버 소스는 저장소에 없음. 실제 사용자 Sheets에 테스트 쓰기를 수행하지 않음.

## 확인한 오류와 수정

| 위험 | 수정 위치/역할 | 변경 |
|---|---|---|
| 즉시 전송이 이전 pending을 추월 | NamuFarmRepository | 모든 쓰기를 같은 FIFO drain으로 통합, 첫 실패에서 중단 |
| 동시 동기화가 같은 작업을 중복 전송 | AppDatabase / LocalDataSource | DB 공유 네트워크 mutex. 로컬 transaction은 네트워크를 기다리지 않음 |
| Pull 도중 로컬 저장을 원격으로 덮어씀 | SyncManager | 적용 transaction 안에서 pending 재확인, 있으면 전체 중단 |
| 재고만 Pull 후 주문 Pull 실패 | SyncManager | 두 응답 검증 후 재고/주문을 하나의 transaction으로 반영 |
| 마스터 누락 행을 skip하면서 기존 재고 삭제 | SyncManager | 전체 실패로 처리하고 기존 재고 보존. 중복 키/누락 수량·가격/음수 데이터도 거부 |
| 같은 견적 반복 확정/중복 주문번호 | Repository / DAO | transaction에서 저장된 견적 존재·동일성·주문번호 확인 |
| 로컬 차감 품목과 전송 품목이 다를 수 있음 | Repository / SyncManager | 저장된 summary에서 일관되게 생성·검증, MANUAL 제외 |
| 수량/단가 파싱 실패를 0으로 저장 | OrderSummaryCodec | 정확한 7/9/10필드만 수용, 잘못된 수량·단가·출처·구분자 거부 |
| 정수 overflow로 잘못된 금액/복원 수량 | OrderAmounts / ViewModel / UI / Repository | Long 중간 계산과 저장 범위 검사, 재고 복원 addExact |
| 같은 번호만으로 전송 성공 판단 | SyncManager / OrderRemoteMapper | 원본 품목·고객·수량·금액 검증, 충돌/원본 누락이면 pending 유지 |
| 주소 지우기가 다른 기기에 전달되지 않음 | OrderRemoteMapper | 필드 누락(null)과 명시적 빈 문자열 구분 |
| 이전의 읽기용 summary가 영구 고착 | OrderRemoteMapper | 유효한 raw_summary/item_summary가 오면 구조화 원본 복원 |
| 취소 주문을 Pull로 활성화 | OrderRemoteMapper | 상태 역전 거부, 재고와 주문 모두 보존 |
| 재고/주문이 참조하는 이름 변경·농원 삭제 | MasterReferenceGuard | 복원 경로를 끊는 변경 거부. 상세정보 수정·미사용 마스터 작업은 유지 |
| 비활성 묘목 재고 유실/판매 노출 | DAO / SyncManager | 재고 매핑에는 비활성 포함, 판매 조회에서는 제외 |
| DB singleton 경쟁 | AppDatabase | synchronized 내부에서 instance 재확인 |
| ViewModel 검증 오류가 앱을 종료 | ManagementViewModel | 코루틴 오류 피드백과 CancellationException 재전파 |
| 견적 저장 연타, 저장 도중 바구니 변경 유실 | ManagementViewModel | 중복 저장 guard와 저장 snapshot 비교 후 바구니 비우기 |
| 기기별 순번 충돌 | ManagementViewModel | 새 견적/주문은 날짜 + UUID. 기존 번호는 유지 |
| 중복 전화 수신기, 전화번호 로그 | UI / MainActivity | 권한 확인 수신기 한 곳으로 통합, 원문 전화번호 로그 제거 |
| 한국어 local.properties 값 손상 | app/build.gradle.kts | UTF-8 reader로 설정 읽기 |
| HTTP 클라이언트의 암묵적 요청 재시도 | RetrofitClient | outbox 밖 자동 재시도 비활성화 |

## 호환성과 운영 영향

- DB 버전은 12 유지. 기존 데이터의 일괄 삭제/변환 없음.
- summary 쓰기는 9필드 유지, 기존 7필드 및 명시적 source 10필드 읽기 지원.
- 잘못된 기존 데이터는 숫자 0/실제 재고로 추정하지 않고 작업을 중단해 알려줌.
- 주문 영구삭제, 사진 기능 추가 없음. 수기 견적은 실제 재고와 분리 유지.
- 재고/견적/취소 가능한 주문에 참조되는 마스터의 이름 변경·농원 삭제에는 새 보호 조건 적용.
- 새 UUID 번호를 서버가 문자열로 그대로 보관해야 함. 실제 Apps Script의 번호 형식 제약은 소스 확인 필요.
- 레거시 서버가 읽기용 요약만 반환하면 응답 유실 주문을 안전하게 동일 주문으로 증명할 수 없어 pending이 남을 수 있음. 서버에서 원본 필드 제공 필요.

## 검증

추가한 테스트는 실제 Room을 사용하는 Robolectric 테스트와 Kotlin 단위 테스트로 구성.
주문/취소 중간 오류 rollback, 수기 혼합, 중복 확정/취소, FIFO, 응답 유실, 동시 sync,
느린 Pull 도중 저장, 잘못된 원격 스냅샷, v10/v11 migration과 Room schema validation을 다룸.

로컬 실행 환경은 Gradle 배포본 다운로드 시 `Network is unreachable`로 실행이 차단됨.
첫 수정 커밋 `d5c27026`은 [GitHub Android CI](https://github.com/ssanai91-hash/NamuFarm/actions/runs/36499593712)에서
`testDebugUnitTest assembleDebug` 성공: 테스트 49개, 실패 0, 오류 0, 건너뛰기 0.
추가로 원격 수량·가격 필드 누락과 첫 복원 전송 실패에 대한 회귀 테스트 2개를 보강해 총 51개이며,
최종 커밋의 실행 결과는 [PR #1 checks](https://github.com/ssanai91-hash/NamuFarm/pull/1/checks)에서 확인할 수 있음.
실폰, 사용자 Apps Script/Sheets와 종단 간 테스트는 미실행.

## 서버 소스가 있어야 완료할 과제

1. ORDER의 서버 원자적 멱등성: 주문행 존재 확인과 재고 차감을 같은 보호 범위에서 처리하고, 중간 실패 복구/응답 유실 재시도를 지원해야 함. Android 사전 GET만으로 보장 불가.
2. raw_summary(또는 item_summary), is_prepaid, shipping_fee, zip_no, road_address, detail_address의 저장/조회 대칭성. 앱 수신·병합은 보완했지만 서버 저장은 미검증.
3. farm/species 정수 ID의 기기 간 충돌. 기존 데이터와 외부 프로그램 연동이 있으므로 서버/UUID 전환 migration을 별도로 설계해야 함.
4. 여러 폰의 재고 동시 편집·판매·취소 충돌. 현재 절대 수량 INVENTORY 덮어쓰기는 다른 폰의 변경을 잃을 수 있으므로 서버 버전 검사 및 충돌 정책 필요.
5. 재고/주문 GET 두 응답의 서버 스냅샷 일관성. 이번 변경은 로컬 반영 원자성만 보장함.

필요 파일: 배포 중인 Apps Script의 `.gs` 전체와 실제 시트 헤더/API 응답 예시(개인정보·키 제외).
