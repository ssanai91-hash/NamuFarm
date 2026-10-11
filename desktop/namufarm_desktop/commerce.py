"""PC quotation and carrier handoff. No synthetic carrier numbers or carrier booking."""
import copy
import csv
import io
import re
import uuid
from datetime import datetime, timedelta
from html import escape

from .domain import EDITABLE, SEOUL, integer, metadata, now_ms, require, valid_id

ISSUER = {"businessName": "나무팜농원", "businessNumber": "213-94-24363", "representative": "오진웅",
          "address": "충북 옥천군 동이면 금강로 185", "phone": "043-732-0980", "businessType": "", "category": ""}
TRACKING_PREFIX = "[로젠택배 송장] "
EXPORT_HEADERS = ("주문번호", "수하인명", "수하인전화", "우편번호", "수하인주소", "상세주소", "품목명", "수량", "배송메모", "운임구분", "송장번호")


def new_draft(request=None):
    person = request["personal"] if request else {}
    identifier = str(uuid.uuid4())
    return {"id": identifier, "sourceRequestId": request["requestId"] if request else "",
            "requestReceiptNumber": (request.get("receiptNumber") or request["requestId"]) if request else "",
            "createdAt": now_ms(),
            "customerName": person.get("customerName", ""), "customerPhone": person.get("customerPhone", ""),
            "customerEmail": person.get("customerEmail", ""), "receiverName": person.get("customerName", ""),
            "formalQuoteRequested": bool(person.get("formalQuoteRequested", False)),
            "quoteRecipientName": person.get("recipientName", ""),
            "quoteReplyChannel": person.get("replyChannel", "sms"),
            "zipNo": "", "roadAddress": "", "detailAddress": "", "requestRegion": person.get("region", ""), "note": person.get("message", ""),
            "items": [{"species": i["species"], "specification": i.get("specification", ""), "quantity": i["quantity"],
                       "unitPrice": 0, "unitCost": 0} for i in person.get("items", [])],
            "shippingFee": 0, "isPrepaid": False, "taxAmount": 0, "costConfirmed": False,
            "validUntil": (datetime.now(SEOUL) + timedelta(days=7)).strftime("%Y-%m-%d"),
            "issuer": copy.deepcopy(ISSUER), "paymentAccount": ""}


def draft_amounts(draft):
    items = draft.get("items", [])
    require(1 <= len(items) <= 100, "견적 품목을 1개 이상 입력해주세요.")
    quantity = price = cost = 0
    for item in items:
        for name, limit in (("species", 200), ("specification", 120)):
            text = item.get(name, "")
            require(isinstance(text, str) and len(text) <= limit and not any(c in text for c in "|\n\r"), "품목명·규격을 확인해주세요.")
        require(item["species"].strip(), "품목명을 입력해주세요.")
        qty = integer(item.get("quantity"), 1, 2147483647)
        quantity += qty
        price += qty * integer(item.get("unitPrice"), 0, 2147483647)
        cost += qty * integer(item.get("unitCost"), 0, 2147483647)
    require(type(draft.get("isPrepaid")) is bool)
    shipping = integer(draft.get("shippingFee"), 0, 2147483647)
    price += shipping if draft["isPrepaid"] else 0
    integer(price, 0, 2147483647)
    integer(quantity, 1, 2147483647)
    integer(cost)
    tax = integer(draft.get("taxAmount"), 0, 2147483647)
    return {"quantity": quantity, "supply": price, "tax": tax, "total": price + tax, "cost": cost}


