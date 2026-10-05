import copy
import json
import uuid
from datetime import datetime, timezone

from .api import DEFAULT_URL, ScriptApi
from .crypto import canonical, digest, SellerKey
from .domain import (changed, integer, metadata, now_ms, require, valid_id, validate_metadata, validate_request, sale_expired)
from .errors import UserError, ConflictError


class Manager:
    def __init__(self, vault, api=None):
        self.vault = vault
        self.credentials = vault.credentials()
        self.seller = SellerKey(self.credentials["privateKey"], self.credentials["publicKey"])
        self.api = api or ScriptApi(self.credentials.get("url", DEFAULT_URL), self.credentials["deviceToken"])

    def verify_server(self):
        info = self.api.info()
        key = info.get("sellerKey")
        require(isinstance(key, dict) and key.get("keyId") == self.seller.key_id
                and key.get("publicKey") == self.seller.public_encoded,
                "앱에서 등록한 보안키와 다른 서버입니다. 기존 백업과 서버 연결을 확인해주세요.")
        require(info.get("encryptionReady") is True, "앱의 서버 보안 연결을 먼저 완료해주세요.")
        require(info.get("requestDeletionSync") is True,
                "PC 삭제 동기화를 지원하는 서버 코드로 Apps Script 배포를 업데이트해주세요.")
        return info

    def pair(self, invite):
        require(isinstance(invite, str) and len(invite) == 64 and all(c in "0123456789abcdef" for c in invite),
                "관리자가 발급한 PC용 일회용 연결 코드를 입력해주세요.")
        self.verify_server()
        result = self.api.call("pair_device", invite=invite, label="나무팜 PC 관리")
        require(result.get("paired") is True, "PC 연결을 확인하지 못했습니다.")
        credentials = self.vault.credentials()
        credentials.update(paired=True, url=self.api.url)
        self.vault.save_credentials(credentials)
        self.credentials = credentials
        return self.sync()

    def view(self):
        self.vault.purge()
        requests = self.vault.rows("requests")
        sales = self.vault.rows("sales")
        decoded_requests = [{**r, "personal": validate_request(r, self.seller.open(r["encryptedRequest"], "quote", r["requestId"]))} for r in requests]
        decoded_sales = []
        for row in sales:
            private = self.decode_sale(row)
            decoded_sales.append({**row, "private": private})
        return {"requests": sorted(decoded_requests, key=lambda r: r["sequence"], reverse=True),
                "sales": sorted(decoded_sales, key=lambda r: r["sequence"], reverse=True),
                "pending": self.vault.pending(), "paired": self.credentials.get("paired") is True}

    def decode_sale(self, row):
        meta = validate_metadata(row["metadata"])
        require(valid_id(row["eventId"]) and digest(meta, row["encryptedPersonal"]) == row["fingerprint"])
        private = self.seller.open(row["encryptedPersonal"], "order", row["eventId"])
        require(isinstance(private, dict) and isinstance(private.get("order"), dict))
        require(canonical(metadata(private["order"])) == canonical(meta), "판매 기록과 고객정보가 일치하지 않습니다.")
        document = private.get("quoteDocumentJson")
        if document:
            require(isinstance(document, str))
            doc = json.loads(document)
            require(isinstance(doc, dict))
            require(doc.get("sourceQuoteNumber") == private["order"].get("sourceQuoteNumber")
                    and doc.get("customerPhone") == private["order"].get("customerPhone")
                    and doc.get("supplyAmount") == meta["totalPrice"] and doc.get("taxAmount") == meta["taxAmount"],
                    "발행 견적서와 판매 기록이 일치하지 않습니다.")
        return private

    def _pages(self, action, field):
        rows, cursor, references, deleted = [], 0, set(), set()
        for _ in range(100):
            response = self.api.call(action, afterSequence=cursor)
            page = response.get(field)
            require(isinstance(page, list) and len(page) <= 100 and type(response.get("hasMore")) is bool
                    and (page or response["hasMore"] is False))
            for row in page:
                require(isinstance(row, dict))
                integer(row.get("sequence"), 1)
                require(row["sequence"] > cursor)
                cursor = row["sequence"]
                require(type(row.get("expired", False)) is bool)
                if field == "requests":
                    require(valid_id(row.get("requestId")))
                    reference = row["requestId"]
                    try:
                        created = datetime.fromisoformat(row["createdAt"].replace("Z", "+00:00"))
                        require(created.tzinfo is not None and created.utcoffset().total_seconds() == 0)
                        require(0 < created.timestamp() * 1000 <= now_ms() + 300000)
                        integer(row.get("expiresAt"), 1)
                        require(abs(row["expiresAt"] - (int(created.timestamp() * 1000) + 30 * 86400000)) <= 1)
                    except (ValueError, KeyError, AttributeError):
                        raise UserError("접수 날짜를 확인할 수 없습니다.") from None
                    require(reference not in references)
                    references.add(reference)
                    if row.get("expired") or row["expiresAt"] <= now_ms():
                        deleted.add(reference)
                        continue
                    validate_request(row, self.seller.open(row["encryptedRequest"], "quote", reference))
                else:
                    if row.get("expired"):
                        continue
                    self.decode_sale(row)
                    reference = row["metadata"]["orderNumber"]
                    require(reference not in references)
                    references.add(reference)
                rows.append(copy.deepcopy(row))
            if not response["hasMore"]:
                return rows, cursor, deleted
        raise UserError("서버 기록이 너무 많아 나누어 조회해야 합니다. 기존 기록을 유지합니다.")

    def fetch_snapshot(self):
        self.verify_server()
        return self._fetch_verified_snapshot()

    def _fetch_verified_snapshot(self):
        requests, request_cursor, deleted = self._pages("list_requests", "requests")
        sales, sale_cursor, _ = self._pages("list_sales", "sales")
        old = self.vault.snapshot_marker()
        require(request_cursor >= old["requests"] and sale_cursor >= old["sales"], "서버 순번이 이전보다 작아 기존 기록을 유지합니다.")
        require(set(old["deletedRequests"]) <= deleted, "삭제된 문의가 서버에 다시 나타나 기존 기록을 유지합니다.")
        request_ids = {r["requestId"] for r in requests} | deleted
        require(all(r["requestId"] in request_ids for r in self.vault.rows("requests")), "서버 문의 목록 일부가 누락되어 기존 기록을 유지합니다.")
        sale_ids = {r["metadata"]["orderNumber"] for r in sales}
        require(all(r["metadata"]["orderNumber"] in sale_ids or sale_expired(r["metadata"], now_ms())
                    for r in self.vault.rows("sales")), "서버 판매 목록 일부가 누락되어 기존 기록을 유지합니다.")
        marker = {"requests": request_cursor, "sales": sale_cursor, "deletedRequests": sorted(deleted)}
        return requests, sales, marker

    def flush(self):
        for operation in self.vault.pending():
            payload = operation["payload"]
            response = self.api.call(operation["action"], **payload)
            if operation["action"] == "save_sale":
                require(response.get("saved") is True and response.get("orderNumber") == payload["metadata"]["orderNumber"]
                        and response.get("revision") == payload["metadata"]["revision"]
                        and response.get("fingerprint") == payload["fingerprint"],
                        "판매 저장 응답이 일치하지 않습니다. 전송 대기를 유지합니다.")
            else:
                require(response.get("closed") is True and response.get("requestId") == payload["requestId"],
                        "문의 삭제 응답을 확인하지 못했습니다. 전송 대기를 유지합니다.")
            self.vault.acknowledge(operation["id"])

    def sync(self):
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        self.verify_server()
        self.flush()
        requests, sales, marker = self._fetch_verified_snapshot()
        previous = {r["metadata"]["orderNumber"]: r for r in self.vault.rows("sales")}
        for row in sales:
            old = previous.get(row["metadata"]["orderNumber"])
            if old:
                require(row["metadata"]["revision"] >= old["metadata"]["revision"], "서버의 오래된 판매 기록으로 덮어쓰지 않습니다.")
                if row["metadata"]["revision"] == old["metadata"]["revision"]:
                    require(row["fingerprint"] == old["fingerprint"], "같은 버전의 판매 기록이 일치하지 않습니다.")
                for key in ("items", "orderNumber", "orderDate", "purchasedAt", "totalQty", "totalPrice", "taxAmount", "shippingFee", "isPrepaid"):
                    require(canonical(row["metadata"][key]) == canonical(old["metadata"][key]), "판매 원본 금액이나 품목이 변경되었습니다.")
        self.vault.replace_snapshot(requests, sales, marker)
        return self.view()

    def save_sale(self, number, expected_revision, fields, target=None):
        require(not self.vault.pending(), "기존 전송 대기를 먼저 처리해주세요.")
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        row = next((r for r in self.vault.rows("sales") if r["metadata"]["orderNumber"] == number), None)
        require(row is not None and row["metadata"]["revision"] == expected_revision, "선택한 판매 기록을 새로 조회해주세요.")
        private = changed(self.decode_sale(row), fields, target)
        meta = metadata(private["order"])
        event_id = str(uuid.uuid4())
        envelope = self.seller.seal(private, "order", event_id)
        payload = {"eventId": event_id, "metadata": meta, "encryptedPersonal": envelope,
                   "fingerprint": digest(meta, envelope), "sourceRequestId": private["order"].get("sourceRequestId", "")}
        require(len(canonical(payload).encode("utf-8")) <= 63000, "전송할 기록이 너무 큽니다.")
        optimistic = {**row, "eventId": event_id, "metadata": meta, "encryptedPersonal": envelope,
                      "fingerprint": payload["fingerprint"]}
        self.vault.queue({"id": event_id, "action": "save_sale", "payload": payload}, optimistic)
        return self.sync()

    def close_request(self, request_id):
        require(valid_id(request_id))
        require(any(r["requestId"] == request_id for r in self.vault.rows("requests")), "먼저 문의를 다시 조회해주세요.")
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        self.vault.queue({"id": str(uuid.uuid4()), "action": "close_request", "payload": {"requestId": request_id}})
        self.verify_server()
        self.flush()
        # A verified deletion acknowledgement is sufficient. A later list pull
        # must not turn a completed deletion into an apparent save failure.
        return self.view()

    def conflict_preview(self):
        pending = self.vault.pending()
        require(len(pending) == 1 and pending[0]["action"] == "save_sale", "판매 수정 충돌이 없습니다.")
        requests, sales, marker = self.fetch_snapshot()
        operation = pending[0]
        number = operation["payload"]["metadata"]["orderNumber"]
        remote = next((r for r in sales if r["metadata"]["orderNumber"] == number), None)
        require(remote is not None, "서버 기록이 만료되었거나 없습니다. 관리자 확인이 필요합니다.")
        require(remote["metadata"]["revision"] >= operation["payload"]["metadata"]["revision"],
                "서버 저장 상태가 아직 확정되지 않았습니다. 동기화를 재시도해주세요.")
        return {"operation": operation, "remote": remote, "requests": requests, "sales": sales, "marker": marker}

    def use_server_version(self, preview):
        pending = self.vault.pending()
        require(len(pending) == 1 and pending[0] == preview["operation"], "전송 대기가 변경되었습니다. 다시 확인해주세요.")
        # Re-read before discarding so another device's newer change is not replaced by an old preview.
        fresh = self.conflict_preview()
        require(fresh["remote"]["fingerprint"] == preview["remote"]["fingerprint"], "서버 기록이 다시 변경되었습니다. 충돌 내용을 새로 확인해주세요.")
        self.vault.discard_conflict(pending[0]["id"], fresh["requests"], fresh["sales"], fresh["marker"])
        return self.view()

    def report(self, unit, start, end):
        require(unit in ("day", "month", "year"))
        try:
            require(datetime.strptime(start, "%Y-%m-%d").strftime("%Y-%m-%d") == start
                    and datetime.strptime(end, "%Y-%m-%d").strftime("%Y-%m-%d") == end and start <= end,
                    "조회 기간을 확인해주세요.")
        except ValueError:
            raise UserError("조회 기간을 확인해주세요.") from None
        self.verify_server()
        self.flush()
        report = self.api.call("sales_report", granularity=unit, **{"from": start, "to": end}).get("report")
        require(isinstance(report, dict) and report.get("granularity") == unit and report.get("from") == start
                and report.get("to") == end and isinstance(report.get("buckets"), list))
        seen = set()
        for bucket in report["buckets"]:
            period = bucket.get("period")
            require(isinstance(period, str) and len(period) == {"day": 10, "month": 7, "year": 4}[unit] and period not in seen)
            require(start[:len(period)] <= period <= end[:len(period)])
            seen.add(period)
            for name in ("orders", "quantity", "sales", "tax", "cost", "expenses", "unknownProfitOrders"):
                integer(bucket.get(name))
            integer(bucket.get("knownProfit"), -9007199254740991)
            require(bucket["unknownProfitOrders"] <= bucket["orders"])
        return report
