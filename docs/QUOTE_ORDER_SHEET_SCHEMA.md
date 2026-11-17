# 견적·주문 장부 통합 스키마

기준: main `6d5b18a7492330f9cef931be030ea739aa1007c6`, 2026-11-16.
현재 연도별 장부의 저장 형식을 설명하는 문서다. 새 열·탭·상태를 도입하거나 운영 데이터를 변경하지 않는다.

## 파일과 탭

| 용도 | 파일명 | 업무 저장 탭 | 헤더 범위 | 저장 위치 기준 |
| --- | --- | --- | --- | --- |
| 견적 | `나무팜_견적_YYYY` | `견적요청`, `견적확정` | 각 `A1:R1` (18열) | 최초 접수시각의 한국 연도. 확정 시 같은 연도의 확정 탭으로 이동 |
| 주문 | `나무팜_주문_YYYY` | `01월`~`12월` | 각 `A1:T1` (20열) | 신규 주문의 최초 입금확인 시각(`paidAt`)의 한국 연·월. 이후 수정 시 원래 위치 유지 |

- 실제 확인한 파일명은 `나무팜_견적_2026`, `나무팜_주문_2026`이다. 두 파일의 시간대는 `Asia/Seoul`이다.
- 1행은 헤더, 데이터는 2행부터다. 시트 그리드의 추가 빈 열은 API 저장 열에 포함하지 않는다.
- 파일명은 표시용이다. 서버는 기존 Script Properties의 장부 레지스트리와 파일 ID로 접근하므로 이름만으로 새 파일을 만들거나 연결을 바꾸지 않는다.
- 연도별 견적 파일에 월별 탭을 추가하지 않는다. 주문 파일에는 업무 저장용 월별 12개 탭을 사용한다. 소유자가 만든 메모 탭은 보존한다.
- `연도별장부파일`, `주문견적연결`, `판매변경이력`, 발송·번호 예약 등 관리 자료는 별도 운영 장부/기존 속성의 영역이다. 이 문서의 18열·20열을 관리 탭에 적용하지 않는다.
- 구 `웹견적요청`, `판매기록`과 과거 월별·암호화 저장 형식은 호환 경로다. 현재 연도별 장부를 그 형식으로 되돌리지 않는다.

코드 근거: [AnnualLedgers.gs](../server/quote-requests/AnnualLedgers.gs)의 `annualFileEntries_`, `annualLedgerBook_`, `annualEnsureTabs_`, `annualActiveBook_`, `newSaleSheet_`, `saleStorageSheets_`; [TwoTabQuotes.gs](../server/quote-requests/TwoTabQuotes.gs)의 `TWO_REQUEST_TAB`, `TWO_CONFIRMED_TAB`, `twoTabMoveConfirmed_`.

## 자료형·필수 여부의 해석

- 아래 자료형은 **서버가 해석하는 논리 자료형**이다. 셀의 숫자·불리언이 텍스트로 저장된 경우가 있으며, 날짜도 ISO 문자열이다. 문서에 맞춘다는 이유로 셀 서식이나 기존 값을 일괄 변환하지 않는다.
- **필수**는 신규 protocol v5 정상 행에 값이 있어야 한다는 의미다. `0`과 `false`는 유효값이며 빈칸과 다르다. **선택**은 빈 문자열을 허용한다. **조건부**는 설명의 상태에 따른다.
- 숫자는 별도 표시가 없으면 JavaScript 안전 정수 범위다. 금액은 원 단위 정수, 수수료율은 bp(1bp = 0.01%)다.
- 헤더 문자열과 열 순서는 코드 상수 그대로 보존한다. 고객 텍스트는 `cell_`로 수식 실행을 방지할 수 있다. 읽은 표시값을 고객 원문의 대체값으로 저장하지 않는다.

## 견적: `견적요청`, `견적확정` 공통 18열

헤더는 [Code.gs](../server/quote-requests/Code.gs)의 `RFQ_HEADERS`(6행)와 `RFQ_RECEIPT_HEADERS`(9행)를 이어 붙인 순서다. `RFQ_COL`은 앞 16열의 0 기반 인덱스를 정의한다.