def validate_draft(draft):
    require(isinstance(draft, dict) and valid_id(draft.get("id")), "견적 식별자를 확인해주세요.")
    require(draft.get("sourceRequestId") == "" or valid_id(draft.get("sourceRequestId")))
    for name, limit in EDITABLE.items():
        value = draft.get(name, "")
        require(isinstance(value, str) and len(value) <= limit and not any(ord(c) < 32 and (name != "note" or c not in "\n\t") for c in value), "고객·배송 정보를 확인해주세요.")
    require(draft["customerName"].strip(), "고객명을 입력해주세요.")
    require(re.fullmatch(r"0[0-9]{8,10}", draft["customerPhone"]), "전화번호를 숫자 9~11자리로 입력해주세요.")
    email = draft.get("customerEmail", "")
    require(not email or re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email), "이메일 형식을 확인해주세요.")
    formal = draft.get("formalQuoteRequested", False)
    recipient = draft.get("quoteRecipientName", "")
    reply_channel = draft.get("quoteReplyChannel", "sms")
    require(type(formal) is bool, "공식 견적서 요청값을 확인해주세요.")
    require(isinstance(recipient, str) and len(recipient) <= 100 and not any(ord(c) < 32 for c in recipient),
            "견적서 수신인을 확인해주세요.")
    require(reply_channel in ("sms", "email", "both"), "견적 수신 방법을 확인해주세요.")
    require(reply_channel == "sms" or bool(email), "메일 수신 견적에는 이메일 주소가 필요합니다.")
    require(type(draft.get("costConfirmed")) is bool)
    integer(draft.get("createdAt"), 1, now_ms() + 300000)
    require(isinstance(draft.get("validUntil"), str) and 1 <= len(draft["validUntil"]) <= 100)
    require(isinstance(draft.get("paymentAccount", ""), str) and len(draft.get("paymentAccount", "")) <= 500)
    issuer = draft.get("issuer")
    require(isinstance(issuer, dict) and set(ISSUER) <= set(issuer), "견적 발행자 정보를 확인해주세요.")
    for key in ISSUER:
        limit = {"address": 150, "businessNumber": 20, "phone": 30}.get(key, 50)
        require(isinstance(issuer[key], str) and len(issuer[key]) <= limit and not any(ord(c) < 32 for c in issuer[key]), "발행자 정보를 확인해주세요.")
    require(all(issuer[k].strip() for k in ("businessName", "businessNumber", "representative", "address", "phone")), "발행자 필수 정보를 입력해주세요.")
    require(re.fullmatch(r"[0-9]{3}-?[0-9]{2}-?[0-9]{5}", issuer["businessNumber"]), "사업자등록번호를 확인해주세요.")
    require(not any(ord(c) < 32 for c in draft["validUntil"]), "견적 유효기간을 확인해주세요.")
    require(not any(ord(c) < 32 and c != "\n" for c in draft.get("paymentAccount", "")), "계좌 안내를 확인해주세요.")
    draft_amounts(draft)
    return draft


def quote_document(draft):
    validate_draft(draft)
    amounts = draft_amounts(draft)
    stamp = draft["createdAt"]
    date = datetime.fromtimestamp(stamp / 1000, SEOUL).strftime("%Y%m%d")
    lines = [{"name": i["species"].strip(), "specification": i["specification"], "quantity": i["quantity"], "unitPrice": i["unitPrice"], "unit": "주"} for i in draft["items"]]
    if draft["isPrepaid"]:
        lines.append({"name": "운반비", "specification": "선불", "quantity": 1, "unitPrice": draft["shippingFee"], "unit": "건"})
    return {"schemaVersion": 1, "id": draft["id"], "number": f"NQ-{date}-{draft['id'][:8].upper()}",
            "sourceQuoteNumber": "PCQ_" + draft["id"], "issuedAt": stamp, "customerPhone": draft["customerPhone"],
            "recipient": {"customerName": draft["customerName"], "customerEmail": draft["customerEmail"],
                          "recipientName": draft.get("quoteRecipientName") or draft["customerName"],
                          "replyChannel": draft.get("quoteReplyChannel", "sms"),
                          "formalQuoteRequested": bool(draft.get("formalQuoteRequested", False)),
                          "sourceRequestId": draft["sourceRequestId"] or None},
            "issuer": copy.deepcopy(draft["issuer"]), "lines": lines, "supplyAmount": amounts["supply"], "taxAmount": amounts["tax"],
            "totalAmount": amounts["total"],
            "shippingNote": (f"택배비 선불 {draft['shippingFee']:,}원 · 합계 포함" if draft["isPrepaid"]
                             else f"택배비 후불 · 선불 시 {draft['shippingFee']:,}원"),
            "validUntil": draft["validUntil"], "notes": draft["note"].replace("\t", " ")[:500], "paymentAccount": draft.get("paymentAccount", "")}


