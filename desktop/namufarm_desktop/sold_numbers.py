"""Reserve disjoint daily sale-number blocks, then consume them without network I/O."""
import copy
import re
import uuid
from datetime import datetime

from .domain import SEOUL, require, integer
from .errors import UserError


class SoldNumbers:
    def _sale_for_draft(self, draft):
        source = draft.get("sourceRequestId")
        return next((row for row in self.vault.rows("sales")
                     if row["metadata"]["orderNumber"] == "PC_" + draft["id"] or
                     self.decode_sale(row)["order"].get("sourceDraftId") == draft["id"] or
                     (source and self.decode_sale(row)["order"].get("sourceRequestId") == source)), None)

    def _sold_state(self):
        state = self.vault.preference("sold_numbers", {"url": self.api.url, "assignments": {}})
        require(state.get("url") == self.api.url, "판매번호를 예약한 서버 주소를 확인해주세요.")
        return copy.deepcopy(state)

    def reserve_sold_numbers(self):
        day = datetime.now(SEOUL).strftime("%Y%m%d")
        state = self._sold_state()
        pool = state.get("pool", {})
        if pool.get("day") == day and pool.get("next", 1) <= pool.get("last", 0):
            return
        self._sales_connection()
        require(getattr(self, "sold_reservation_supported", False),
                "공통 판매번호 예약 기능이 필요합니다. 서버의 Code.gs·SecureCommerce.gs를 업데이트하고 SoldNumbers.gs를 추가한 뒤 기존 웹앱 배포를 새 버전으로 업데이트해주세요.")
        pending = state.get("pending")
        if not pending or pending.get("day") != day:
            pending = {"operationId": str(uuid.uuid4()), "day": day, "count": 20}
            state["pending"] = pending
            self.vault.save_preference("sold_numbers", state)
        response = self.api.call("reserve_sold_numbers", **pending)
        require(response.get("reserved") is True and response.get("day") == day and
                response.get("operationId") == pending["operationId"], "판매번호 예약 응답을 확인하지 못했습니다. 같은 예약으로 재시도합니다.")
        first = integer(response.get("first"), 1)
        last = integer(response.get("last"), first)
        require(last - first + 1 == pending["count"], "판매번호 예약 수량이 일치하지 않습니다.")
        state.update(pool={"day": day, "next": first, "last": last}, pending=None)
        self.vault.save_preference("sold_numbers", state)

    def _sold_number_for_draft(self, draft):
        existing = self._sale_for_draft(draft)
        if existing:
            return existing["metadata"]["orderNumber"]
        day = datetime.now(SEOUL).strftime("%Y%m%d")
        state = self._sold_state()
        assigned = state["assignments"].get(draft["id"], "")
        require(assigned not in self.vault.preference("hidden_sales", []), "이 PC에서 삭제한 판매 주문입니다.")
        if assigned.startswith("SOLD-" + day + "-"):
            return assigned
        self.reserve_sold_numbers()
        # Re-read after reserving. Counter advancement and the stable draft mapping
        # commit together; a retry after a crash always reuses the same number.
        with self.vault.mutex:
            state = self._sold_state()
            assigned = state["assignments"].get(draft["id"], "")
            if assigned.startswith("SOLD-" + day + "-"):
                return assigned
            pool = state["pool"]
            require(pool["day"] == day and pool["next"] <= pool["last"], "판매번호를 다시 예약해주세요.")
            number = f"SOLD-{day}-{pool['next']:04d}"
            pool["next"] += 1
            state["assignments"][draft["id"]] = number
            self.vault.save_preference("sold_numbers", state)
            return number

    def _prefetch_sold_numbers(self):
        if getattr(self, "sold_reservation_supported", False):
            try:
                self.sync_sold_labels()
                self.reserve_sold_numbers()
            except (UserError, OSError):
                self.transfer_notice = getattr(self, "transfer_notice", "") + " · 다음 입금확인 전에 판매번호 예약을 재시도해주세요."

    def sync_sold_labels(self):
        """Old immutable order keys keep their links; everyone sees one shared SOLD label."""
        if not getattr(self, "sold_labels_supported", False):
            return
        labels = self.vault.preference("sold_display_labels", {})
        pending = self.vault.preference("sold_display_pending")
        while True:
            if not pending:
                orders = []
                for row in self.vault.rows("sales"):
                    number = row["metadata"]["orderNumber"]
                    if number.startswith("PC_") and number not in labels:
                        stamp = row["metadata"].get("paidAt") or row["metadata"]["purchasedAt"]
                        day = datetime.fromtimestamp(stamp / 1000, SEOUL).strftime("%Y%m%d")
                        orders.append({"orderNumber": number, "day": day})
                if not orders:
                    return
                pending = {"operationId": str(uuid.uuid4()), "orders": orders[:20]}
                self.vault.save_preference("sold_display_pending", pending)
            response = self.api.call("sold_display_numbers", **pending)
            require(response.get("assigned") is True and response.get("operationId") == pending["operationId"]
                    and isinstance(response.get("numbers"), list), "기존 판매번호의 SOLD 변환 응답을 확인하지 못했습니다.")
            expected = {r["orderNumber"] for r in pending["orders"]}
            received = set()
            for item in response["numbers"]:
                require(isinstance(item, dict) and item.get("orderNumber") in expected and item["orderNumber"] not in received
                        and isinstance(item.get("soldNumber"), str) and re.fullmatch(r"SOLD-\d{8}-\d{4,}", item["soldNumber"]),
                        "기존 판매번호의 SOLD 변환 내용이 일치하지 않습니다.")
                labels[item["orderNumber"]] = item["soldNumber"]
                received.add(item["orderNumber"])
            require(received == expected and len(set(labels.values())) == len(labels), "판매번호 연결이 중복되거나 누락되었습니다.")
            self.vault.save_preference("sold_display_labels", labels)
            self.vault.save_preference("sold_display_pending", None)
            pending = None