| 열 | 헤더 | 논리 자료형 | 필수 여부 | 의미·허용값 |
| --- | --- | --- | --- | --- |
| A / 1 | 요청번호 | 문자열(UUID) | 필수 | API `requestId`. 화면의 `NAMU-...` 접수번호와 다른 내부 식별자 |
| B / 2 | 접수순번 | 양의 정수 | 필수 | 변경 커서용 전역 순번. 상태 변경 때도 증가하므로 최초 접수 순서 고정값이 아님 |
| C / 3 | 접수시각(UTC) | ISO 8601 문자열 | 필수 | 최초 접수시각, 예: `YYYY-MM-DDTHH:mm:ss.sssZ` |
| D / 4 | 성함 | 문자열 | 필수 | 신규 v5 고객명. 만료·삭제 후 또는 과거 암호화 행은 빈칸 가능 |
| E / 5 | 연락처 | 문자열 | 필수 | 앞자리 0 보존. 신규 v5는 `0`으로 시작하는 9~11자리 전화번호. 만료·삭제/과거 암호화 행은 빈칸 가능 |
| F / 6 | 배송지역 | 문자열 | 선택 | `request.region`; 만료·삭제 시 비움 |
| G / 7 | 희망묘목 | 문자열 | 필수 | 품목명·수량 표시 요약, 서버에서 생성. 만료·삭제/과거 암호화 행은 빈칸 가능 |
| H / 8 | 품목JSON | JSON 배열 문자열 | 필수 | `[{species, specification, quantity}, ...]`. 수량은 양의 정수. 만료·삭제/과거 암호화 행은 빈칸 가능 |
| I / 9 | 요청사항 | 문자열 | 선택 | `request.message`; 만료·삭제 시 비움 |
| J / 10 | 상태 | 문자열 | 필수 | `신규`, `견적확정`, `견적발송완료`, `입금확인`, `배송완료`, `구매취소`, `보관종료` 등. 기존 호환 `주문취소`도 코드에서 읽음 |
| K / 11 | 알림상태 | 문자열 | 필수 | 접수 푸시 알림의 `PENDING`, `SENT`, `STOPPED`. 고객 견적 문자·메일 발송 완료 여부와 다름 |
| L / 12 | 내용해시 | 64자리 16진 문자열 | 필수 | 접수 `fingerprint`. 같은 요청ID 재시도의 내용 일치 확인에 사용 |
| M / 13 | 알림시도 | 0 이상 정수 | 필수 | 접수 푸시 시도 횟수, 최초 `0` |
| N / 14 | 다음알림(UTC) | ISO 8601 문자열 | 필수 | 다음 푸시 시각. 처리 중에는 중복 실행 방지용 임시 점유 만료시각으로도 사용 |
| O / 15 | 오류코드 | 문자열 | 선택 | 접수 푸시 오류 코드. 정상·보관종료 시 빈칸 가능 |
| P / 16 | 원본JSON | JSON 객체 문자열 | 필수 | 정상 v5 `{request, ...}`. 확정 가격·버전 등이 추가될 수 있음. 과거 `{encryptedRequest, ...}` 호환, 만료 시 `{expired:true}`, 명시 삭제 시 `deleted:true` 추가 |
| Q / 17 | 견적접수번호 | 문자열 | 필수 | 신규 `NAMU-YYYYMMDD-NNNN`(일별 순번 최소 4자리). 기존 `NFQ-...` 번호는 보존 |
| R / 18 | 접수시각(한국) | 날짜·시각 문자열 | 필수 | C의 한국 시각 `YYYY-MM-DD HH:mm:ss`. C와 9시간 차이인 문자열 |

저장·검증 근거: `Code.gs`의 `validateRequest_`, `cell_`, `receiptTime_`, `receiptCounter_`, `receiptDetails_`; [SecureCommerce.gs](../server/quote-requests/SecureCommerce.gs)의 `quoteCustomerCells_`, `quoteStoredData_`, `commitQuoteRequest_`, `quoteResponseRow_`; `TwoTabQuotes.gs`의 `twoTabSubmit_`, `twoTabRecords_`, `twoTabClose_`.

- `견적요청`은 신규 접수, `견적확정`은 확정 이후 이력을 저장한다. 입금·배송 상태로 바뀌어도 확정 탭을 다른 이름으로 바꾸지 않는다.
- 만료·삭제 행은 정상 고객 행과 다르다. D:I를 비우고 J=`보관종료`, K=`STOPPED`, O를 비우며 P에 만료/삭제 표식을 남긴다. A:C·L:N·Q:R 등 식별·커서·접수 정보는 현재 코드에서 남는다.
- 기본 만료 처리는 행 자체를 삭제하지 않는다. 4단계의 별도 소유자 물리 정리는 기본 꺼짐이며 종료 식별 정보를 운영 관리 장부에 검증 보존한 후에만 실행한다. 운영 활성화/실행은 미완료다.

## 주문: `01월`~`12월` 공통 20열

헤더는 [SecureCommerce.gs](../server/quote-requests/SecureCommerce.gs)의 `SALE_HEADERS`(3행)다. 정상 행은 `saveSale_`의 `row` 배열 순서로 기록한다.