def order_from_draft(draft):
    validate_draft(draft)
    amount, stamp = draft_amounts(draft), now_ms()
    order = {k: draft.get(k, "").strip() for k in EDITABLE}
    order.update(orderId=None, orderNumber="PC_" + draft["id"], orderDate=datetime.fromtimestamp(stamp / 1000, SEOUL).strftime("%Y-%m-%d %H:%M"),
                 status="입금확인", revision=1, totalQty=amount["quantity"], totalPrice=amount["supply"], isPrepaid=draft["isPrepaid"], shippingFee=draft["shippingFee"],
                 purchasedAt=stamp, paidAt=stamp, deliveredAt=0, completedAt=0, updatedAt=stamp, costTotal=amount["cost"], expenseTotal=0,
                 costConfirmed=draft["costConfirmed"], profitMode="resale", commissionBps=3000, taxAmount=amount["tax"],
                 sourceRequestId=draft["sourceRequestId"], sourceQuoteNumber="PCQ_" + draft["id"])
    # Existing Android codec explicitly treats this farm label as non-stock-backed.
    order["itemSummary"] = "\n".join("|".join([i["species"].strip(), "", "", i["specification"], "", str(i["quantity"]), str(i["unitPrice"]), "수기견적", str(i["unitCost"])]) for i in draft["items"])
    metadata(order)
    return order


def tracking_number(order):
    lines = [x[len(TRACKING_PREFIX):] for x in order.get("note", "").splitlines() if x.startswith(TRACKING_PREFIX)]
    # Customer-supplied notes are untrusted: a malformed marker must not crash the UI.
    if len(lines) == 1 and re.fullmatch(r"[0-9]{11}", lines[0]):
        return lines[0]
    return ""


def normalize_tracking(value):
    require(isinstance(value, str), "송장번호를 텍스트로 입력해주세요.")
    number = value.strip().replace("-", "").replace(" ", "")
    require(re.fullmatch(r"[0-9]{11}", number), "로젠에서 발급받은 숫자 11자리 송장번호를 입력해주세요.")
    return number


def customer_note(order):
    return "\n".join(x for x in order.get("note", "").splitlines() if not x.startswith(TRACKING_PREFIX)).strip()


def tracking_fields(order, number):
    number = normalize_tracking(number)
    require(order["status"] in ("입금확인", "배송확인"), "입금확인 상태에서 송장번호를 등록해주세요.")
    note = customer_note(order)
    note = (note + "\n" if note else "") + TRACKING_PREFIX + number
    require(len(note) <= 1000, "배송 메모를 줄인 후 송장번호를 등록해주세요.")
    return {"note": note}


def shipping_problem(order):
    if order["status"] in ("구매취소", "주문취소"):
        return "구매 취소"
    if order["status"] not in ("입금확인", "배송완료", "배송확인", "판매완료"):
        return "입금 확인 필요"
    if not order.get("receiverName", "").strip():
        return "수령인 입력"
    if not re.fullmatch(r"0[0-9]{8,10}", order.get("customerPhone", "")):
        return "연락처 확인"
    if not re.fullmatch(r"[0-9]{5}", order.get("zipNo", "")):
        return "우편번호 확인"
    if not order.get("roadAddress", "").strip():
        return "배송주소 입력"
    return ""


def csv_text(value):
    value = str(value)
    # CSV formula injection: protect untrusted customer/item text in Excel.
    return "'" + value if value.lstrip().startswith(("=", "+", "-", "@")) or value.startswith(("\t", "\r", "\n")) else value


def shipping_csv(rows):
    require(rows, "내보낼 주문을 선택해주세요.")
    stream = io.StringIO(newline="")
    writer = csv.writer(stream)
    writer.writerow(EXPORT_HEADERS)
    for row in rows:
        order = row["private"]["order"]
        require(order["status"] == "입금확인", "출고자료는 입금 확인된 주문만 내보낼 수 있습니다.")
        require(not shipping_problem(order), f"{order['orderNumber']}: {shipping_problem(order)}")
        writer.writerow([csv_text(v) for v in (order["orderNumber"], order["receiverName"], order["customerPhone"], order["zipNo"],
                       order["roadAddress"], order["detailAddress"], ", ".join(i["species"] for i in row["metadata"]["items"]), order["totalQty"],
                       customer_note(order), "선불" if order["isPrepaid"] else "착불", tracking_number(order))])
    return stream.getvalue()


