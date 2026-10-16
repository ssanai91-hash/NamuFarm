import copy
import re
import time
from datetime import datetime, timezone, timedelta

from .errors import UserError

MAX_SAFE = 9007199254740991
STATES = ("입금확인", "배송완료", "구매취소")
LEGACY_STATES = ("구매확정", "배송확인", "판매완료", "주문취소")
ALL_STATES = STATES + LEGACY_STATES
NEXT = {"입금확인": "배송완료", "구매확정": "배송완료", "배송확인": "배송완료"}
TERMINAL_STATES = ("배송완료", "구매취소", "판매완료", "주문취소")
SEOUL = timezone(timedelta(hours=9))
UUID_RE = re.compile(r"[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}")
META_FIELDS = ("orderNumber", "orderDate", "status", "revision", "totalQty", "totalPrice", "isPrepaid",
               "shippingFee", "purchasedAt", "paidAt", "deliveredAt", "completedAt", "updatedAt",
               "costTotal", "expenseTotal", "costConfirmed", "profitMode", "commissionBps", "taxAmount", "items")
EDITABLE = {"customerName": 50, "customerPhone": 20, "customerEmail": 254, "receiverName": 100,
            "zipNo": 10, "roadAddress": 300, "detailAddress": 300, "note": 1000}


def now_ms():
    return int(time.time() * 1000)


def require(value, message="서버 기록 형식을 확인할 수 없습니다."):
    if not value:
        raise UserError(message)


def integer(value, minimum=0, maximum=MAX_SAFE):
    require(type(value) is int and minimum <= value <= maximum)
    return value


def valid_id(value):
    return isinstance(value, str) and UUID_RE.fullmatch(value) is not None


def line_items(summary):
    require(isinstance(summary, str))
    result = []
    for raw in summary.splitlines():
        if not raw.strip():
            continue
        parts = [x.strip() for x in raw.split("|")]
        require(len(parts) in (7, 9, 10))
        legacy = len(parts) == 7
        require(parts[0] and len(parts[0]) <= 200)
        try:
            quantity, price = int(parts[3 if legacy else 5]), int(parts[4 if legacy else 6])
        except ValueError:
            raise UserError("주문 품목 수량과 단가를 확인해주세요.") from None
        result.append({"species": parts[0], "quantity": integer(quantity, 1, 2147483647),
                       "unitPrice": integer(price, 0, 2147483647)})
    require(1 <= len(result) <= 100)
    return result


def validate_metadata(meta, clock=None):
    require(isinstance(meta, dict) and set(meta) == set(META_FIELDS))
    require(isinstance(meta["orderNumber"], str) and re.fullmatch(r"[A-Za-z0-9_-]{1,120}", meta["orderNumber"]))
    require(isinstance(meta["orderDate"], str) and re.fullmatch(r"\d{4}-\d{2}-\d{2} \d{2}:\d{2}", meta["orderDate"]))
    try:
        datetime.strptime(meta["orderDate"], "%Y-%m-%d %H:%M")
    except ValueError:
        raise UserError("주문 날짜를 확인해주세요.") from None
    require(meta["status"] in ALL_STATES)
    for name in ("revision", "totalQty", "totalPrice", "shippingFee", "purchasedAt", "paidAt",
                 "deliveredAt", "completedAt", "updatedAt", "costTotal", "expenseTotal", "commissionBps", "taxAmount"):
        integer(meta[name])
    require(meta["revision"] > 0 and meta["totalQty"] > 0 and meta["totalPrice"] <= 2147483647
            and meta["shippingFee"] <= 2147483647 and meta["commissionBps"] <= 10000)
    integer(meta["totalPrice"] + meta["taxAmount"])
    integer(meta["costTotal"] + meta["expenseTotal"])
    require(type(meta["isPrepaid"]) is bool and type(meta["costConfirmed"]) is bool
            and meta["profitMode"] in ("resale", "commission"))
    require(0 < meta["purchasedAt"] <= meta["updatedAt"] <= (clock or now_ms()) + 300000)
    require(all(0 <= meta[k] <= meta["updatedAt"] for k in ("paidAt", "deliveredAt", "completedAt")))
    require(isinstance(meta["items"], list) and 1 <= len(meta["items"]) <= 100)
    quantity, amount = 0, meta["shippingFee"] if meta["isPrepaid"] else 0
    for item in meta["items"]:
        require(isinstance(item, dict) and set(item) == {"species", "quantity", "unitPrice"})
        require(isinstance(item["species"], str) and 1 <= len(item["species"].strip()) <= 200
                and item["species"] == item["species"].strip())
        quantity += integer(item["quantity"], 1, 2147483647)
        amount += integer(item["unitPrice"], 0, 2147483647) * item["quantity"]
    require(quantity == meta["totalQty"] and amount == meta["totalPrice"])
    status = meta["status"]
    if status in STATES:
        require(meta["paidAt"] >= meta["purchasedAt"])
        if status == "배송완료":
            require(meta["deliveredAt"] >= meta["paidAt"]
                    and meta["completedAt"] >= meta["deliveredAt"]
                    and meta["costConfirmed"])
    else:
        legacy_rank = ("구매확정", "입금확인", "배송확인", "판매완료").index(status) if status != "주문취소" else -1
        if legacy_rank >= 1:
            require(meta["paidAt"] >= meta["purchasedAt"])
        if legacy_rank >= 2:
            require(meta["deliveredAt"] >= meta["paidAt"])
        if legacy_rank == 3:
            require(meta["completedAt"] >= meta["deliveredAt"] and meta["costConfirmed"])
    return meta


