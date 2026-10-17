"""Clearly labelled, read-only synthetic preview. Never opens the production vault."""
import copy
from datetime import datetime
from types import SimpleNamespace

from .api import DEFAULT_URL
from .commerce import new_draft, order_from_draft, TRACKING_PREFIX
from .domain import metadata, now_ms, SEOUL


def demo_data():
    names = ["가람조경", "푸른정원", "봄빛농원", "이음건설", "온유수목원", "다온마을", "새봄농장", "숲길정원"]
    species = ["에메랄드그린", "왕벚나무", "홍가시나무", "소나무", "황금사철", "블루애로우", "배롱나무", "측백나무"]
    states = ["구매확정", "입금확인", "입금확인", "구매확정", "입금확인", "배송확인", "판매완료", "판매완료"]
    quantities = [120, 50, 300, 25, 200, 80, 35, 150]
    prices = [18000, 45000, 7500, 80000, 6000, 27000, 42000, 9000]
    sales = []
    for index, name in enumerate(names):
        draft = new_draft()
        draft.update(customerName=name + " (예시)", customerPhone="01000000000", receiverName=name + " (예시)", zipNo="29000",
                     roadAddress="충청북도 옥천군 예시로 100", detailAddress="미리보기 주소", costConfirmed=True,
                     items=[dict(species=species[index], specification="상묘 / H 1.2m", quantity=quantities[index], unitPrice=prices[index], unitCost=prices[index]//2)],
                     note="예시 데이터 · 실제 주문이 아닙니다.")
        order = order_from_draft(draft)
        stamp = now_ms() - (index + 1) * 3600000
        order.update(orderNumber=f"NF-{datetime.now(SEOUL):%y%m%d}-{1082-index:04d}", status=states[index], purchasedAt=stamp, updatedAt=now_ms())
        if index != 0 and states[index] != "구매확정":
            order["paidAt"] = stamp + 1000
        if states[index] in ("배송확인", "판매완료"):
            order["deliveredAt"] = stamp + 2000
        if states[index] == "판매완료":
            order["completedAt"] = stamp + 3000
        if index in (2, 5, 6, 7):
            # Clearly synthetic numbers are shown only inside the labelled demo.
            order["note"] += "\n" + TRACKING_PREFIX + f"0000000000{index}"
        if index == 4:
            order["roadAddress"] = ""
        sales.append({"metadata": metadata(order), "private": {"order": order}, "sequence": 100-index})
    requests = []
    for index in range(5):
        requests.append({"requestId": f"00000000-0000-4000-8000-{index+1:012d}", "createdAt": datetime.now(SEOUL).isoformat(), "expiresAt": now_ms()+86400000,
                         "sequence": 5-index, "personal": {"customerName": names[index] + " (예시)", "customerPhone": "01000000000", "region": "충북 옥천",
                         "items": [dict(species=species[index], specification="H 1.2m", quantity=quantities[index])], "message": "출고 가능 일정 문의 (예시)", "formalQuoteRequested": True}})
    draft = new_draft(requests[0])
    draft["items"][0].update(unitPrice=18000, unitCost=9000)
    return {"requests": requests, "sales": sales, "drafts": [draft], "pending": [], "paired": False}


def show_demo(window):
    window.demo_mode = True
    window.timer.stop()
    window.manager = SimpleNamespace(api=SimpleNamespace(url=DEFAULT_URL))
    window.apply_view(demo_data())
    window.message.setText("미리보기 모드 · 모든 고객·주소·금액·송장번호는 예시입니다. 운영 시트에 연결하거나 저장하지 않습니다.")
    window.navigate(0)
