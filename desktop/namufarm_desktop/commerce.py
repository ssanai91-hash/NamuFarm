"""PC quotation and carrier handoff. No synthetic carrier numbers or carrier booking."""
import copy
import csv
import io
import hashlib
import json
import re
import uuid
from datetime import datetime, timedelta
from html import escape

from .domain import EDITABLE, SEOUL, integer, metadata, now_ms, require, valid_id

ISSUER = {"businessName": "나무팜농원", "businessNumber": "213-94-24363", "representative": "오진웅",
          "address": "충북 옥천군 동이면 금강로 185", "phone": "043-732-0980", "businessType": "", "category": ""}
PAYMENT_BANK_ACCOUNT = "농협 328-12-035671"
PAYMENT_ACCOUNT_HOLDER = "오진웅"
TRACKING_PREFIX = "[로젠택배 송장] "
EXPORT_HEADERS = ("주문번호", "수하인명", "수하인전화", "우편번호", "수하인주소", "상세주소", "품목명", "수량", "배송메모", "운임구분", "송장번호")


def payment_account_text(draft):
    """Use the quote's own account instructions, falling back to the existing farm account."""
    return draft.get("paymentAccount", "").strip() or f"{PAYMENT_BANK_ACCOUNT} {PAYMENT_ACCOUNT_HOLDER}"


def payment_notice(draft):
    account = payment_account_text(draft)
    if account == f"{PAYMENT_BANK_ACCOUNT} {PAYMENT_ACCOUNT_HOLDER}":
        return f"입금계좌 : {PAYMENT_BANK_ACCOUNT}\n예금주 : {PAYMENT_ACCOUNT_HOLDER}"
    return f"입금계좌 안내 : {account}"


def quote_delivery_fingerprint(draft):
    """Bind a successful delivery to the customer and quotation that was sent."""
    keys = ("id", "sourceRequestId", "requestReceiptNumber", "customerName", "customerPhone",
            "customerEmail", "note", "issuer", "paymentAccount", "isPrepaid", "shippingFee", "taxAmount")
    value = {key: draft.get(key) for key in keys}
    value["items"] = [{key: item.get(key, "" if key in ("species", "specification") else 0)
                       for key in ("species", "specification", "quantity", "unitPrice")}
                      for item in draft.get("items", [])]
    for original, item in zip(draft.get("items", []), value["items"]):
        if original.get("remarks", ""):
            item["remarks"] = original["remarks"]
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False,
                                     separators=(",", ":")).encode("utf-8")).hexdigest()


def new_draft(request=None):
    person = request["personal"] if request else {}
    identifier = str(uuid.uuid4())
    pricing = request.get("quotePricing") if request else None
    rows = person.get("items", [])
    priced = pricing.get("items", []) if isinstance(pricing, dict) else []
    if not priced:
        pricing = None
    else:
        rows = priced
    return {"id": identifier, "sourceRequestId": request["requestId"] if request else "",
            "requestReceiptNumber": (request.get("receiptNumber") or request["requestId"]) if request else "",
            "createdAt": now_ms(),
            "customerName": person.get("customerName", ""), "customerPhone": person.get("customerPhone", ""),
            "customerEmail": person.get("customerEmail", ""), "receiverName": person.get("customerName", ""),
            "formalQuoteRequested": bool(person.get("formalQuoteRequested", False)),
            "quoteRecipientName": person.get("recipientName", ""),
            "quoteReplyChannel": person.get("replyChannel", "sms"),
            "zipNo": "", "roadAddress": "", "detailAddress": "", "requestRegion": person.get("region", ""), "note": person.get("message", ""),
            "requestNote": person.get("message", ""),
            "items": [{"species": i["species"], "specification": i.get("specification", ""), "quantity": i["quantity"],
                       "unitPrice": priced[n].get("unitPrice", 0) if pricing else 0, "unitCost": 0,
                       "remarks": i.get("remarks", "")}
                      for n, i in enumerate(rows)],
            "shippingFee": pricing.get("shippingFee", 0) if pricing else 0,
            "isPrepaid": pricing.get("isPrepaid", False) if pricing else False,
            "taxAmount": pricing.get("taxAmount", 0) if pricing else 0,
            "costConfirmed": False,
            "validUntil": (datetime.now(SEOUL) + timedelta(days=7)).strftime("%Y-%m-%d"),
            "issuer": copy.deepcopy(ISSUER), "paymentAccount": ""}


def new_manual_draft():
    """Create a phone/manual quote with a human-readable local quote number."""
    draft = new_draft()
    stamp = datetime.fromtimestamp(draft["createdAt"] / 1000, SEOUL)
    draft["requestReceiptNumber"] = f"M-{stamp:%Y%m%d-%H%M%S}-{draft['id'][:4].upper()}"
    draft["manualStatus"] = "견적확정"
    return draft