def metadata(order):
    require(isinstance(order, dict))
    meta = {k: order.get(k) for k in META_FIELDS if k != "items"}
    meta["items"] = line_items(order.get("itemSummary"))
    return validate_metadata(meta)


def changed(private, fields, target=None, clock=None):
    order = copy.deepcopy(private["order"])
    require(order["status"] not in TERMINAL_STATES, "종료된 판매 기록은 수정할 수 없습니다.")
    require(set(fields) <= set(EDITABLE) | {"costTotal", "expenseTotal", "costConfirmed", "profitMode", "commissionBps"})
    for name, value in fields.items():
        if name in EDITABLE:
            require(isinstance(value, str) and len(value) <= EDITABLE[name]
                    and not any(ord(c) < 32 and (name != "note" or c not in "\n\t") for c in value),
                    "고객·배송 정보를 확인해주세요.")
            value = value.strip()
            if name == "customerPhone":
                require(re.fullmatch(r"0[0-9-]{8,19}", value), "전화번호 형식을 확인해주세요.")
                require(not private.get("quoteDocumentJson") or value == order.get(name),
                        "발행 견적서의 고객 전화번호는 유지해야 합니다.")
            if name == "customerEmail":
                require(not value or re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", value), "이메일 형식을 확인해주세요.")
        order[name] = value
    stamp = clock if clock is not None else now_ms()
    require(stamp >= order["updatedAt"], "PC 시간을 확인해주세요.")
    if target is not None:
        if target == "구매취소":
            require(order["status"] in ("입금확인", "구매확정"), "배송 완료 전 입금확인 건만 구매취소할 수 있습니다.")
            if not order["paidAt"]:
                order["paidAt"] = stamp
        else:
            require(target == "배송완료" and NEXT.get(order["status"]) == target, "입금확인 다음에는 배송완료만 처리할 수 있습니다.")
            require(order["costConfirmed"], "배송완료 전에 원가와 비용을 확인해주세요.")
            if not order["paidAt"]:
                order["paidAt"] = stamp
            order["deliveredAt"] = stamp
            order["completedAt"] = stamp
        order["status"] = target
    order["revision"] += 1
    order["updatedAt"] = stamp
    order["orderId"] = None
    result = copy.deepcopy(private)
    result["order"] = order
    metadata(order)
    return result


def profit(meta):
    if not meta["costConfirmed"]:
        return None
    return (meta["totalPrice"] * meta["commissionBps"] // 10000 - meta["expenseTotal"]
            if meta["profitMode"] == "commission"
            else meta["totalPrice"] - meta["costTotal"] - meta["expenseTotal"])


def sale_expired(meta, clock):
    if meta["status"] not in TERMINAL_STATES:
        return False
    stamp = meta["completedAt"] if meta["status"] in ("배송완료", "판매완료") else meta["updatedAt"]
    date = datetime.fromtimestamp(stamp / 1000, timezone.utc)
    try:
        deadline = date.replace(year=date.year + 5)
    except ValueError:
        deadline = date.replace(year=date.year + 5, month=3, day=1)
    return deadline.timestamp() * 1000 <= clock


def validate_request(outer, plain):
    require(isinstance(plain, dict))
    require(valid_id(outer["requestId"]) and plain.get("requestId") == outer["requestId"])
    require(isinstance(plain.get("customerName"), str) and 2 <= len(plain["customerName"]) <= 50)
    require(isinstance(plain.get("customerPhone"), str) and re.fullmatch(r"0[0-9-]{8,19}", plain["customerPhone"]))
    require(isinstance(plain.get("region"), str) and 0 <= len(plain["region"]) <= 100)
    require(isinstance(plain.get("items"), list) and 1 <= len(plain["items"]) <= 20)
    for item in plain["items"]:
        require(isinstance(item, dict) and isinstance(item.get("species"), str) and 1 <= len(item["species"]) <= 80)
        require(isinstance(item.get("specification", ""), str) and len(item.get("specification", "")) <= 120)
        integer(item.get("quantity"), 1, 1000000)
    require(isinstance(plain.get("message", ""), str) and len(plain.get("message", "")) <= 1000)
    require(isinstance(plain.get("customerEmail", ""), str) and len(plain.get("customerEmail", "")) <= 254)
    email, channel = plain.get("customerEmail", ""), plain.get("replyChannel", "sms")
    require(not email or re.fullmatch(r"[^\s@]+@[^\s@]+\.[^\s@]+", email))
    require(channel in ("sms", "email", "both") and (channel == "sms" or email))
    require(type(plain.get("formalQuoteRequested", False)) is bool)
    require(isinstance(plain.get("recipientName", ""), str) and len(plain.get("recipientName", "")) <= 100)
    return plain
