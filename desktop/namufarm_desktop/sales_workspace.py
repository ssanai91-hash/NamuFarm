"""Local sales work with explicit upload/download and conservative union merges."""
import copy
import uuid
import re
from pathlib import Path

from .crypto import canonical
from .domain import require, valid_id, integer
from .errors import UserError, ConflictError


class SalesWorkspace:
    @staticmethod
    def _operation_number(op):
        payload = op.get("payload", {})
        return payload.get("metadata", {}).get("orderNumber") or payload.get("orderNumber")

    def _sales_connection(self):
        self.verify_server()
        if self.credentials.get("paired") is not True:
            result = self.api.call("connect_app", authMode="auto-v1", label="나무팜 PC")
            require(result.get("paired") is True and result.get("authMode") == "auto-v1", "서버 연결을 확인해주세요.")
            self.credentials = {**self.credentials, "paired": True, "url": self.api.url}
            self.vault.save_credentials(self.credentials)

    def _save_paid_sale(self, draft, payload):
        quotes = self._local_quotes()
        # Store the paid status and the new sale in the very same SQLite transaction.
        for quote in quotes:
            if quote["id"] == draft["id"]:
                quote["quoteStatus"] = "입금확인"
        row = {**payload, "sequence": 0, "expired": False}
        self.decode_sale(row)
        source = draft.get("sourceRequestId")
        require(not any(r["metadata"]["orderNumber"] == payload["metadata"]["orderNumber"] or
                        (source and self.decode_sale(r)["order"].get("sourceRequestId") == source)
                        for r in self.vault.rows("sales")), "이미 판매관리에 등록한 견적입니다.")
        operations = [*self.vault.pending(), {"id": payload["eventId"], "action": "save_sale",
                      "payload": payload, "baseFingerprint": None, "preserveQuote": True}]
        self.vault.save_local_sales([*self.vault.rows("sales"), row], operations,
                                   self.vault.preference("hidden_sales", []), quotes=quotes)
        return self.view()

    def _merge_sales(self, incoming, *, server=False, imported_operations=(), marker=None, quotes=None, sold_labels=None):
        """Union by order number. Conflicting copies are retained locally, never overwritten."""
        existing = {r["metadata"]["orderNumber"]: r for r in self.vault.rows("sales")}
        operations = self.vault.pending()
        dirty = {self._operation_number(op) for op in operations if op["action"] == "save_sale"}
        hidden = set(self.vault.preference("hidden_sales", []))
        sources = {self.decode_sale(r)["order"].get("sourceRequestId"): n for n, r in existing.items()
                   if self.decode_sale(r)["order"].get("sourceRequestId")}
        seen, accepted, conflicts = set(), set(), []
        added = updated = skipped = 0
        immutable = ("items", "orderNumber", "orderDate", "purchasedAt", "totalQty", "totalPrice", "taxAmount", "shippingFee", "isPrepaid")
        for row in incoming:
            private = self.decode_sale(row)
            number = row["metadata"]["orderNumber"]
            require(number not in seen, "가져올 파일에 주문번호가 중복되었습니다.")
            seen.add(number)
            integer(row.get("sequence", 0), 0)
            if number in hidden:
                skipped += 1
                continue
            source = private["order"].get("sourceRequestId")
            if source and source in sources and sources[source] != number:
                conflicts.append(number)
                continue
            old = existing.get(number)
            if old:
                if old["fingerprint"] == row["fingerprint"]:
                    skipped += 1
                    continue
                # Pending local edits and differing Excel copies must be explicitly resolved.
                baseline = next((op.get("baseFingerprint") for op in operations
                                 if op["action"] == "save_sale" and self._operation_number(op) == number), None)
                if server and number in dirty and row["fingerprint"] == baseline:
                    skipped += 1
                    continue
                if number in dirty or not server:
                    conflicts.append(number)
                    continue
                if row["metadata"]["revision"] < old["metadata"]["revision"]:
                    skipped += 1
                    continue
                if (row["metadata"]["revision"] == old["metadata"]["revision"] or
                        any(canonical(row["metadata"][k]) != canonical(old["metadata"][k]) for k in immutable) or
                        source != self.decode_sale(old)["order"].get("sourceRequestId")):
                    conflicts.append(number)
                    continue
                updated += 1
            else:
                added += 1
            existing[number] = copy.deepcopy(row)
            accepted.add(number)
            if source:
                sources[source] = number
        identifiers = {op["id"] for op in operations}
        for op in imported_operations:
            if self._operation_number(op) in accepted and op["id"] not in identifiers:
                operations.append(copy.deepcopy(op))
                identifiers.add(op["id"])
        self.vault.save_local_sales(list(existing.values()), operations, hidden, marker=marker, quotes=quotes, sold_labels=sold_labels)
        self.transfer_notice = f"추가 {added}건 · 갱신 {updated}건 · 유지 {skipped}건"
        if conflicts:
            self.transfer_notice += " · 충돌로 기존 자료 유지: " + ", ".join(conflicts[:10])
            if len(conflicts) > 10:
                self.transfer_notice += f" 외 {len(conflicts) - 10}건"

    def download_sales(self, *, connected=False):
        if not connected:
            self._sales_connection()
        rows, cursor, deleted = self._pages("list_sales", "sales")
        self.vault.backup("download_sales", min_interval=60 * 60)
        # Missing or remotely deleted rows never erase a local order during a union download.
        marker = {**self.vault.snapshot_marker(), "sales": cursor, "deletedSales": sorted(deleted)}
        self._merge_sales(rows, server=True, marker=marker)
        self._prefetch_sold_numbers()
        return self.local_copy_view()

    def upload_sales(self):
        self._sales_connection()
        remote, _, deleted = self._pages("list_sales", "sales")
        remote = {r["metadata"]["orderNumber"]: r for r in remote}
        local = {r["metadata"]["orderNumber"]: r for r in self.vault.rows("sales")}
        pending = [op for op in self.vault.pending() if op["action"] == "save_sale"]
        require(not any(op["action"] == "delete_sale" for op in self.vault.pending()),
                "이전 버전의 서버 삭제 대기가 있습니다. 로컬에서 해당 주문을 삭제하면 서버 삭제 대기를 취소합니다.")
        queued = {self._operation_number(op) for op in pending}
        # Imported revision-1 rows can be backed up even if the originating PC had no journal.
        for number, row in local.items():
            if number in queued:
                continue
            old = remote.get(number)
            if old and old["fingerprint"] == row["fingerprint"]:
                continue
            require(old is None and number not in deleted and row["metadata"]["revision"] == 1,
                    f"주문 {number}: 서버 내용이 다르거나 복원할 변경 이력이 없습니다. 서버로부터 가져오기로 비교해주세요. 로컬 자료는 유지됩니다.")
            payload = {key: row[key] for key in ("eventId", "metadata", "fingerprint")}
            payload.update(personalData=self.decode_sale(row), protocolVersion=5,
                           sourceRequestId=self.decode_sale(row)["order"].get("sourceRequestId", ""))
            pending.append({"id": row["eventId"], "action": "save_sale", "payload": payload,
                            "baseFingerprint": None, "preserveQuote": True})
        # Preflight the entire ordered journal before the first write. The server still
        # checks each revision under its lock to catch changes made after this read.
        simulated = dict(remote)
        remote_sources = {self.decode_sale(r)["order"].get("sourceRequestId"): n for n, r in remote.items()
                          if self.decode_sale(r)["order"].get("sourceRequestId")}
        acknowledged = set()
        for op in pending:
            payload = op["payload"]
            self.decode_sale(payload)
            number = self._operation_number(op)
            old = simulated.get(number)
            meta = payload["metadata"]
            source = payload.get("sourceRequestId")
            require(not source or remote_sources.get(source, number) == number,
                    f"같은 견적요청이 다른 주문으로 등록되어 있습니다: {number}")
            if old and old["fingerprint"] == payload["fingerprint"]:
                acknowledged.add(op["id"])
                continue
            # An ACK can be lost while later events from the same workbook were applied.
            later = next((p for p in pending if self._operation_number(p) == number and old and
                          p["payload"]["fingerprint"] == old["fingerprint"] and
                          p["payload"]["metadata"]["revision"] >= meta["revision"]), None)
            if later:
                acknowledged.add(op["id"])
                continue
            if not (number not in deleted and
                    ((old is None and meta["revision"] == 1 and op.get("baseFingerprint") is None) or
                     (old is not None and meta["revision"] == old["metadata"]["revision"] + 1 and
                      op.get("baseFingerprint") == old["fingerprint"]))):
                self.sales_conflict_number = number
                raise ConflictError(f"주문 {number}: 다른 PC의 수정과 충돌하여 업로드를 중단했습니다. 양쪽 자료는 유지됩니다. 연결·설정의 동시 수정 해결에서 비교할 수 있습니다.")
            simulated[number] = payload
            if source:
                remote_sources[source] = number
        # Persist every event before attempting a network write, including imported rows.
        other = [op for op in self.vault.pending() if op["action"] != "save_sale"]
        self.vault.save_local_sales(list(local.values()), [*other, *pending], self.vault.preference("hidden_sales", []))
        sent = 0
        for op in pending:
            if op["id"] not in acknowledged:
                payload = op["payload"]
                try:
                    response = self.api.call("save_sale", **payload)
                except ConflictError:
                    self.sales_conflict_number = self._operation_number(op)
                    raise
                require(response.get("saved") is True and response.get("orderNumber") == self._operation_number(op)
                        and response.get("revision") == payload["metadata"]["revision"]
                        and response.get("fingerprint") == payload["fingerprint"],
                        "서버 저장 응답이 일치하지 않습니다. 전송 대기를 유지합니다.")
            self.vault.acknowledge(op["id"])
            sent += 1
        self.transfer_notice = f"서버에 올리기 완료 · 변경 {sent}건 · 로컬 삭제는 서버에 반영하지 않습니다."
        self._prefetch_sold_numbers()
        return self.local_copy_view()

    def export_local(self, folder):
        from .confirmed_excel import write_confirmed_quotes
        from .sales_excel import write_sales
        folder = Path(folder)
        folder.mkdir(parents=True, exist_ok=True)
        view = self.view()
        self.vault.backup("export_local")
        write_confirmed_quotes(folder / "나무팜-확정견적.xlsx", self._local_quotes())
        write_sales(folder / "나무팜-판매관리.xlsx", self.vault.rows("sales"), self.vault.pending(), self.api.url, view["sales"])
        self.transfer_notice = f"로컬 내보내기 완료 · {folder}"
        return self.view()

    def import_local(self, paths):
        from .confirmed_excel import read_confirmed_quotes
        from .sales_excel import read_sales
        require(isinstance(paths, (list, tuple)) and 1 <= len(paths) <= 2, "확정견적·판매관리 파일을 선택해주세요.")
        sales, quotes, operations = [], [], []
        sold_labels = dict(self.vault.preference("sold_display_labels", {}))
        for path in paths:
            # Detect the payload, rather than trusting the editable filename.
            try:
                data = read_sales(path)
            except UserError:
                quotes.extend(read_confirmed_quotes(path))
                continue
            require(data.get("serverUrl") == self.api.url, "다른 서버의 판매자료는 가져올 수 없습니다.")
            labels = data.get("soldLabels", {})
            require(isinstance(labels, dict), "엑셀의 판매번호를 확인해주세요.")
            for number, display in labels.items():
                require(isinstance(number, str) and number.startswith("PC_") and valid_id(number[3:])
                        and isinstance(display, str) and re.fullmatch(r"SOLD-\d{8}-\d{4,}", display)
                        and sold_labels.get(number, display) == display, "엑셀의 판매번호가 기존 자료와 다릅니다.")
                sold_labels[number] = display
            require(len(set(sold_labels.values())) == len(sold_labels), "엑셀의 판매번호가 중복되었습니다.")
            sales.extend(data["sales"])
            operations.extend(data["operations"])
        op_ids = set()
        for op in operations:
            require(isinstance(op, dict) and op.get("action") == "save_sale" and valid_id(op.get("id"))
                    and isinstance(op.get("payload"), dict) and op["id"] == op["payload"].get("eventId")
                    and op["id"] not in op_ids, "엑셀의 변경 이력을 확인해주세요.")
            self.decode_sale(op["payload"])
            require(op["payload"].get("sourceRequestId") == self.decode_sale(op["payload"])["order"].get("sourceRequestId", ""))
            op_ids.add(op["id"])
        # Each restored journal must end at its exported row; otherwise a stale
        # event could silently upload a different value than the one just imported.
        exported = {row["metadata"]["orderNumber"]: row for row in sales}
        chains = {}
        for op in operations:
            number = self._operation_number(op)
            require(number in exported and "baseFingerprint" in op, "엑셀의 주문과 변경 이력이 일치하지 않습니다.")
            previous = chains.get(number)
            if previous:
                require(op["baseFingerprint"] == previous["fingerprint"] and
                        op["payload"]["metadata"]["revision"] == previous["metadata"]["revision"] + 1,
                        "엑셀 변경 이력이 연속되지 않습니다.")
            chains[number] = op["payload"]
        require(all(payload["fingerprint"] == exported[number]["fingerprint"]
                    for number, payload in chains.items()), "엑셀의 최종 주문과 변경 이력이 다릅니다.")
        # Validate all input before the first write.
        for row in sales:
            self.decode_sale(row)
        local_quotes = self._local_quotes()
        conflicts = []
        for quote in quotes:
            old = next((q for q in local_quotes if q["id"] == quote["id"] or
                        (quote.get("sourceRequestId") and q.get("sourceRequestId") == quote["sourceRequestId"])), None)
            if old:
                if canonical(old) != canonical(quote):
                    conflicts.append(quote.get("requestReceiptNumber") or quote["id"])
            else:
                local_quotes.append(quote)
        self.vault.backup("import_local")
        self._merge_sales(sales, imported_operations=operations, quotes=local_quotes, sold_labels=sold_labels)
        if conflicts:
            self.transfer_notice += " · 견적 충돌로 기존 자료 유지: " + ", ".join(conflicts[:10])
        return self.local_copy_view()

