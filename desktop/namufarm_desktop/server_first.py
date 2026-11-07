"""Server reads are independent of unsent work. Recovery never resends a message."""
import copy
import hashlib
from functools import wraps

from .crypto import canonical
from .domain import now_ms, require
from .errors import AuthenticationError, UserError


def serialized(action):
    @wraps(action)
    def guarded(self, *args, **kwargs):
        with self._server_lock:
            return action(self, *args, **kwargs)
    return guarded


def review_key(scope, identifier, state):
    pending = state.get("pendingDelivery") or {}
    token = pending.get("id") or state.get("smsMessageId") or state.get("id") or canonical(state)
    return scope + ":" + identifier + ":" + hashlib.sha256(str(token).encode("utf-8")).hexdigest()


class ServerFirst:
    @serialized
    def retry_recovery_record(self, identifier):
        """Explicit recovery of saved operations, using their original IDs and revisions."""
        archive = self.vault.preference("server_recovery_archive", []) or []
        record = next((r for r in archive if r["id"] == identifier), None)
        require(record is not None, "복구 기록을 다시 선택해주세요.")
        body = record["body"]
        pending = body.get("quoteWrite")
        operations = body.get("operations", [])
        if body.get("kind") == "outbox":
            operations = [body["body"]]
        operations = [op for op in operations if op.get("action") == "save_sale"]
        require(pending or operations, "이 기록에는 다시 보낼 저장 요청이 없습니다. 보관된 내용을 확인해주세요.")
        self.verify_server()
        self.vault.backup("admin_recovery_retry")
        if pending:
            require(pending.get("action") == "revise_confirmed_quote" and
                    (pending.get("payload") or {}).get("operationId"),
                    "이전 형식의 견적입니다. 서버 내용을 확인하고 보관한 수정본을 참고해 다시 작성해주세요.")
            self._park_quote_write("보관한 견적 저장 재시도 전 현재 대기 보관")
            resumed = copy.deepcopy(pending)
            resumed["acknowledged"] = False
            self.vault.save_preference("quote_write_pending", resumed)
            self._resume_quote_write()
        for op in operations:
            payload = op["payload"]
            self.decode_sale(payload)
            existing = next((p for p in self.vault.pending() if p["id"] == op["id"]), None)
            require(existing is None or canonical(existing) == canonical(op), "다른 작업과 복구 번호가 겹칩니다.")
            if existing is None:
                self.vault.queue(op)
            result = self.api.call("save_sale", **payload)
            require(result.get("saved") is True and result.get("orderNumber") == self._operation_number(op)
                    and result.get("revision") == payload["metadata"]["revision"]
                    and result.get("fingerprint") == payload["fingerprint"], "서버 저장을 확인하지 못했습니다. 원래 작업 번호를 보관합니다.")
            self.vault.acknowledge(op["id"])
        self.vault.archive_and_update("관리자가 선택한 보관 기록 저장 확인", {"recoveryId": identifier})
        return self.server_sync()

    def _park_quote_write(self, reason):
        pending = self.vault.preference("quote_write_pending")
        if pending:
            self.vault.archive_and_update(reason, {"quoteWrite": pending},
                preferences={"quote_write_pending": None}, expected={"quote_write_pending": pending})

    def _prepare_quote_action(self, draft):
        pending = self.vault.preference("quote_write_pending")
        if not pending:
            return
        previous = pending.get("draft") or {}
        same = previous.get("id") == draft["id"] or (draft.get("sourceRequestId") and
               previous.get("sourceRequestId") == draft["sourceRequestId"])
        if same:
            self._resume_quote_write()
        else:
            self._park_quote_write("다른 견적 처리 계속 · 이전 저장 결과는 별도 보관")

    @serialized
    def server_sync(self):
        """Pull each server area independently. No upload or delivery retry here."""
        self.verify_server()
        errors, completed = [], []
        for title, action in (("견적", self.sync_requests), ("판매", self.download_sales)):
            try:
                action()
                completed.append(title)
            except AuthenticationError:
                raise
            except (UserError, OSError) as error:
                errors.append(title + ": " + str(error))
        info = self.server_info()
        if info.get("customerInquiryReady"):
            from .inquiries import InquiryService
            try:
                InquiryService(self).sync()
                completed.append("문의")
            except AuthenticationError:
                raise
            except (UserError, OSError) as error:
                errors.append("문의: " + str(error))
        self.transfer_notice = "서버 → PC 반영: " + ("·".join(completed) or "완료 없음")
        if errors:
            self.transfer_notice += " · 일부 확인 필요: " + " / ".join(errors)
        result = self.local_copy_view()
        result["syncErrors"] = errors
        return result

    def pending_items(self):
        """Metadata and payloads stay inside the existing encrypted local vault."""
        items = []
        closed = set(self.vault.preference("pending_review_closed", []) or [])
        quotes = {q["id"]: q.get("requestReceiptNumber") or q["id"] for q in self._local_quotes()}
        for op in self.vault.pending():
            payload = op.get("payload") or {}
            ref = (payload.get("metadata") or {}).get("orderNumber") or payload.get("requestId") or op["id"]
            items.append({"kind": "outbox", "key": op["id"], "title": "서버 저장 확인",
                          "reference": ref, "detail": op.get("action", ""), "body": op})
        pending = self.vault.preference("quote_write_pending")
        if pending:
            draft = pending.get("draft") or {}
            items.append({"kind": "quote", "key": draft.get("id", ""), "title": "견적 저장 확인",
                          "reference": draft.get("requestReceiptNumber") or draft.get("sourceRequestId", ""),
                          "detail": "서버 응답 확인 필요", "body": pending})
        for scope in ("quote_delivery", "payment_delivery", "sales_report_delivery"):
            saved = self.vault.preference(scope, {}) or {}
            states = {"report": saved} if scope == "sales_report_delivery" else saved
            for identifier, state in states.items():
                key = review_key(scope, identifier, state)
                if key in closed or not (state.get("pendingDelivery") or state.get("smsState") in ("sending", "pending", "unknown")):
                    continue
                channel = (state.get("pendingDelivery") or {}).get("channel", "sms")
                items.append({"kind": "delivery", "key": key, "scope": scope, "identifier": identifier,
                              "channel": channel, "title": "발송 결과 확인", "reference": quotes.get(identifier, identifier),
                              "detail": state.get("smsStatusMessage") or "발송 접수/완료 여부 확인 필요", "body": state})
        nursery = self.vault.preference("nursery-v1", {}) or {}
        for op in nursery.get("pending", []):
            items.append({"kind": "nursery", "key": op["id"], "title": "농원·묘목·재고 저장",
                          "reference": op.get("key", ""), "detail": op.get("kind", ""), "body": op})
        from .inquiries import InquiryStore
        inquiry = InquiryStore(self.vault)
        with self.vault.mutex, self.vault._db() as db:
            operations = {op["payload"]["requestId"]: op for key, blob in
                          db.execute("SELECT id,body FROM inquiry_operations")
                          for op in [self.vault._open(blob, "inquiry_operations:" + key)]}
            records = [self.vault._open(blob, "inquiry_records:" + key)
                       for key, blob in db.execute("SELECT id,body FROM inquiry_records")]
        by_id = {r["requestId"]: r for r in records}
        for identifier in set(operations) | set(by_id):
            row, op = by_id.get(identifier, {}), operations.get(identifier)
            delivery = row.get("delivery") or {}
            key = review_key("inquiry", identifier, delivery)
            if not op and (key in closed or delivery.get("state") not in ("sending", "pending", "unknown")):
                continue
            items.append({"kind": "inquiry", "key": key, "identifier": identifier, "title": "고객문의 결과 확인",
                          "reference": row.get("inquiryNumber", identifier), "detail": (op or {}).get("action", "발송 결과 확인"),
                          "body": {"operation": op, "record": row}})
        return items

    @serialized
    def check_pending_item(self, item):
        # A result check reads status. It must never replay a send or an old write.
        current = next((r for r in self.pending_items() if r["kind"] == item["kind"] and r["key"] == item["key"]), None)
        require(current and canonical(current["body"]) == canonical(item["body"]), "내역이 변경되었습니다. 새로고침해주세요.")
        if item["kind"] == "delivery":
            state, channel = item["body"], item["channel"]
            if state.get("pendingDelivery"):
                self.check_quote_delivery(item["identifier"], channel, payment=item["scope"] == "payment_delivery")
            else:
                self.refresh_quote_sms_status(item["identifier"], payment=item["scope"] == "payment_delivery")
        elif item["kind"] == "nursery":
            from .nursery import NurseryStore
            NurseryStore(self).pull()
        elif item["kind"] == "inquiry":
            from .inquiries import InquiryService
            service = InquiryService(self)
            service.sync()
            identifier = item["identifier"]
            row = service.store.read("records", identifier) or {}
            op = service.store.read("operations", identifier)
            payload = (op or {}).get("payload") or {}
            event = payload.get("eventId") or payload.get("deliveryId")
            known = {row.get("lastEventId"), (row.get("delivery") or {}).get("id")}
            known.update(h.get("id") for h in row.get("history", []))
            if event and event in known:
                service.store.write("operations", identifier, None)
            delivery = row.get("delivery") or {}
            if delivery.get("channel") == "sms" and delivery.get("messageId"):
                service._ack(identifier, self.api.call("inquiry_sms_status", requestId=identifier))
        elif item["kind"] == "quote":
            pending = item["body"]
            draft = pending["draft"]
            quote = self.server_quote(draft["sourceRequestId"])
            updated = self._linked(self._draft_from_server(quote, draft), quote)
            self._park_quote_write("관리자 서버 조회 · 서버 확정견적 사용")
            self._store_local_quote(updated, quote["status"])
        else:
            op = item["body"]
            if op["action"] in ("save_sale", "delete_sale"):
                rows, _, deleted = self._pages("list_sales", "sales")
                number = self._operation_number(op)
                row = next((r for r in rows if r["metadata"]["orderNumber"] == number), None)
                if op["action"] == "save_sale":
                    require(row and row["fingerprint"] == op["payload"]["fingerprint"],
                            "서버와 PC 수정이 다릅니다. 서버 동기화로 원본을 받거나 기록 보관 후 대기를 해제해주세요.")
                    self.vault.acknowledge(op["id"])
                else:
                    require(number in deleted, "서버 삭제를 확인하지 못했습니다. 서버 원본을 유지합니다.")
                    self.vault.acknowledge(op["id"])
            else:
                rows, _, deleted = self._pages("list_requests", "requests")
                request_id = op["payload"]["requestId"]
                require(request_id in deleted or not any(r["requestId"] == request_id for r in rows),
                        "서버에 접수가 남아 있습니다. 기록 보관 후 대기 해제 또는 해당 접수의 삭제를 다시 선택해주세요.")
                self.vault.archive_and_update("서버 접수 목록 확인 · 이전 삭제 대기 보관", item, operations=[op])
        return self.pending_items()

    @serialized
    def release_pending_item(self, item):
        """Explicit administrator action: archive intent, never label it as sent/saved."""
        current = next((r for r in self.pending_items() if r["kind"] == item["kind"] and r["key"] == item["key"]), None)
        require(current and canonical(current["body"]) == canonical(item["body"]), "내역이 변경되었습니다. 새로고침해주세요.")
        self.vault.backup("admin_pending_release")
        if item["kind"] == "quote":
            self._park_quote_write("관리자 확인 · 서버 내용 우선")
        elif item["kind"] == "outbox":
            self.vault.archive_and_update("관리자 확인 · 미반영 작업 보관", item, operations=[item["body"]])
        elif item["kind"] == "delivery":
            scope, identifier = item["scope"], item["identifier"]
            saved = self.vault.preference(scope, {}) or {}
            states = {"report": copy.deepcopy(saved)} if scope == "sales_report_delivery" else copy.deepcopy(saved)
            state = states[identifier]
            state.pop("pendingDelivery", None)
            state["adminReviewedAt"] = now_ms()
            state["deliveryResolution"] = "admin_archived"
            closed = list(self.vault.preference("pending_review_closed", []) or [])
            # Preserve both tokens: pending ID before removal and carrier message ID afterwards.
            closed = sorted(set([*closed, item["key"], review_key(scope, identifier, state)]))
            value = state if scope == "sales_report_delivery" else states
            self.vault.archive_and_update("관리자 발송내역 확인 · 대기 해제", item,
                preferences={scope: value, "pending_review_closed": closed}, expected={scope: saved})
        elif item["kind"] == "nursery":
            # Farm/stock operations depend on each other. Preserve them together, then pull.
            from .nursery import NurseryStore
            NurseryStore(self).pull()
        else:
            from .inquiries import InquiryStore
            store = InquiryStore(self.vault)
            identifier = item["identifier"]
            with self.vault.mutex, self.vault._db() as db:
                op = store._read(db, "operations", identifier)
                row = store._read(db, "records", identifier)
                require(canonical({"operation": op, "record": row}) == canonical(item["body"]), "문의 내역이 변경되었습니다.")
                self.vault._archive_in_db(db, "관리자 문의 발송내역 확인 · 대기 해제", item)
                store._write(db, "operations", identifier, None)
                scope = "workspace:pending_review_closed"
                saved = db.execute("SELECT value FROM config WHERE name=?", (scope,)).fetchone()
                closed = self.vault._open(saved[0], scope) if saved else []
                closed = sorted(set([*closed, item["key"]]))
                db.execute("INSERT OR REPLACE INTO config VALUES (?,?)", (scope, self.vault._seal(closed, scope)))
        self.transfer_notice = "선택한 대기는 복구 기록에 보관했습니다. 서버 저장·발송 성공으로 처리하지 않았습니다."
        return self.pending_items()