def parse_tracking_csv(raw, rows):
    require(len(raw) <= 2 * 1024 * 1024, "송장 파일은 2MB 이하로 나누어 가져오세요.")
    try:
        text = raw.decode("utf-8-sig")
    except UnicodeDecodeError:
        try:
            text = raw.decode("cp949")
        except UnicodeDecodeError:
            require(False, "UTF-8 또는 한글 CSV 파일을 선택해주세요.")
    reader = csv.DictReader(io.StringIO(text, newline=""))
    require(reader.fieldnames and len(set(reader.fieldnames)) == len(reader.fieldnames) and {"주문번호", "송장번호"} <= set(reader.fieldnames), "CSV 첫 줄에 주문번호, 송장번호 열이 필요합니다.")
    return parse_tracking_records(reader, rows)


def parse_tracking_records(records, rows):
    """Shared, all-or-nothing validation for CSV and legacy Excel imports."""
    known = {r["metadata"]["orderNumber"]: r for r in rows}
    assignments, used = {}, {}
    for index, record in enumerate(records, 2):
        require(index <= 501, "한 번에 최대 500건을 가져오세요.")
        number, value = (record.get("주문번호") or "").strip(), record.get("송장번호") or ""
        require(number in known and number not in assignments, f"{index}행: 주문번호가 없거나 중복되었습니다.")
        tracking = normalize_tracking(value)
        require(tracking not in used, f"{index}행: 같은 송장번호를 여러 주문에 등록할 수 없습니다.")
        order = known[number]["private"]["order"]
        tracking_fields(order, tracking)
        old = tracking_number(order)
        require(not old or old == tracking, f"{index}행: 기존 송장과 다릅니다. 개별 주문에서 확인해주세요.")
        assignments[number], used[tracking] = tracking, number
    require(assignments, "가져올 송장번호가 없습니다.")
    for number, row in known.items():
        value = tracking_number(row["private"]["order"])
        require(value not in used or used[value] == number, "이미 다른 주문에 등록된 송장번호입니다.")
    return assignments


def document_html(title, subtitle, body):
    return ('<html><head><meta charset="utf-8"><style>body{font-family:"Malgun Gothic","NanumGothic",sans-serif;color:#1c352e;font-size:11pt}'
            'h1{font-size:28pt;color:#164d3f}h2{font-size:16pt}table{border-collapse:collapse;width:100%}th{background:#eaf1ec;text-align:left}'
            'td,th{padding:9px;border-bottom:1px solid #dae3dd}p{line-height:1.6}.amount{font-size:19pt;font-weight:bold}.muted{color:#63756c}</style></head><body>'
            f'<p class="muted">NAMUFARM / 나무팜</p><h1>{escape(title)}</h1><p>{escape(subtitle)}</p>{body}</body></html>')


def quote_html(draft, final=False):
    doc = quote_document(draft)
    return quote_document_html(doc, final)