def draft_amounts(draft):
    items = draft.get("items", [])
    require(1 <= len(items) <= 100, "견적 품목을 1개 이상 입력해주세요.")
    quantity = price = cost = 0
    for item in items:
        for name, limit in (("species", 200), ("specification", 120)):
            text = item.get(name, "")
            require(isinstance(text, str) and len(text) <= limit and not any(c in text for c in "|\n\r"), "품목명·규격을 확인해주세요.")
        remarks = item.get("remarks", "")
        require(isinstance(remarks, str) and len(remarks) <= 200 and not any(ord(c) < 32 for c in remarks),
                "묘목별 비고는 줄바꿈 없이 200자 이내로 입력해주세요.")
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
    receipt = draft.get("requestReceiptNumber", "")
    require(isinstance(receipt, str) and len(receipt) <= 120 and not any(ord(c) < 32 for c in receipt),
            "견적번호를 확인해주세요.")
    if draft.get("manualStatus") is not None:
        require(draft.get("sourceRequestId") == "" and draft.get("manualStatus") in ("견적확정", "견적발송완료"),
                "수기 견적 상태를 확인해주세요.")
    for name, limit in EDITABLE.items():
        value = draft.get(name, "")
        require(isinstance(value, str) and len(value) <= limit and not any(ord(c) < 32 and (name != "note" or c not in "\n\t") for c in value), "고객·배송 정보를 확인해주세요.")
    original_note = draft.get("requestNote", "")
    require(isinstance(original_note, str) and len(original_note) <= 1000 and
            not any(ord(c) < 32 and c not in "\r\n\t" for c in original_note), "고객 요청 원문을 확인해주세요.")
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
    lines = [{"name": i["species"].strip(), "specification": i["specification"], "remarks": i.get("remarks", ""), "quantity": i["quantity"], "unitPrice": i["unitPrice"], "unit": "주"} for i in draft["items"]]
    if draft["isPrepaid"]:
        lines.append({"name": "운반비", "specification": "선불", "quantity": 1, "unitPrice": draft["shippingFee"], "unit": "건"})
    return {"schemaVersion": 1, "id": draft["id"], "number": f"NQ-{date}-{draft['id'][:8].upper()}",
            "sourceQuoteNumber": (draft.get("requestReceiptNumber") if draft.get("manualStatus") else "PCQ_" + draft["id"]), "issuedAt": stamp, "customerPhone": draft["customerPhone"],
            "recipient": {"customerName": draft["customerName"], "customerEmail": draft["customerEmail"],
                          "recipientName": draft.get("quoteRecipientName") or draft["customerName"],
                          "replyChannel": draft.get("quoteReplyChannel", "sms"),
                          "formalQuoteRequested": bool(draft.get("formalQuoteRequested", False)),
                          "sourceRequestId": draft["sourceRequestId"] or None},
            "issuer": copy.deepcopy(draft["issuer"]), "lines": lines, "supplyAmount": amounts["supply"], "taxAmount": amounts["tax"],
            "totalAmount": amounts["total"],
            "shippingNote": (f"택배비 선불 {draft['shippingFee']:,}원 · 합계 포함" if draft["isPrepaid"]
                             else f"택배비 후불 · 선불 시 {draft['shippingFee']:,}원"),
            "validUntil": draft["validUntil"], "notes": draft["note"].replace("\t", " "), "paymentAccount": draft.get("paymentAccount", "")}