| 열 | 헤더 | 논리 자료형 | 필수 여부 | 의미·허용값 |
| --- | --- | --- | --- | --- |
| A / 1 | 주문번호 | 문자열 | 필수 | 서버가 예약한 신규 `SOLD-YYYYMMDD-NNNN` 계열. API 주문 식별자이며 기존 번호는 재발급하지 않음 |
| B / 2 | 변경순번 | 양의 정수 | 필수 | 판매 변경 커서. `revision`이나 행 번호와 다름 |
| C / 3 | 상태 | 문자열 | 필수 | 현재 업무 `입금확인`, `배송완료`, `구매취소`. 호환 코드에는 `구매확정`, `배송확인`, `판매완료`, `주문취소`도 남아 있음 |
| D / 4 | 구매시각(UTC) | ISO 8601 문자열 | 필수 | `metadata.purchasedAt`(epoch ms)을 ISO로 변환. 장부 월 배정용 `paidAt`과 구분 |
| E / 5 | 판매완료(UTC) | ISO 8601 문자열 | 조건부 | `metadata.completedAt > 0`이면 기록, 미완료는 빈 문자열 |
| F / 6 | 수량 | 양의 정수 | 필수 | `totalQty`; 품목 수량 합계 |
| G / 7 | 매출(세액제외) | 0 이상 정수 | 필수 | `totalPrice`; 품목 공급가 합계와 선불인 경우 배송비를 포함 |
| H / 8 | 세액 | 0 이상 정수 | 필수 | `taxAmount` |
| I / 9 | 원가 | 0 이상 정수 | 필수 | `costTotal` |
| J / 10 | 비용 | 0 이상 정수 | 필수 | `expenseTotal` |
| K / 11 | 원가확인 | 불리언 | 필수 | `costConfirmed`. 셀에 `true/false` 텍스트로 남은 호환 행도 있음 |
| L / 12 | 수익방식 | 문자열 | 필수 | `profitMode`: `resale` 또는 `commission` |
| M / 13 | 수수료비율(bp) | 0~10000 정수 | 필수 | `commissionBps`; `10000` = 100% |
| N / 14 | 매출이익 | 정수 또는 빈칸 | 조건부 | 원가 미확인 시 `null`→빈칸. 원가 확인 후 `profit_(metadata)` 계산값이며 음수 가능 |
| O / 15 | 보관기한(UTC) | ISO 8601 문자열 | 필수 | `retentionUntil_` 계산값. 취소는 변경시각, 그 외 완료시각 또는 구매시각에서 UTC 연도 +5 |
| P / 16 | 공개판매JSON | JSON 객체 문자열 | 필수 | `metadata` 전체. 품목·금액·revision·시각 포함. 헤더의 ‘공개’는 인터넷 공개를 의미하지 않음 |
| Q / 17 | 고객정보JSON | JSON 객체 문자열 | 필수 | 신규 v5 `{personalData:{order, quoteDocumentJson?}}`. 고객·수령인·연락처·주소·견적 연결 포함. 과거 암호화 envelope도 호환 |
| R / 18 | 내용해시 | 64자리 16진 문자열 | 필수 | `saleDigest_`로 계산하는 `fingerprint`. 판매 metadata와 고객정보에 대한 내용 지문 |
| S / 19 | 최종변경번호 | 문자열(UUID) | 필수 | `save_sale.eventId`. 재시도는 같은 작업ID·내용·revision을 사용 |
| T / 20 | revision | 양의 정수 | 필수 | 신규 `1`, 실제 후속 수정은 이전값 +1. 같은 작업 재시도는 증가시키지 않음 |

저장·검증 근거: `SecureCommerce.gs`의 `saleMetadata_`, `salePersonal_`, `saleDigest_`, `saveSale_`, `profit_`, `retentionUntil_`, `listSales_`, `purgeExpiredCustomerData`; `AnnualLedgers.gs`의 `newSaleSheet_`, `reserveSaleSource_`.

- 구매확정과 입금확인은 **동일한 업무 상태**다. 호환 상태 문자열이 남아 있어도 새 구매확정 단계를 만들지 않는다.
- `공개판매JSON`의 시각 필드는 epoch 밀리초 정수다. 미발생 시각은 `0`을 사용할 수 있으며, 필수 키·상태별 관계는 `saleMetadata_`를 따른다. 시트의 D:E·O는 ISO 문자열이다.
- `고객정보JSON`의 `personalData.order.sourceRequestId`는 견적 A열 UUID, `sourceQuoteNumber`는 표시용 견적접수번호다. 견적 없는 직접 주문의 연결값은 빈 문자열을 허용한다. 견적-주문 연결은 기존 관리 장부·API에서 처리하며 월별 주문 탭에 새 연결 열을 만들지 않는다.
- 보관기한 후 정리된 판매 행은 정상 행의 예외다. A는 원주문번호 대신 해시, D·Q·R·S는 빈칸, T는 `0`, P는 `{archived:true, ...집계 필드}`가 된다. 재무 집계값은 남는다. 이 예외를 정상 판매 수정이나 신규 주문으로 재전송하지 않는다.