def official_quote_html(draft):
    """Render a temporary A4 official quotation modeled after the farm's paper form."""
    validate_draft(draft)
    e = lambda value: escape(str(value))
    issuer = draft["issuer"]
    amount = draft_amounts(draft)
    quote_number = draft.get("requestReceiptNumber") or ("PCQ_" + draft["id"][:8])
    issued = datetime.now(SEOUL)
    issued_text = f"{issued.year}년 {issued.month}월 {issued.day}일"
    business_type = issuer.get("businessType") or "농업,도소매"
    category = issuer.get("category") or "묘목,화훼"

    lines = []
    for item in draft.get("items", []):
        quantity = int(item["quantity"])
        unit_price = int(item["unitPrice"])
        lines.append({
            "name": item["species"].strip(),
            "specification": item.get("specification", ""),
            "quantity": quantity,
            "unitPrice": unit_price,
            "amount": quantity * unit_price,
            "note": "",
        })
    if draft.get("isPrepaid") and int(draft.get("shippingFee", 0) or 0) > 0:
        fee = int(draft["shippingFee"])
        lines.append({
            "name": "택배비",
            "specification": "선불",
            "quantity": 1,
            "unitPrice": fee,
            "amount": fee,
            "note": "선불",
        })

    row_count = max(14, len(lines))
    item_rows = []
    for index in range(row_count):
        if index < len(lines):
            line = lines[index]
            item_rows.append(
                "<tr>"
                f"<td>{e(line['name'])}</td>"
                f"<td>{e(line['specification'])}</td>"
                f"<td class='num'>{line['quantity']:,}</td>"
                f"<td class='num'>{line['unitPrice']:,}</td>"
                f"<td class='num'>{line['amount']:,}</td>"
                "<td class='num'></td>"
                f"<td>{e(line['note'])}</td>"
                "</tr>"
            )
        else:
            item_rows.append("<tr><td>&nbsp;</td><td></td><td></td><td></td><td></td><td></td><td></td></tr>")

    if draft.get("isPrepaid"):
        shipping_note = f"택배비 선불 {int(draft.get('shippingFee', 0) or 0):,}원 · 합계 포함"
    else:
        shipping_note = f"택배비 후불 · 선불 시 {int(draft.get('shippingFee', 0) or 0):,}원"

    payment = draft.get("paymentAccount", "").strip() or "입금계좌 : 별도 안내"
    note = draft.get("note", "").strip()
    note_line = f"<div>4. 요청사항 : {e(note)}</div>" if note else "<div>4. 요청사항 : 별도 없음</div>"

    return f"""<html>
<head>
<meta charset="utf-8">
<style>
@page {{ size:A4 portrait; margin:8mm; }}
body {{ font-family:"Malgun Gothic","NanumGothic",sans-serif; color:#111; font-size:9.5pt; margin:0; }}
.official {{ width:100%; }}
.topline {{ width:100%; border-collapse:collapse; margin-bottom:2mm; }}
.topline td {{ border:0; padding:0; }}
.no {{ width:35%; border-bottom:1px solid #333 !important; font-size:9pt; }}
.title {{ text-align:center; font-size:25pt; font-weight:700; letter-spacing:10px; padding:0 0 2mm 0; }}
.title-line {{ width:34%; border-top:2px solid #555; margin:0 auto 5mm auto; }}
.party {{ width:100%; border-collapse:collapse; margin-bottom:3mm; }}
.party td {{ vertical-align:top; }}
.customer {{ width:42%; padding:2mm 4mm 0 2mm; }}
.customer-line {{ border-bottom:1px solid #555; padding:1.2mm 2mm; font-size:10pt; }}
.supplier {{ width:58%; border:1px solid #222; border-collapse:collapse; }}
.supplier td {{ border:1px solid #555; padding:1.2mm 1.8mm; font-size:8.8pt; }}
.supplier .key {{ width:18%; text-align:center; font-weight:700; background:#fafafa; }}
.supplier .value {{ width:32%; }}
.stamp {{ color:#a71919; font-weight:700; border:1px solid #a71919; padding:1px 3px; }}
.intro {{ margin:1.5mm 0 2mm 1mm; }}
.total-box {{ border:1.5px solid #222; padding:2.2mm 3mm; font-size:15pt; font-weight:700; margin-bottom:0; }}
.items {{ width:100%; border-collapse:collapse; table-layout:fixed; }}
.items th,.items td {{ border:1px solid #333; padding:1.5mm 1mm; height:7mm; }}
.items th {{ text-align:center; font-size:9.2pt; background:#fff; }}
.items td {{ font-size:9pt; }}
.items .c-name {{ width:23%; }} .items .c-spec {{ width:12%; }} .items .c-qty {{ width:8%; }}
.items .c-unit {{ width:14%; }} .items .c-supply {{ width:16%; }} .items .c-tax {{ width:11%; }} .items .c-note {{ width:16%; }}
.num {{ text-align:right; }}
.sum-row td {{ font-weight:700; height:8mm; }}
.notes {{ border:1px solid #333; border-top:0; width:100%; border-collapse:collapse; }}
.notes td {{ border-right:1px solid #333; padding:2mm; vertical-align:top; }}
.notes .label {{ width:20%; text-align:center; font-size:12pt; font-weight:700; vertical-align:middle; }}
.notes .body {{ line-height:1.85; font-size:9pt; }}
.muted {{ color:#444; }}
</style>
</head>
<body>
<div class="official">
<table class="topline">
<tr><td class="no">NO. {e(quote_number)}</td><td></td></tr>
</table>
<div class="title">견 적 서</div>
<div class="title-line"></div>

<table class="party">
<tr>
<td class="customer">
  <div class="customer-line">{e(issued_text)}</div>
  <div class="customer-line">{e(draft['customerName'])} 귀하</div>
  <div class="customer-line">{e(draft['customerPhone'])}</div>
</td>
<td>
  <table class="supplier">
    <tr><td class="key">공급자</td><td class="key">등록번호</td><td colspan="3">{e(issuer['businessNumber'])}</td></tr>
    <tr><td class="key" rowspan="4">공<br>급<br>자</td><td class="key">상호</td><td class="value">{e(issuer['businessName'])}</td><td class="key">성명</td><td>{e(issuer['representative'])} <span class="stamp">인</span></td></tr>
    <tr><td class="key">사업장주소</td><td colspan="3">{e(issuer['address'])}</td></tr>
    <tr><td class="key">업태</td><td>{e(business_type)}</td><td class="key">종목</td><td>{e(category)}</td></tr>
    <tr><td class="key">연락처</td><td colspan="3">{e(issuer['phone'])}</td></tr>
  </table>
</td>
</tr>
</table>

<div class="intro">아래와 같이 견적합니다.</div>
<div class="total-box">합계금액(공급가액+세액)&nbsp;&nbsp;&nbsp; ₩{amount['total']:,}원</div>

<table class="items">
<tr>
  <th class="c-name">품명</th><th class="c-spec">규격</th><th class="c-qty">수량</th>
  <th class="c-unit">단가</th><th class="c-supply">공급가액</th><th class="c-tax">세액</th><th class="c-note">비고</th>
</tr>
{''.join(item_rows)}
<tr class="sum-row">
  <td>계</td><td></td><td></td><td></td><td class="num">{amount['supply']:,}</td>
  <td class="num">{amount['tax']:,}</td><td></td>
</tr>
</table>

<table class="notes">
<tr>
<td class="label">중요사항</td>
<td class="body">
  <div>1. {e(payment)}</div>
  <div>2. 견적 유효기간 : {e(draft['validUntil'])} · 유효기간 이후 금액이 변동될 수 있습니다.</div>
  <div>3. {e(shipping_note)}</div>
  {note_line}
</td>
</tr>
</table>
</div>
</body>
</html>"""