def order_from_draft(draft):
    validate_draft(draft)
    amount, stamp = draft_amounts(draft), now_ms()
    order = {k: draft.get(k, "").strip() for k in EDITABLE}
    order.update(orderId=None, orderNumber="PC_" + draft["id"], orderDate=datetime.fromtimestamp(stamp / 1000, SEOUL).strftime("%Y-%m-%d %H:%M"),
                 status="입금확인", revision=1, totalQty=amount["quantity"], totalPrice=amount["supply"], isPrepaid=draft["isPrepaid"], shippingFee=draft["shippingFee"],
                 purchasedAt=stamp, paidAt=stamp, deliveredAt=0, completedAt=0, updatedAt=stamp, costTotal=amount["cost"], expenseTotal=0,
                 costConfirmed=draft["costConfirmed"], profitMode="resale", commissionBps=3000, taxAmount=amount["tax"],
                 sourceRequestId=draft["sourceRequestId"],
                 sourceQuoteNumber=(draft.get("requestReceiptNumber") if draft.get("manualStatus") else "PCQ_" + draft["id"]))
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
    """Render a temporary A4 official quotation closely matching the farm's paper form."""
    validate_draft(draft)
    e = lambda value: escape(str(value))
    issuer = draft["issuer"]
    amount = draft_amounts(draft)
    quote_number = draft.get("requestReceiptNumber") or ("PCQ_" + draft["id"][:8])
    issued = datetime.now(SEOUL)
    issued_text = f"{issued.year}년 {issued.month}월 {issued.day}일"
    business_type = issuer.get("businessType") or "농업,도소매"
    category = issuer.get("category") or "묘목,화훼"

    rows = []
    for item in draft.get("items", []):
        quantity = int(item["quantity"])
        unit_price = int(item["unitPrice"])
        rows.append({
            "name": item["species"].strip(),
            "specification": item.get("specification", ""),
            "quantity": quantity,
            "unitPrice": unit_price,
            "amount": quantity * unit_price,
            "note": item.get("remarks", ""),
        })
    if draft.get("isPrepaid") and int(draft.get("shippingFee", 0) or 0) > 0:
        fee = int(draft["shippingFee"])
        rows.append({
            "name": "운반비",
            "specification": "선불",
            "quantity": 1,
            "unitPrice": fee,
            "amount": fee,
            "note": "택배비",
        })

    visible_rows = max(18, len(rows))
    item_rows = []
    for index in range(visible_rows):
        if index < len(rows):
            line = rows[index]
            item_rows.append(
                "<tr>"
                f"<td class='name'>{e(line['name'])}</td>"
                f"<td class='spec'>{e(line['specification'])}</td>"
                f"<td class='qty'>{line['quantity']:,}</td>"
                f"<td class='num'>{line['unitPrice']:,}</td>"
                f"<td class='num'>{line['amount']:,}</td>"
                "<td class='num'></td>"
                f"<td class='note'>{e(line['note'])}</td>"
                "</tr>"
            )
        else:
            item_rows.append(
                "<tr><td>&nbsp;</td><td></td><td></td><td></td>"
                "<td></td><td></td><td></td></tr>"
            )

    shipping_note = (
        f"택배비 선불 {int(draft.get('shippingFee', 0) or 0):,}원 · 합계 포함"
        if draft.get("isPrepaid")
        else f"택배비 후불 · 선불 시 {int(draft.get('shippingFee', 0) or 0):,}원"
    )
    payment = payment_account_text(draft)
    note = draft.get("note", "").strip() or "별도 요청사항 없음"
    fax = "732-0981"

    # Keep CSS outside the f-string. This prevents CSS braces from ever being
    # interpreted as Python expressions while still giving us a fixed A4-like
    # 760 x ~1075 preview canvas.
    style = """
<style>
body { font-family:"Malgun Gothic","NanumGothic",sans-serif; color:#111; font-size:12px; margin:0; padding:0; }
.paper { width:760px; background:#fff; border-collapse:collapse; table-layout:fixed; }
.paper > tbody > tr > td { border:0; padding:0; }
.no-table { width:100%; border-collapse:collapse; }
.no-line { width:180px; border-bottom:1px solid #333; font-size:12px; padding:0 0 5px 0; }
.doc-title { font-size:34px; font-weight:700; letter-spacing:18px; text-align:center; padding:0; }
.title-rule { width:250px; border-top:2px solid #555; margin:3px auto 20px auto; }
.party { width:100%; border-collapse:collapse; table-layout:fixed; margin:0 0 8px 0; }
.customer { width:39%; vertical-align:top; padding:0 22px 0 4px; }
.customer-line { border-bottom:1px solid #555; padding:7px 4px 5px 4px; font-size:14px; }
.supplier-wrap { width:61%; vertical-align:top; }
.supplier { width:100%; border-collapse:collapse; table-layout:fixed; }
.supplier td { border:1px solid #333; padding:4px 5px; font-size:12px; line-height:1.15; }
.supplier .side { width:32px; text-align:center; font-weight:700; letter-spacing:1px; }
.supplier .key { width:74px; text-align:center; font-weight:700; background:#fff; }
.supplier .small-key { width:52px; text-align:center; font-weight:700; }
.stamp { color:#a71919; border:1px solid #a71919; border-radius:50%; padding:2px 4px; font-size:10px; font-weight:700; }
.intro { font-size:14px; margin:8px 2px 8px 2px; }
.total { width:100%; border-collapse:collapse; table-layout:fixed; }
.total td { border:2px solid #222; padding:7px 9px; font-size:18px; font-weight:700; }
.total .label { width:31%; }
.items { width:100%; border-collapse:collapse; table-layout:fixed; }
.items th, .items td { border:1px solid #333; height:27px; padding:2px 5px; font-size:12px; }
.items th { font-size:13px; font-weight:700; text-align:center; }
.items .name { width:23%; text-align:left; }
.items .spec { width:8%; text-align:center; }
.items .qty { width:7%; text-align:center; }
.items .num { text-align:right; }
.items .note { text-align:center; }
.sum td { height:31px; font-weight:700; }
.notes { width:100%; border-collapse:collapse; table-layout:fixed; }
.notes td { border:1px solid #333; border-top:0; padding:10px 8px; vertical-align:top; }
.notes .label { width:20%; text-align:center; vertical-align:middle; font-size:17px; font-weight:700; }
.notes .body { line-height:2.05; font-size:12px; }
</style>
"""

    body = f"""
<table class="paper" align="center" cellspacing="0" cellpadding="0">
<tr><td>
  <table class="no-table"><tr><td class="no-line">NO. {e(quote_number)}</td><td></td></tr></table>
  <div class="doc-title">견 적 서</div>
  <div class="title-rule"></div>

  <table class="party">
    <tr>
      <td class="customer">
        <div class="customer-line">{e(issued_text)}</div>
        <div class="customer-line">{e(draft['customerName'])} 귀하</div>
        <div class="customer-line">{e(draft['customerPhone'])}</div>
      </td>
      <td class="supplier-wrap">
        <table class="supplier">
          <tr>
            <td class="side" rowspan="5">공<br>급<br>자</td>
            <td class="key">등록번호</td>
            <td colspan="3">{e(issuer['businessNumber'])}</td>
          </tr>
          <tr>
            <td class="key">상 호</td>
            <td>{e(issuer['businessName'])}</td>
            <td class="small-key">성 명</td>
            <td>{e(issuer['representative'])} <span class="stamp">인</span></td>
          </tr>
          <tr>
            <td class="key">사업장주소</td>
            <td colspan="3">{e(issuer['address'])}</td>
          </tr>
          <tr>
            <td class="key">업 태</td>
            <td>{e(business_type)}</td>
            <td class="small-key">종 목</td>
            <td>{e(category)}</td>
          </tr>
          <tr>
            <td class="key">연락처</td>
            <td colspan="3">T:{e(issuer['phone'])}&nbsp;&nbsp;&nbsp;F:{e(fax)}</td>
          </tr>
        </table>
      </td>
    </tr>
  </table>

  <div class="intro">아래와같이 견적 합니다.</div>

  <table class="total"><tr>
    <td class="label">합계금액(공급가액+세액)</td>
    <td>₩{amount['total']:,}원</td>
  </tr></table>

  <table class="items">
    <colgroup>
      <col style="width:23%"><col style="width:8%"><col style="width:7%">
      <col style="width:14%"><col style="width:16%"><col style="width:14%"><col style="width:18%">
    </colgroup>
    <tr><th>품 명</th><th>규격</th><th>수량</th><th>단 가</th><th>공급가액</th><th>세 액</th><th>비 고</th></tr>
    {''.join(item_rows)}
    <tr class="sum"><td style="text-align:center">계</td><td></td><td></td><td></td>
      <td class="num">{amount['supply']:,}</td><td class="num">{amount['tax']:,}</td><td></td></tr>
  </table>

  <table class="notes"><tr>
    <td class="label">중요사항</td>
    <td class="body">
      1. {e(payment)}<br>
      2. 견적후 일정기간후 금액변동 있을수 있음.<br>
      3. {e(shipping_note)}<br>
      4. {e(note)}
    </td>
  </tr></table>
</td></tr>
</table>
"""
    return "<html><head><meta charset='utf-8'>" + style + "</head><body>" + body + "</body></html>"


def quote_document_html(doc, final=True):
    e = lambda value: escape(str(value))
    issuer = doc["issuer"]
    body = f'<p><b>{e(doc["recipient"]["customerName"])} 귀하</b> · {e(doc["customerPhone"])}</p><p>{e(issuer["businessName"])} / {e(issuer["representative"])} / {e(issuer["businessNumber"])}<br>{e(issuer["address"])} / {e(issuer["phone"])}</p>'
    body += '<table><tr><th>품목 / 규격</th><th>수량</th><th>단가</th><th>금액</th><th>비고</th></tr>'
    for line in doc["lines"]:
        body += f'<tr><td>{e(line["name"])}<br>{e(line["specification"])}</td><td>{line["quantity"]:,} {e(line["unit"])}</td><td>{line["unitPrice"]:,}</td><td>{line["quantity"]*line["unitPrice"]:,}</td><td>{e(line.get("remarks", ""))}</td></tr>'
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