## 실제 헤더 대조 기록

2026-11-16에 연결된 Google Sheets 도구로 다음 범위의 **첫 행만 읽어** 코드 상수와 열 이름·순서를 대조했다. 고객 데이터 행은 읽거나 쓰지 않았다.

| 파일 | 조회 범위 | 코드 기준 | 대조 결과 |
| --- | --- | --- | --- |
| `나무팜_견적_2026` | `견적요청!A1:R1`, `견적확정!A1:R1` | `RFQ_HEADERS.concat(RFQ_RECEIPT_HEADERS)` | 두 탭 모두 18열 일치 |
| `나무팜_주문_2026` | `01월!A1:T1`~`12월!A1:T1` (각 월의 첫 행) | `SALE_HEADERS` | 12개 탭 모두 20열 일치 |

자료형·필수 여부는 고객 행을 표본 추출한 결과가 아니라 위 서버 코드의 검증·저장·만료 처리 규칙에서 정리했다. 이 확인은 다른 연도 장부, 운영 Apps Script 배포 버전, 실제 주문 동작까지 검증한 결과가 아니다.

## 유지 규칙

1. 업무 데이터 변경은 기존 Apps Script API를 사용한다. 에이전트·MCP·수동 시트 쓰기로 잠금, 지문, 작업ID, revision, 견적-주문 연결을 우회하지 않는다.
2. 코드 상수를 바꾸는 승인된 작업이 있을 때 이 문서와 기존 클라이언트 호환성도 함께 검토한다. 헤더만 임의로 바꾸면 서버는 `NOT_READY`로 중단할 수 있다.
3. 장부 위치·열 순서 확인과 운영 서버 배포 여부는 분리한다. 기존 파일 ID·배포 ID·URL·인증 설정은 이 문서 작성으로 바뀌지 않는다.


## 내부 종료 색인: 기존 운영 관리 장부 `견적종료색인`

4단계 소스에 추가했으며 **운영 생성/데이터 이관/정리 실행은 하지 않았다.** 견적 연도 파일의 제3 업무 탭이 아니다. 위치는 `LEGACY_SPREADSHEET_ID || SPREADSHEET_ID`인 기존 관리 장부다. 현재 annual-two-tabs에서만 사용한다. 코드 상수/검증/저장은 `server/quote-requests/QuoteCompaction.gs`의 `QUOTE_CLOSED_TAB`, `QUOTE_CLOSED_HEADERS`, `quoteClosedCheck_`, `quoteClosedPut_`다.

| 열 | 헤더 | 논리 자료형 | 필수 | 의미 |
| --- | --- | --- | --- | --- |
| A | 요청UUID | UUID 문자열 | 예 | 기존 접수 UUID, 유일키 |
| B | 최초내용지문 | 64자리 16진 문자열 | 예 | 최초 접수 fingerprint, 재접수 중복 방지 |
| C | 접수시각(UTC) | ISO 시각 문자열 | 예 | 원래 접수시각 |
| D | 접수시각(한국) | 시각 문자열 | 예 | 기존 R열, C에서 Asia/Seoul 변환 |
| E | 견적접수번호 | 문자열 | 예 | 기존 NAMU/NFQ 번호 유지 |
| F | 변경순번 | 양의 정수(셀은 텍스트) | 예 | 마지막 종료 변경순번, LAST_SEQUENCE 감소/재사용 금지 |
| G | 종료구분 | enum 문자열 | 예 | expired / admin_deleted |
| H | 종료시각(UTC) | ISO 시각 문자열 | 예 | 자연 만료는 접수+30일, 명시 삭제는 삭제시각 |
| I | 원래연도 | YYYY 문자열 | 예 | 기존 견적 파일 연도 |
| J | 원래탭 | 문자열 | 예 | 견적요청 / 견적확정 |
| K | 정리단계 | enum 문자열 | 예 | VERIFIED(검증 완료) / REMOVED(물리 삭제 확인) |

고객 이름·전화·주소·품목 원문·요청사항·공식견적서 원문을 색인으로 복사하지 않는다. UUID·지문·번호와 종료/삭제 근거의 유지 기한을 이번에 축소하지 않는다. 중단 후 VERIFIED만 남아도 색인과 업무 행을 UUID로 합쳐 한 건으로 읽으며, 같은 UUID의 식별 정보 충돌은 중단한다. 실제 헤더 대조는 **신규 색인의 운영 생성 후 사용자 확인 대상**이다. 위 2026-11-16의 18/20열 운영 헤더 대조 결과와 혼동하지 않는다.

운영 순서·기본 비활성화·안전성 건너뜀·v1/v2 호환과 사용자 확인 항목은 [EXPIRED_QUOTE_COMPACTION_RUNTIME.md](EXPIRED_QUOTE_COMPACTION_RUNTIME.md)를 따른다.