def quote_document_html(doc, final=True):
    e = lambda value: escape(str(value))
    issuer = doc["issuer"]
    body = f'<p><b>{e(doc["recipient"]["customerName"])} 귀하</b> · {e(doc["customerPhone"])}</p><p>{e(issuer["businessName"])} / {e(issuer["representative"])} / {e(issuer["businessNumber"])}<br>{e(issuer["address"])} / {e(issuer["phone"])}</p>'
    body += '<table><tr><th>품목 / 규격</th><th>수량</th><th>단가</th><th>금액</th></tr>'
    for line in doc["lines"]:
        body += f'<tr><td>{e(line["name"])}<br>{e(line["specification"])}</td><td>{line["quantity"]:,} {e(line["unit"])}</td><td>{line["unitPrice"]:,}</td><td>{line["quantity"]*line["unitPrice"]:,}</td></tr>'
    body += f'</table><p>공급가 {doc["supplyAmount"]:,}원 + 세액 {doc["taxAmount"]:,}원</p><p class="amount">합계 {doc["totalAmount"]:,}원</p><p>{e(doc["shippingNote"])}<br>유효기간: {e(doc["validUntil"])}<br>{e(doc["paymentAccount"])}</p><p>{e(doc["notes"]).replace(chr(10), "<br>")}</p>'
    return document_html("견적서" if final else "견적안", doc["number"], body)


def shipping_html(rows):
    require(rows, "출력할 주문을 선택해주세요.")
    pages = []
    for row in rows:
        order = row["private"]["order"]
        number = tracking_number(order)
        require(number, "송장번호를 등록한 주문만 출력할 수 있습니다.")
        e = lambda value: escape(str(value))
        body = f'<h2>로젠택배 · {e(number)}</h2><p>주문 {e(order["orderNumber"])}</p><p><b>{e(order["receiverName"])}</b> / {e(order["customerPhone"])}<br>({e(order["zipNo"])}) {e(order["roadAddress"])} {e(order["detailAddress"])}</p><table><tr><th>포장 확인</th><th>품목</th><th>수량</th></tr>'
        for item in row["metadata"]["items"]:
            body += f'<tr><td>□</td><td>{e(item["species"])}</td><td>{item["quantity"]:,}주</td></tr>'
        body += f'</table><p>{e(customer_note(order))}</p><p class="muted">출고 확인용 문서입니다. 택배 부착용 공식 운송장은 로젠 기업택배시스템에서 출력하세요.</p>'
        pages.append(body)
    return document_html("송장번호 · 출고 확인서", "", '<div style="page-break-before:always"></div>'.join(pages))
