import base64
import copy
import json
import re
import uuid
from datetime import datetime, timezone
from pathlib import Path

from .api import DEFAULT_URL, ScriptApi
from .crypto import canonical, digest, customer_digest, SellerKey
from .domain import (changed, integer, metadata, now_ms, require, valid_id, validate_metadata, validate_request, sale_expired)
from .errors import AuthenticationError, UserError, ConflictError
from .commerce import (validate_draft, order_from_draft, quote_document, tracking_fields,
                       tracking_number, normalize_tracking, quote_delivery_fingerprint)


class Manager:
    def __init__(self, vault, api=None):
        self.vault = vault
        self.credentials = vault.credentials()
        # Keep both legacy credentials and the negotiated common key in the
        # existing Windows-protected vault, without replacing business records.
        self.seller = SellerKey(self.credentials["privateKey"], self.credentials["publicKey"]) if self.credentials.get("privateKey") else None
        self.api = api or ScriptApi(self.credentials.get("url", DEFAULT_URL), self.credentials["deviceToken"],
                                   connection_key=self.credentials.get("connectionKey", "")
                                   if self.credentials.get("authMode") == "shared-key-v1"
                                   and self.credentials.get("connectionKeyUrl") == self.credentials.get("url", DEFAULT_URL) else "")
        self.request_snapshot = False
        self.quote_read_mode = None
        self.copy_error = ""
        self.inbox_requests = []  # Server results from this session only.

    @property
    def using_default_server(self):
        return self.api.url == DEFAULT_URL

    def reset_to_default_server(self):
        """User-approved destructive reset for PCs pinned to an obsolete Apps Script deployment."""
        self.credentials = self.vault.reset_workspace(DEFAULT_URL)
        self.seller = SellerKey(self.credentials["privateKey"], self.credentials["publicKey"]) if self.credentials.get("privateKey") else None
        self.api = ScriptApi(DEFAULT_URL, self.credentials["deviceToken"])
        self.request_snapshot = False
        self.quote_read_mode = None
        self.copy_error = ""
        self.inbox_requests = []
        return self.view()

    @property
    def request_copy_path(self):
        return self.vault.path.with_name("나무팜-견적요청.xlsx")

    @property
    def confirmed_quotes_path(self):
        return self.vault.path.with_name("나무팜-확정견적.xlsx")

    def _local_quotes(self):
        from .confirmed_excel import read_confirmed_quotes, write_confirmed_quotes
        if not self.confirmed_quotes_path.exists():
            require(not self.vault.preference("confirmed_excel_initialized", False),
                    "확정 견적 엑셀을 찾을 수 없습니다. 기존 파일을 원래 위치로 복원해주세요.")
            # One-time migration only of this PC's already confirmed drafts.
            # Never manufacture quotes from server-only rows or unconfirmed drafts.
            states = {r["requestId"]: r.get("status") for r in self.vault.rows("requests")}
            quotes = []
            for draft in self.vault.preference("drafts", []):
                status = states.get(draft.get("sourceRequestId")) if draft.get("sourceRequestId") else draft.get("manualStatus")
                if status in ("견적확정", "견적발송완료"):
                    quotes.append({**copy.deepcopy(draft), "quoteStatus": status})
            write_confirmed_quotes(self.confirmed_quotes_path, quotes)
        quotes = read_confirmed_quotes(self.confirmed_quotes_path)
        self.vault.save_preference("confirmed_excel_initialized", True)
        return quotes

    def _store_local_quote(self, draft, status):
        from .confirmed_excel import write_confirmed_quotes
        quotes = self._local_quotes()
        updated = {**copy.deepcopy(draft), "quoteStatus": status}
        quotes = [d for d in quotes if d["id"] != draft["id"] and
                  not (draft.get("sourceRequestId") and d.get("sourceRequestId") == draft["sourceRequestId"])]
        write_confirmed_quotes(self.confirmed_quotes_path, [updated, *quotes])

    def _finish_quote_write(self):
        """An acknowledged server write survives a locked Excel file or a restart."""
        pending = self.vault.preference("quote_write_pending")
        if pending and pending.get("acknowledged"):
            self._store_local_quote(pending["draft"], pending["status"])
            self.vault.save_preference("quote_write_pending", None)

    def _resume_quote_write(self):
        pending = self.vault.preference("quote_write_pending")
        if not pending:
            return
        if not pending.get("acknowledged"):
            try:
                response = self.api.call(pending["action"], **pending["payload"])
            except ConflictError:
                self.vault.save_preference("quote_write_pending", None)
                raise UserError("서버의 견적 상태가 변경되어 확정하지 못했습니다. 가져오기로 요청을 다시 확인해주세요.") from None
            source = pending["draft"]["sourceRequestId"]
            require(response.get("updated") is True and response.get("requestId") == source
                    and response.get("status") == pending["status"],
                    "견적 상태 저장을 확인하지 못했습니다. 가져오기로 같은 견적을 다시 확인해주세요.")
            pending["acknowledged"] = True
            self.vault.save_preference("quote_write_pending", pending)
            self.inbox_requests = [r for r in self.inbox_requests if r["requestId"] != source]
        self._finish_quote_write()

    def _write_quote_state(self, draft, status, action, payload):
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        # Check that the existing book is readable before changing the server.
        self._local_quotes()
        self.verify_server()
        pending = self.vault.preference("quote_write_pending")
        if pending:
            require(pending.get("action") == action and pending.get("payload") == payload,
                    "확인 대기 중인 견적이 있습니다. 먼저 가져오기로 저장을 완료해주세요.")
        else:
            self.vault.save_preference("quote_write_pending", {
                "draft": copy.deepcopy(draft), "status": status,
                "action": action, "payload": payload, "acknowledged": False})
        try:
            self._resume_quote_write()
        except OSError:
            # Server acknowledgement is already durable; view exposes the Excel warning.
            pass
        return self.local_copy_view()

    def _confirmed_source(self, draft):
        quote = next((d for d in self._local_quotes() if d["id"] == draft["id"] and
                      d["sourceRequestId"] == draft.get("sourceRequestId")), None)
        require(quote is not None and quote["quoteStatus"] in ("견적확정", "견적발송완료"),
                "로컬 엑셀의 확정 견적을 선택해주세요.")
        return quote

    def connect(self, include_sales=False):
        """Enroll this PC with the configured server without asking for a key or file."""
        pull = self.sync if include_sales else self.sync_requests
        if self.credentials.get("paired") is True:
            try:
                return pull()
            except AuthenticationError:
                # A restored vault can outlive the server's device registry.
                # Reuse its token and pending event IDs on this same server.
                pass
        info = self.verify_server()
        require(info.get("connectionAuth") == "auto-v1",
                "운영 Apps Script 배포가 이전 인증 방식입니다. 기존 서버의 Code.gs와 SecureCommerce.gs를 최신 main 코드로 교체하고, 동일 배포 ID의 새 버전으로 업데이트해주세요. 기존 자료는 유지됩니다.")
        result = self.api.call("connect_app", authMode="auto-v1", label="나무팜 PC")
        require(result.get("paired") is True and result.get("authMode") == "auto-v1",
                "서버에 자동 연결 기능을 배포해주세요.")
        credentials = {**self.credentials, "paired": True, "url": self.api.url}
        credentials.pop("connectionKey", None)
        credentials.pop("connectionKeyUrl", None)
        credentials.pop("authMode", None)
        self.vault.save_credentials(credentials)
        self.credentials = credentials
        self.api.connection_key = ""
        return pull()

    def connect_key(self, password, url=None, api_factory=ScriptApi):
        from .connection import connection_input
        url, key = connection_input(self.api.url if url is None else url, password)
        require(url == self.api.url or not (self.credentials.get("paired") or self.vault.rows("requests")
                or self.vault.rows("sales") or self.vault.pending() or self.vault.preference("drafts", [])),
                "기존 자료가 있는 보관함은 다른 서버로 바꿀 수 없습니다.")
        previous = self.api
        previous_snapshot = self.request_snapshot
        previous_read_mode = self.quote_read_mode
        self.api = api_factory(url, self.credentials["deviceToken"])
        try:
            self.verify_server()
            result = self.api.call("connect_app", authMode="shared-key-v1", connectionKey=key, label="나무팜 PC")
            require(result.get("paired") is True, "연결 암호를 확인해주세요.")
            require(result.get("authMode") == "shared-key-v1", "공통 연결 암호를 지원하는 서버 버전으로 업데이트해주세요.")
            credentials = {**self.credentials, "paired":True, "url":url}
            credentials.pop("connectionKey", None)
            credentials.pop("authMode", None)
            credentials.pop("connectionKeyUrl", None)
            credentials.update(connectionKey=key, connectionKeyUrl=url, authMode="shared-key-v1")
            self.vault.save_credentials(credentials)
            self.credentials = credentials
            self.api.connection_key = credentials.get("connectionKey", "")
        except Exception:
            self.api = previous
            self.request_snapshot = previous_snapshot
            self.quote_read_mode = previous_read_mode
            raise
        return self.sync()

    def local_copy_view(self):
        from .request_excel import write_request_copy
        value = self.view()
        try:
            write_request_copy(self.request_copy_path, value["requests"])
            self.copy_error = ""
        except OSError:
            self.copy_error = "앱 동기화 완료. 엑셀 사본 갱신은 실패했습니다. 열린 파일을 닫고 다시 동기화해주세요."
        value["copyError"] = " · ".join(filter(None, (value.get("copyError"), self.copy_error)))
        return value

    def verify_server(self):
        info = self.api.info()
        require(info.get("version") == 5 and info.get("connectionMode") == "device-auth"
                and info.get("customerDataReady") is True,
                "서버를 API v5로 업데이트해주세요. 기존 자료는 보존됩니다.")
        require(info.get("requestDeletionSync") is True,
                "PC 삭제 동기화를 지원하는 서버 코드로 Apps Script 배포를 업데이트해주세요.")
        self.request_snapshot = info.get("requestSnapshot") is True
        mode = info.get("quoteReadMode")
        self.quote_read_mode = mode if mode in ("annual-two-tabs", "legacy-storage") else None
        return info

    def pair(self, invite):
        require(isinstance(invite, str) and len(invite) == 64 and all(c in "0123456789abcdef" for c in invite),
                "관리자가 발급한 PC용 일회용 연결 코드를 입력해주세요.")
        self.verify_server()
        result = self.api.call("pair_device", invite=invite, label="나무팜 PC 관리")
        require(result.get("paired") is True, "PC 연결을 확인하지 못했습니다.")
        credentials = self.vault.credentials()
        credentials.update(paired=True, url=self.api.url)
        credentials.pop("connectionKey", None)
        credentials.pop("authMode", None)
        credentials.pop("connectionKeyUrl", None)
        self.vault.save_credentials(credentials)
        self.credentials = credentials
        self.api.connection_key = ""
        return self.sync()

    def view(self):
        self.vault.purge()
        requests = self.inbox_requests
        sales = self.vault.rows("sales")
        decoded_requests = [{**r, "personal": self.decode_request(r)} for r in requests]
        decoded_sales = []
        sale_error = ""
        for row in sales:
            try:
                private = self.decode_sale(row)
                decoded_sales.append({**row, "private": private})
            except UserError:
                sale_error = "일부 로컬 판매자료를 읽지 못했습니다. 판매관리에서 가져오기 또는 이전 자료 복원을 확인해주세요."
        quote_error = ""
        quotes = []
        try:
            self._finish_quote_write()
            quotes = self._local_quotes()
        except (UserError, OSError) as error:
            quote_error = (str(error) if isinstance(error, UserError) else
                           "서버 저장 확인 후 확정 견적 엑셀 갱신 대기 중입니다. 열린 엑셀을 닫고 가져오기를 눌러주세요.")
            try:
                quotes = self._local_quotes()
            except (UserError, OSError):
                pass
        registered = {r["metadata"]["orderNumber"] for r in sales} | set(self.vault.snapshot_marker().get("deletedSales", []))
        registered_sources = {r["private"]["order"].get("sourceRequestId") for r in decoded_sales
                              if r["private"]["order"].get("sourceRequestId")}
        # Paid orders retain the original local quotation as a readable history.
        active = [{**copy.deepcopy(d), "paymentRegistered": d["quoteStatus"] == "입금확인" or
                   "PC_" + d["id"] in registered or d.get("sourceRequestId") in registered_sources}
                  for d in quotes]
        drafts = self.vault.preference("drafts", [])
        active_drafts = [d for d in drafts if d["createdAt"] > now_ms() - 30 * 86400000
                         and not any(r["metadata"]["orderNumber"] == "PC_" + d["id"] for r in sales)]
        if active_drafts != drafts:
            self.vault.save_preference("drafts", active_drafts)
        drafts = active_drafts
        pending = self.vault.pending()
        recovery = self.vault.preference("quote_write_pending")
        if recovery:
            pending = [*pending, {"id": recovery["draft"]["id"], "action": "quote_write"}]
        return {"requests": sorted(decoded_requests, key=lambda r: r["sequence"], reverse=True),
                "sales": sorted(decoded_sales, key=lambda r: r["sequence"], reverse=True),
                "drafts": drafts, "confirmedQuotes": active, "quoteRecovery": recovery,
                "copyError": " · ".join(filter(None, (quote_error, sale_error))), "pending": pending,
                "paired": self.credentials.get("paired") is True}

    def save_draft(self, draft):
        try:
            return self._save_draft(draft)
        except OSError:
            raise UserError("확정 견적 엑셀을 갱신하지 못했습니다. 열린 엑셀을 닫고 다시 저장해주세요.") from None

    def _save_draft(self, draft):
        validate_draft(draft)
        require(not self.vault.preference("quote_write_pending"), "먼저 가져오기로 견적 저장 확인을 완료해주세요.")
        require(not any(r["metadata"]["orderNumber"] == "PC_" + draft["id"] for r in self.vault.rows("sales")), "구매 확정된 견적은 수정할 수 없습니다.")
        local = next((d for d in self._local_quotes() if d["id"] == draft["id"]), None)
        if local:
            self._store_local_quote(draft, local["quoteStatus"])
            return self.view()
        if not draft.get("sourceRequestId") and draft.get("manualStatus") in ("견적확정", "견적발송완료"):
            self._store_local_quote(draft, draft["manualStatus"])
            return self.view()
        drafts = self.view()["drafts"]
        require(draft["createdAt"] > now_ms() - 30 * 86400000, "30일이 지난 견적은 새로 작성해주세요.")
        drafts = [d for d in drafts if d["id"] != draft["id"]]
        require(len(drafts) < 500, "보관 중인 초안을 정리해주세요.")
        self.vault.save_preference("drafts", [copy.deepcopy(draft), *drafts])
        return self.view()

    def confirm_quote(self, draft):
        validate_draft(draft)
        source = draft.get("sourceRequestId", "")
        require(valid_id(source), "홈페이지 견적 요청에서 작성한 견적만 견적확정으로 처리할 수 있습니다.")
        pending = self.vault.preference("quote_write_pending")
        request = next((r for r in self.inbox_requests if r["requestId"] == source), None)
        require((request is not None and request.get("status", "신규") == "신규") or
                (pending is not None and pending["draft"]["sourceRequestId"] == source),
                "가져오기로 서버의 견적요청을 다시 확인해주세요.")
        pricing = {"items": [{"species": item["species"], "specification": item.get("specification", ""),
                              "quantity": item["quantity"], "unitPrice": item["unitPrice"]}
                             for item in draft["items"]],
                   "isPrepaid": draft["isPrepaid"], "shippingFee": draft["shippingFee"], "taxAmount": draft["taxAmount"]}
        require(len(pricing["items"]) <= 50 and all(i["quantity"] <= 1000000 for i in pricing["items"]),
                "견적은 최대 50품목, 품목별 수량은 1,000,000주까지 입력해주세요.")
        require(sum(i["quantity"] * i["unitPrice"] for i in pricing["items"]) +
                (draft["shippingFee"] if draft["isPrepaid"] else 0) + draft["taxAmount"] <= 2147483647,
                "세액을 포함한 견적 총액이 저장 범위를 초과합니다.")
        require(len(canonical(pricing).encode("utf-8")) <= 60000, "견적 품목이나 규격을 줄여주세요.")
        return self._write_quote_state(draft, "견적확정", "save_quote_pricing",
                                       {"requestId": source, "quotePricing": pricing})

    def mark_quote_sent(self, draft):
        validate_draft(draft)
        local = self._confirmed_source(draft)
        source = draft.get("sourceRequestId", "")
        if not source:
            updated = {**copy.deepcopy(draft), "manualStatus": "견적발송완료"}
            self._store_local_quote(updated, "견적발송완료")
            return self.view()
        require(local["quoteStatus"] in ("견적확정", "견적발송완료"))
        return self._write_quote_state(draft, "견적발송완료", "update_request_status",
                                       {"requestId": source, "status": "견적발송완료"})

    def quote_delivery_state(self, identifier):
        require(valid_id(identifier), "견적 식별자를 확인해주세요.")
        states = self.vault.preference("quote_delivery", {})
        value = states.get(identifier, {}) if isinstance(states, dict) else {}
        sms_message_id = str(value.get("smsMessageId", "") or "")
        sms_state = str(value.get("smsState", "") or "")
        if not sms_state and sms_message_id:
            # Older builds marked 2000 (accepted) as sent. Treat those records as
            # needing a final result check instead of assuming delivery success.
            sms_state = "pending"
        return {"smsSentAt": int(value.get("smsSentAt", 0) or 0),
                "emailSentAt": int(value.get("emailSentAt", 0) or 0),
                "smsTo": str(value.get("smsTo", "") or ""),
                "senderPhone": str(value.get("senderPhone", "") or ""),
                "smsMessageId": sms_message_id,
                "smsState": sms_state,
                "smsStatusCode": str(value.get("smsStatusCode", "") or ""),
                "smsStatusMessage": str(value.get("smsStatusMessage", "") or ""),
                "smsUpdatedAt": int(value.get("smsUpdatedAt", 0) or 0),
                "emailTo": str(value.get("emailTo", "") or ""),
                "senderEmail": str(value.get("senderEmail", "") or ""),
                "smsQuoteFingerprint": str(value.get("smsQuoteFingerprint", "") or ""),
                "emailQuoteFingerprint": str(value.get("emailQuoteFingerprint", "") or "")}

    def _save_quote_delivery_state(self, identifier, state):
        require(valid_id(identifier), "견적 식별자를 확인해주세요.")
        states = self.vault.preference("quote_delivery", {})
        states = copy.deepcopy(states) if isinstance(states, dict) else {}
        states[identifier] = copy.deepcopy(state)
        if len(states) > 1000:
            states = dict(list(states.items())[-1000:])
        self.vault.save_preference("quote_delivery", states)
        return self.quote_delivery_state(identifier)

    def _mark_quote_channel_sent(self, identifier, channel, **extra):
        require(valid_id(identifier) and channel in ("sms", "email"))
        state = self.quote_delivery_state(identifier)
        state[channel + "SentAt"] = now_ms()
        state.update(extra)
        return self._save_quote_delivery_state(identifier, state)

    def sms_profile(self):
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        info = self.verify_server()
        require(info.get("quoteSms") is True,
                "현재 PC가 연결된 Apps Script 웹앱은 문자 기능 이전 버전입니다. 기존 /exec 배포를 최신 코드로 새 버전 배포해주세요.")
        result = self.api.call("sms_profile")
        return {"provider": str(result.get("provider") or "SOLAPI"),
                "configured": result.get("configured") is True,
                "senderPhone": str(result.get("senderPhone") or "")}

    def _prepare_quote_delivery(self, draft):
        """Allow delivery from a current request without advancing its quote state."""
        source = draft.get("sourceRequestId", "")
        if not source:
            return
        local = next((q for q in self._local_quotes() if q["id"] == draft["id"]), None)
        if local:
            self._confirmed_source(draft)
            return
        request = next((r for r in self.inbox_requests if r["requestId"] == source), None)
        require(request is not None and request.get("status", "신규") == "신규",
                "가져오기로 서버의 견적요청을 다시 확인해주세요.")
        # Keep the sent input/identifier recoverable on reopen, even if sending fails.
        # Confirmed Excel and server status are changed only by confirm_quote().
        self.save_draft(draft)

    def payment_delivery_state(self, identifier):
        require(valid_id(identifier), "견적 식별자를 확인해주세요.")
        states = self.vault.preference("payment_delivery", {})
        return copy.deepcopy(states.get(identifier, {})) if isinstance(states, dict) else {}

    def _save_payment_delivery_state(self, identifier, state):
        require(valid_id(identifier), "견적 식별자를 확인해주세요.")
        states = self.vault.preference("payment_delivery", {})
        states = copy.deepcopy(states) if isinstance(states, dict) else {}
        states[identifier] = copy.deepcopy(state)
        self.vault.save_preference("payment_delivery", dict(list(states.items())[-1000:]))
        return self.payment_delivery_state(identifier)

    def send_payment_sms(self, draft, text):
        return self.send_quote_sms(draft, text, payment=True)

    def refresh_payment_sms_status(self, identifier):
        return self.refresh_quote_sms_status(identifier, payment=True)

    def send_quote_sms(self, draft, text, *, payment=False):
        validate_draft(draft)
        require(isinstance(text, str) and 1 <= len(text.strip()) <= 1000, "문자 내용을 1~1,000자로 입력해주세요.")
        recipient = draft.get("customerPhone", "").strip()
        require(recipient, "고객 연락처가 입력되지 않았습니다.")
        recipient_digits = "".join(ch for ch in recipient if ch.isdigit())
        source = draft.get("sourceRequestId", "")
        if payment:
            self._confirmed_source(draft)
        else:
            self._prepare_quote_delivery(draft)
        result = self.api.call("send_quote_sms", requestId=source, recipientPhone=recipient, text=text.strip())
        require(result.get("sent") is True and result.get("recipientPhone") == recipient_digits,
                "문자 전송 접수를 확인하지 못했습니다.")
        state = self.payment_delivery_state(draft["id"]) if payment else self.quote_delivery_state(draft["id"])
        state.update({"smsSentAt": 0, "smsTo": recipient_digits,
                      "senderPhone": result.get("senderPhone", ""),
                      "smsMessageId": result.get("messageId", ""),
                      "smsState": "pending",
                      "smsQuoteFingerprint": quote_delivery_fingerprint(draft),
                      "smsStatusCode": str(result.get("statusCode", "") or "2000"),
                      "smsStatusMessage": str(result.get("statusMessage", "") or "문자 발송을 접수했습니다."),
                      "smsUpdatedAt": now_ms()})
        delivery = (self._save_payment_delivery_state if payment else self._save_quote_delivery_state)(draft["id"], state)
        return {"delivery": delivery, "senderPhone": result.get("senderPhone", ""),
                "messageId": result.get("messageId", ""), "statusCode": result.get("statusCode", ""),
                "statusMessage": result.get("statusMessage", ""), "sentAt": result.get("sentAt", "")}

    def refresh_quote_sms_status(self, identifier, *, payment=False):
        state = self.payment_delivery_state(identifier) if payment else self.quote_delivery_state(identifier)
        message_id = state.get("smsMessageId", "")
        require(message_id, "확인할 문자 발송 기록이 없습니다.")
        result = self.api.call("quote_sms_status", messageId=message_id)
        require(result.get("checked") is True and result.get("messageId") == message_id,
                "문자 발송 결과를 확인하지 못했습니다.")
        sms_state = str(result.get("state") or "pending")
        status_code = str(result.get("statusCode") or "")
        status_message = str(result.get("statusMessage") or "문자 발송 결과를 확인하고 있습니다.")
        state.update({"smsState": sms_state, "smsStatusCode": status_code,
                      "smsStatusMessage": status_message, "smsUpdatedAt": now_ms()})
        if sms_state == "success":
            if not state.get("smsSentAt"):
                state["smsSentAt"] = now_ms()
        elif sms_state == "failed":
            state["smsSentAt"] = 0
        return (self._save_payment_delivery_state if payment else self._save_quote_delivery_state)(identifier, state)

    def email_profile(self):
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        info = self.verify_server()
        require(info.get("quoteEmail") is True,
                "현재 PC가 연결된 Apps Script 웹앱은 메일 기능 이전 버전입니다. 기존 /exec 배포를 최신 코드로 새 버전 배포해주세요.")
        require(info.get("quoteEmailSender") == "ssanai91@gmail.com",
                "Apps Script 메일 발신 계정을 ssanai91@gmail.com으로 배포해주세요.")
        result = self.api.call("email_profile")
        email = result.get("senderEmail", "")
        require(isinstance(email, str) and "@" in email and "." in email, "메일 발신 계정을 확인하지 못했습니다.")
        return {"senderName": result.get("senderName") or "나무팜농원",
                "senderEmail": email,
                "remainingDailyQuota": int(result.get("remainingDailyQuota", 0) or 0)}

    def send_quote_email(self, draft, subject, body_text, body_html, attachment_path=None):
        validate_draft(draft)
        recipient = draft.get("customerEmail", "").strip()
        require(recipient, "고객 이메일이 입력되지 않았습니다.")
        require(isinstance(subject, str) and 1 <= len(subject.strip()) <= 160, "메일 제목을 확인해주세요.")
        require(isinstance(body_text, str) and 1 <= len(body_text.strip()) <= 12000, "메일 내용을 확인해주세요.")
        require(isinstance(body_html, str) and 1 <= len(body_html.strip()) <= 24000, "메일 내용을 확인해주세요.")

        attachment_name = ""
        attachment_base64 = ""
        if attachment_path:
            path = Path(attachment_path)
            require(path.is_file() and path.suffix.lower() == ".pdf", "첨부할 공식견적서 PDF를 찾지 못했습니다.")
            size = path.stat().st_size
            require(0 < size <= 2 * 1024 * 1024, "공식견적서 PDF 첨부 용량은 2MB 이하여야 합니다.")
            attachment_name = path.name
            attachment_base64 = base64.b64encode(path.read_bytes()).decode("ascii")

        source = draft.get("sourceRequestId", "")
        self._prepare_quote_delivery(draft)
        result = self.api.call("send_quote_email", requestId=source, recipientEmail=recipient,
                               senderName=draft.get("issuer", {}).get("businessName") or "나무팜농원",
                               quoteNumber=draft.get("requestReceiptNumber") or ("PCQ_" + draft["id"][:8]),
                               subject=subject.strip(), bodyText=body_text.strip(), bodyHtml=body_html.strip(),
                               attachmentName=attachment_name, attachmentBase64=attachment_base64)
        require(result.get("sent") is True and result.get("recipientEmail") == recipient,
                "메일 전송 완료를 확인하지 못했습니다.")
        if attachment_name:
            require(result.get("attachmentName") == attachment_name, "공식견적서 첨부 전송을 확인하지 못했습니다.")
        delivery = self._mark_quote_channel_sent(draft["id"], "email", emailTo=recipient,
                                                 senderEmail=result.get("senderEmail", ""),
                                                 emailQuoteFingerprint=quote_delivery_fingerprint(draft))
        return {"delivery": delivery, "senderEmail": result.get("senderEmail", ""),
                "attachmentName": result.get("attachmentName", ""),
                "sentAt": result.get("sentAt", ""), "remainingDailyQuota": result.get("remainingDailyQuota", 0)}

    def request_notification_state(self):
        state = self.vault.preference("request_notifications")
        if not isinstance(state, dict):
            return None
        seen = state.get("seenSequence", 0)
        notified = state.get("notifiedSequence", 0)
        if not isinstance(seen, int) or seen < 0 or not isinstance(notified, int) or notified < 0:
            return None
        return {"seenSequence": seen, "notifiedSequence": notified}

    def save_request_notification_state(self, seen_sequence, notified_sequence):
        require(isinstance(seen_sequence, int) and seen_sequence >= 0)
        require(isinstance(notified_sequence, int) and notified_sequence >= 0)
        state = {
            "seenSequence": seen_sequence,
            "notifiedSequence": max(seen_sequence, notified_sequence),
        }
        self.vault.save_preference("request_notifications", state)
        return state

    def shipping_export_preferences(self):
        return self.vault.preference("shipping_export", {"sender": {}, "history": {}})

    def remember_shipping_export(self, sender, parcels):
        """Called only after the XLS file is committed. Does not change sale state."""
        from .shipping_excel import sender_settings
        preferences = self.shipping_export_preferences()
        clock = now_ms()
        history = {k: v for k, v in preferences.get("history", {}).items()
                   if v > clock - 30 * 86400000}
        history.update({number: clock for number in parcels})
        history = dict(sorted(history.items(), key=lambda p: p[1], reverse=True)[:2000])
        self.vault.save_preference("shipping_export", {"sender": sender_settings(sender), "history": history})

    def delete_draft(self, identifier):
        require(valid_id(identifier))
        from .confirmed_excel import write_confirmed_quotes
        require(not self.vault.preference("quote_write_pending"), "먼저 가져오기로 견적 저장을 완료해주세요.")
        write_confirmed_quotes(self.confirmed_quotes_path, [d for d in self._local_quotes() if d["id"] != identifier])
        self.vault.save_preference("drafts", [d for d in self.vault.preference("drafts", []) if d["id"] != identifier])
        return self.view()

    def confirm_draft(self, draft):
        validate_draft(draft)
        # Read cloud state before purchase confirmation; never replay a new order under a new ID.
        snapshot = self.sync()
        number = "PC_" + draft["id"]
        require(not any(r["metadata"]["orderNumber"] == number for r in snapshot["sales"]),
                "이미 판매 · 고객 관리로 이동한 견적입니다.")
        source = draft["sourceRequestId"]
        local = self._confirmed_source(draft)
        require(local["quoteStatus"] == "견적발송완료",
                "먼저 고객에게 견적을 발송하고 견적발송완료로 처리해주세요.")
        if source:
            require(not any(r["private"]["order"].get("sourceRequestId") == source for r in snapshot["sales"]),
                    "이미 판매 · 고객 관리로 이동한 문의입니다.")

        self.save_draft(draft)
        order = order_from_draft(draft)
        private = {"order": order, "quoteDocumentJson": canonical(quote_document(draft))}
        meta, event_id = metadata(order), str(uuid.uuid4())
        payload = {"eventId": event_id, "metadata": meta, "personalData": private,
                   "fingerprint": customer_digest(meta, private), "protocolVersion": 5, "sourceRequestId": source}
        require(len(canonical(payload).encode("utf-8")) <= 63000, "견적 품목이나 메모를 줄여주세요.")
        self.vault.queue({"id": event_id, "action": "save_sale", "payload": payload},
                         {**payload, "sequence": 0, "expired": False})
        return self.sync()

    def prepare_received_payment(self, draft):
        validate_draft(draft)
        view = self.sync()
        number = "PC_" + draft["id"]
        require(number not in self.vault.snapshot_marker().get("deletedSales", []),
                "이미 판매자료에서 삭제한 주문입니다. 같은 견적으로 다시 등록할 수 없습니다.")
        existing = next((r for r in view["sales"] if r["metadata"]["orderNumber"] == number or
                         (draft.get("sourceRequestId") and r["private"]["order"].get("sourceRequestId") == draft["sourceRequestId"])), None)
        if existing:
            return {"alreadyRegistered": True, "view": view}
        local = self._confirmed_source(draft)
        require(canonical(local["items"]) == canonical(draft["items"]),
                "확정 품목이 변경되었습니다. 묘목정보 수정에서 먼저 저장해주세요.")
        for field in ("customerName", "customerPhone", "customerEmail", "requestReceiptNumber", "note", "taxAmount"):
            require(local.get(field) == draft.get(field), "확정 견적이 변경되었습니다. 창을 다시 열어주세요.")
        return {"alreadyRegistered": False, "view": view}

    def confirm_received_payment(self, draft, *, sms_required):
        prepared = self.prepare_received_payment(draft)
        if prepared["alreadyRegistered"]:
            return prepared["view"]
        if sms_required:
            state = self.payment_delivery_state(draft["id"])
            require(state.get("smsState") == "success" and
                    state.get("smsQuoteFingerprint") == quote_delivery_fingerprint(draft),
                    "현재 주문·입금 금액의 문자 전송 성공을 확인한 뒤 판매 등록할 수 있습니다.")
        order = order_from_draft(draft)
        private = {"order": order, "quoteDocumentJson": canonical(quote_document(draft))}
        meta, event_id = metadata(order), str(uuid.uuid4())
        payload = {"eventId": event_id, "metadata": meta, "personalData": private,
                   "fingerprint": customer_digest(meta, private), "protocolVersion": 5,
                   "sourceRequestId": draft.get("sourceRequestId", "")}
        require(len(canonical(payload).encode("utf-8")) <= 63000, "주문 품목이나 메모를 줄여주세요.")
        self.vault.queue({"id": event_id, "action": "save_sale", "payload": payload,
                          "preserveQuote": True}, {**payload, "sequence": 0, "expired": False})
        return self.sync()

    def register_tracking(self, number, expected_revision, tracking):
        value = normalize_tracking(tracking)
        view = self.view()
        row = next((r for r in view["sales"] if r["metadata"]["orderNumber"] == number), None)
        require(row is not None and row["metadata"]["revision"] == expected_revision, "판매 기록을 다시 조회해주세요.")
        require(all(r["metadata"]["orderNumber"] == number or tracking_number(r["private"]["order"]) != value for r in view["sales"]), "다른 주문에 등록된 송장번호입니다.")
        return self.save_sale(number, expected_revision, tracking_fields(row["private"]["order"], value))

    def import_tracking(self, assignments):
        view = self.sync()
        # Revalidate the entire preview against the latest cloud snapshot before writing.
        seen = set()
        for number, value in assignments.items():
            value = normalize_tracking(value)
            require(value not in seen, "중복 송장번호를 확인해주세요.")
            seen.add(value)
            row = next((r for r in view["sales"] if r["metadata"]["orderNumber"] == number), None)
            require(row is not None, "주문이 변경되었습니다. 파일을 다시 확인해주세요.")
            tracking_fields(row["private"]["order"], value)
            old = tracking_number(row["private"]["order"])
            require(not old or old == value, "기존 송장과 다릅니다. 개별 주문에서 확인해주세요.")
            require(all(r["metadata"]["orderNumber"] == number or tracking_number(r["private"]["order"]) != value for r in view["sales"]), "다른 주문에 등록된 송장번호입니다.")
        done = 0
        for number, value in assignments.items():
            row = next(r for r in view["sales"] if r["metadata"]["orderNumber"] == number)
            if tracking_number(row["private"]["order"]) == value:
                done += 1
                continue
            try:
                view = self.register_tracking(number, row["metadata"]["revision"], value)
            except ConflictError:
                raise
            except UserError as error:
                raise UserError(f"{done}건 저장 확인 후 중단했습니다. 전송 대기를 동기화한 뒤 같은 파일로 재시도하세요. {error}") from None
            done += 1
        return view

    def restore_legacy(self, raw, password):
        seller = SellerKey.from_backup(raw, password)
        info = self.verify_server()
        require((info.get("sellerKey") or {}).get("keyId") == seller.key_id, "서버의 이전 자료와 다른 백업입니다.")
        credentials = self.vault.credentials()
        credentials.update(seller.material())
        self.vault.save_credentials(credentials)
        self.credentials, self.seller = credentials, seller
        return self.view()

    def open_legacy(self, envelope, kind, reference):
        require(self.seller is not None and envelope.get("keyId") == self.seller.key_id,
                "이전 버전 자료가 있습니다. 연결 · 설정의 ‘이전 자료 복원’에서 기존 백업을 선택해주세요.")
        return self.seller.open(envelope, kind, reference)

    def decode_request(self, row):
        if "encryptedRequest" in row:
            require("customerName" not in row, "고객 요청 형식이 중복되어 확인이 필요합니다.")
            plain = self.open_legacy(row["encryptedRequest"], "quote", row["requestId"])
        else:
            plain = row
        return validate_request(row, plain)

    def decode_sale(self, row):
        meta = validate_metadata(row["metadata"])
        require(valid_id(row["eventId"]))
        require(("personalData" in row) != ("encryptedPersonal" in row))
        if "personalData" in row:
            private = row["personalData"]
            require(customer_digest(meta, private) == row["fingerprint"])
        else:
            require(digest(meta, row["encryptedPersonal"]) == row["fingerprint"])
            private = self.open_legacy(row["encryptedPersonal"], "order", row["eventId"])
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

    def _pages(self, action, field, inbox_only=False):
        rows, cursor, references, deleted = [], 0, set(), set()
        snapshot_id, total, watermark, received = None, None, None, 0
        snapshot = field == "requests" and self.request_snapshot
        for _ in range(100):
            extra = {"snapshot":True, **({"snapshotId":snapshot_id} if snapshot_id else {})} if snapshot else {}
            response = self.api.call(action, afterSequence=cursor, **extra)
            page = response.get(field)
            require(isinstance(page, list) and len(page) <= 100 and type(response.get("hasMore")) is bool
                    and (page or response["hasMore"] is False))
            if snapshot:
                token = response.get("snapshotId")
                require(isinstance(token, str) and len(token) == 64 and all(c in "0123456789abcdef" for c in token))
                integer(response.get("total"), 0)
                integer(response.get("watermark"), 0)
                if snapshot_id is None:
                    snapshot_id, total, watermark = token, response["total"], response["watermark"]
                require((token, response["total"], response["watermark"]) == (snapshot_id,total,watermark),
                        "동기화 도중 시트가 변경되었습니다. 다시 동기화해주세요.")
                received += len(page)
                require(received <= total)
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
                    if inbox_only and row.get("status", "신규") != "신규":
                        continue
                    if row.get("expired") or row["expiresAt"] <= now_ms():
                        deleted.add(reference)
                        continue
                    self.decode_request(row)
                else:
                    if row.get("expired"):
                        if row.get("deleted") is True:
                            number = row.get("orderNumber", "")
                            require(isinstance(number, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,120}", number),
                                    "주문 삭제 기록을 확인할 수 없습니다.")
                            integer(row.get("revision"), 1)
                            deleted.add(number)
                        continue
                    self.decode_sale(row)
                    reference = row["metadata"]["orderNumber"]
                    require(reference not in references)
                    references.add(reference)
                rows.append(copy.deepcopy(row))
            if not response["hasMore"]:
                if snapshot:
                    require(received == total and cursor <= watermark, "시트 자료를 전부 받지 못해 기존 기록을 유지합니다.")
                    cursor = watermark
                return rows, cursor, deleted
        raise UserError("서버 기록이 너무 많아 나누어 조회해야 합니다. 기존 기록을 유지합니다.")

    def fetch_snapshot(self):
        self.verify_server()
        return self._fetch_verified_snapshot()

    def _fetch_verified_snapshot(self):
        requests, request_cursor, deleted = self._pages("list_requests", "requests")
        sales, sale_cursor, deleted_sales = self._pages("list_sales", "sales")
        old = self.vault.snapshot_marker()
        # Request data is a fresh authoritative server view, independent of local history.
        require(sale_cursor >= old["sales"], "서버 판매 순번이 이전보다 작아 기존 기록을 유지합니다.")
        sale_ids = {r["metadata"]["orderNumber"] for r in sales}
        require(set(old.get("deletedSales", [])) <= deleted_sales and not sale_ids.intersection(deleted_sales),
                "서버 주문 삭제 이력이 이전 상태여서 기존 자료를 유지합니다.")
        require(all(r["metadata"]["orderNumber"] in sale_ids or r["metadata"]["orderNumber"] in deleted_sales or sale_expired(r["metadata"], now_ms())
                    for r in self.vault.rows("sales")), "서버 판매 목록 일부가 누락되어 기존 기록을 유지합니다.")
        marker = {"requests": request_cursor, "sales": sale_cursor,
                  "deletedRequests": sorted(deleted), "deletedSales": sorted(deleted_sales), "quoteReadMode": self.quote_read_mode}
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
            elif operation["action"] == "delete_sale":
                require(response.get("deleted") is True and response.get("orderNumber") == payload["orderNumber"]
                        and response.get("revision") == payload["revision"],
                        "주문 삭제 성공을 확인하지 못해 로컬 자료와 전송 대기를 유지합니다.")
            else:
                require(response.get("closed") is True and response.get("requestId") == payload["requestId"],
                        "문의 삭제 응답을 확인하지 못했습니다. 전송 대기를 유지합니다.")
            if operation["action"] == "save_sale":
                number = payload["metadata"]["orderNumber"]
                quote = next((d for d in self._local_quotes() if number == "PC_" + d["id"]), None) if number.startswith("PC_") else None
                if quote and quote["quoteStatus"] != "입금확인" and not operation.get("preserveQuote"):
                    self.vault.save_preference("quote_write_pending", {
                        "draft": quote, "status": "입금확인", "acknowledged": True})
            elif operation["action"] == "close_request":
                self.inbox_requests = [r for r in self.inbox_requests if r["requestId"] != payload["requestId"]]
            self.vault.acknowledge(operation["id"])

    def sync_requests(self):
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        self.verify_server()
        recovery_error = ""
        try:
            self._resume_quote_write()
        except OSError:
            pass
        except AuthenticationError:
            raise
        except UserError as error:
            recovery_error = str(error)
        # A pending deletion may be retried without touching an unrelated sale edit.
        if any(op["action"] == "close_request" for op in self.vault.pending()):
            try:
                self.flush()
            except AuthenticationError:
                raise
            except UserError as error:
                recovery_error = str(error)
        requests, cursor, deleted = self._pages("list_requests", "requests", inbox_only=True)
        self.vault.replace_requests(requests, cursor, deleted,
                                    quote_read_mode=self.quote_read_mode, authoritative=True)
        self.inbox_requests = [r for r in requests if r.get("status", "신규") == "신규"]
        value = self.local_copy_view()
        value["copyError"] = " · ".join(filter(None, (value.get("copyError"), recovery_error)))
        return value

    def sync(self):
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        self.verify_server()
        self._resume_quote_write()
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
        self.inbox_requests = [r for r in requests if r.get("status", "신규") == "신규"]
        return self.local_copy_view()

    def save_sale(self, number, expected_revision, fields, target=None):
        require(not self.vault.pending(), "기존 전송 대기를 먼저 처리해주세요.")
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        row = next((r for r in self.vault.rows("sales") if r["metadata"]["orderNumber"] == number), None)
        require(row is not None and row["metadata"]["revision"] == expected_revision, "선택한 판매 기록을 새로 조회해주세요.")
        private = changed(self.decode_sale(row), fields, target)
        meta = metadata(private["order"])
        event_id = str(uuid.uuid4())
        payload = {"eventId": event_id, "metadata": meta, "personalData": private,
                   "fingerprint": customer_digest(meta, private), "protocolVersion": 5, "sourceRequestId": private["order"].get("sourceRequestId", "")}
        require(len(canonical(payload).encode("utf-8")) <= 63000, "전송할 기록이 너무 큽니다.")
        optimistic = {**row, "eventId": event_id, "metadata": meta, "personalData": private,
                      "fingerprint": payload["fingerprint"]}
        optimistic.pop("encryptedPersonal", None)
        self.vault.queue({"id": event_id, "action": "save_sale", "payload": payload}, optimistic)
        return self.sync()

    def close_request(self, request_id):
        require(valid_id(request_id))
        require(any(r["requestId"] == request_id for r in self.inbox_requests), "먼저 문의를 다시 조회해주세요.")
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        self.vault.queue({"id": str(uuid.uuid4()), "action": "close_request", "payload": {"requestId": request_id}})
        self.verify_server()
        self.flush()
        # A verified deletion acknowledgement is sufficient. A later list pull
        # must not turn a completed deletion into an apparent save failure.
        return self.local_copy_view()

    def delete_sale(self, number, expected_revision):
        require(not self.vault.pending(), "기존 전송 대기를 먼저 동기화해주세요.")
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        row = next((r for r in self.vault.rows("sales") if r["metadata"]["orderNumber"] == number), None)
        require(row is not None and row["metadata"]["revision"] == expected_revision,
                "선택한 판매 기록을 다시 조회해주세요.")
        info = self.verify_server()
        require(info.get("saleDeletionSync") is True,
                "주문 삭제를 지원하도록 운영 Apps Script를 최신 코드로 재배포해주세요.")
        event_id = str(uuid.uuid4())
        self.vault.queue({"id": event_id, "action": "delete_sale", "payload": {
            "eventId": event_id, "orderNumber": number, "revision": expected_revision}})
        # No optimistic deletion. Cache/outbox change together only after a verified ACK.
        self.flush()
        return self.local_copy_view()

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
        return self.local_copy_view()

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




