import base64
import copy
import json
import uuid
from datetime import datetime, timezone
from pathlib import Path

from .api import DEFAULT_URL, ScriptApi
from .crypto import canonical, digest, customer_digest, SellerKey
from .domain import (changed, integer, metadata, now_ms, require, valid_id, validate_metadata, validate_request, sale_expired)
from .errors import UserError, ConflictError
from .commerce import (validate_draft, order_from_draft, quote_document, tracking_fields,
                       tracking_number, normalize_tracking)


class Manager:
    def __init__(self, vault, api=None):
        self.vault = vault
        self.credentials = vault.credentials()
        # The connection file is a one-time bootstrap. Keep the paired URL and
        # device token in the Windows-protected vault across restarts.
        self.seller = SellerKey(self.credentials["privateKey"], self.credentials["publicKey"]) if self.credentials.get("privateKey") else None
        self.api = api or ScriptApi(self.credentials.get("url", DEFAULT_URL), self.credentials["deviceToken"])
        self.request_snapshot = False
        self.copy_error = ""

    @property
    def using_default_server(self):
        return self.api.url == DEFAULT_URL

    def reset_to_default_server(self):
        """User-approved destructive reset for PCs pinned to an obsolete Apps Script deployment."""
        self.credentials = self.vault.reset_workspace(DEFAULT_URL)
        self.seller = SellerKey(self.credentials["privateKey"], self.credentials["publicKey"]) if self.credentials.get("privateKey") else None
        self.api = ScriptApi(DEFAULT_URL, self.credentials["deviceToken"])
        self.request_snapshot = False
        self.copy_error = ""
        return self.view()

    @property
    def request_copy_path(self):
        return self.vault.path.with_name("나무팜-견적요청.xlsx")

    def connect_file(self, raw, api_factory=ScriptApi):
        from .connection import read_connection
        url, key = read_connection(raw)
        require(url == self.api.url or not (self.credentials.get("paired") or self.vault.rows("requests")
                or self.vault.rows("sales") or self.vault.pending() or self.vault.preference("drafts", [])),
                "기존 자료가 있는 보관함은 다른 서버로 바꿀 수 없습니다.")
        previous = self.api
        self.api = api_factory(url, self.credentials["deviceToken"])
        try:
            info = self.verify_server()
            require(info.get("connectionFile") is True, "연결파일을 지원하는 서버로 Apps Script 배포를 업데이트해주세요.")
            result = self.api.call("connect_app", connectionKey=key, label="나무팜 PC")
            require(result.get("paired") is True, "연결파일을 확인해주세요.")
            credentials = {**self.credentials, "paired":True, "url":url}
            self.vault.save_credentials(credentials)
            self.credentials = credentials
        except Exception:
            self.api = previous
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
        value["copyError"] = self.copy_error
        return value

    def verify_server(self):
        info = self.api.info()
        require(info.get("version") == 5 and info.get("connectionMode") == "device-auth"
                and info.get("customerDataReady") is True,
                "구글시트 서버를 API v5로 업데이트해주세요. 기존 자료는 보존됩니다.")
        require(info.get("requestDeletionSync") is True,
                "PC 삭제 동기화를 지원하는 서버 코드로 Apps Script 배포를 업데이트해주세요.")
        self.request_snapshot = info.get("requestSnapshot") is True
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
        decoded_requests = [{**r, "personal": self.decode_request(r)} for r in requests]
        decoded_sales = []
        for row in sales:
            private = self.decode_sale(row)
            decoded_sales.append({**row, "private": private})
        drafts = self.vault.preference("drafts", [])
        # Drafts contain personal data and follow the 30-day unpurchased retention rule.
        deleted = set(self.vault.snapshot_marker()["deletedRequests"])
        active = [d for d in drafts if d["createdAt"] > now_ms() - 30 * 86400000 and d["sourceRequestId"] not in deleted
                  and not any(r["metadata"]["orderNumber"] == "PC_" + d["id"] for r in sales)]
        if active != drafts:
            self.vault.save_preference("drafts", active)
        return {"requests": sorted(decoded_requests, key=lambda r: r["sequence"], reverse=True),
                "sales": sorted(decoded_sales, key=lambda r: r["sequence"], reverse=True),
                "drafts": active, "pending": self.vault.pending(), "paired": self.credentials.get("paired") is True}

    def save_draft(self, draft):
        validate_draft(draft)
        require(not any(r["metadata"]["orderNumber"] == "PC_" + draft["id"] for r in self.vault.rows("sales")), "구매 확정된 견적은 수정할 수 없습니다.")
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
        request = next((r for r in self.vault.rows("requests") if r["requestId"] == source), None)
        require(request is not None, "견적 요청을 다시 동기화해주세요.")
        require(request.get("status", "신규") in ("신규", "견적확정"), "이미 판매 단계로 진행된 견적 요청입니다.")
        self.save_draft(draft)
        self.verify_server()
        response = self.api.call("update_request_status", requestId=source, status="견적확정")
        require(response.get("updated") is True and response.get("requestId") == source
                and response.get("status") == "견적확정", "견적확정 상태 저장을 확인하지 못했습니다.")
        return self.sync_requests()

    def mark_quote_sent(self, draft):
        validate_draft(draft)
        source = draft.get("sourceRequestId", "")
        require(valid_id(source), "홈페이지 견적 요청에서 작성한 견적만 견적발송완료로 처리할 수 있습니다.")
        request = next((r for r in self.vault.rows("requests") if r["requestId"] == source), None)
        require(request is not None, "견적 요청을 다시 동기화해주세요.")
        require(request.get("status", "신규") in ("견적확정", "견적발송완료"),
                "견적확정 상태에서 발송완료로 변경해주세요.")
        self.save_draft(draft)
        self.verify_server()
        response = self.api.call("update_request_status", requestId=source, status="견적발송완료")
        require(response.get("updated") is True and response.get("requestId") == source
                and response.get("status") == "견적발송완료", "견적발송완료 상태 저장을 확인하지 못했습니다.")
        return self.sync_requests()

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
                "senderEmail": str(value.get("senderEmail", "") or "")}

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

    def send_quote_sms(self, draft, text):
        validate_draft(draft)
        require(isinstance(text, str) and 1 <= len(text.strip()) <= 1000, "문자 내용을 1~1,000자로 입력해주세요.")
        recipient = draft.get("customerPhone", "").strip()
        require(recipient, "고객 연락처가 입력되지 않았습니다.")
        recipient_digits = "".join(ch for ch in recipient if ch.isdigit())
        source = draft.get("sourceRequestId", "")
        if source:
            request = next((r for r in self.vault.rows("requests") if r["requestId"] == source), None)
            require(request is not None, "견적 요청을 다시 동기화해주세요.")
            require(request.get("status", "신규") in ("견적확정", "견적발송완료"),
                    "견적확정 이후에 문자를 전송해주세요.")
        result = self.api.call("send_quote_sms", requestId=source, recipientPhone=recipient, text=text.strip())
        require(result.get("sent") is True and result.get("recipientPhone") == recipient_digits,
                "문자 전송 접수를 확인하지 못했습니다.")
        state = self.quote_delivery_state(draft["id"])
        state.update({"smsSentAt": 0, "smsTo": recipient_digits,
                      "senderPhone": result.get("senderPhone", ""),
                      "smsMessageId": result.get("messageId", ""),
                      "smsState": "pending",
                      "smsStatusCode": str(result.get("statusCode", "") or "2000"),
                      "smsStatusMessage": str(result.get("statusMessage", "") or "문자 발송을 접수했습니다."),
                      "smsUpdatedAt": now_ms()})
        delivery = self._save_quote_delivery_state(draft["id"], state)
        return {"delivery": delivery, "senderPhone": result.get("senderPhone", ""),
                "messageId": result.get("messageId", ""), "statusCode": result.get("statusCode", ""),
                "statusMessage": result.get("statusMessage", ""), "sentAt": result.get("sentAt", "")}

    def refresh_quote_sms_status(self, identifier):
        state = self.quote_delivery_state(identifier)
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
        return self._save_quote_delivery_state(identifier, state)

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
        if source:
            request = next((r for r in self.vault.rows("requests") if r["requestId"] == source), None)
            require(request is not None, "견적 요청을 다시 동기화해주세요.")
            require(request.get("status", "신규") in ("견적확정", "견적발송완료"),
                    "견적확정 이후에 메일을 전송해주세요.")
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
                                                 senderEmail=result.get("senderEmail", ""))
        return {"delivery": delivery, "senderEmail": result.get("senderEmail", ""),
                "attachmentName": result.get("attachmentName", ""),
                "sentAt": result.get("sentAt", ""), "remainingDailyQuota": result.get("remainingDailyQuota", 0)}

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
        self.vault.save_preference("drafts", [d for d in self.view()["drafts"] if d["id"] != identifier])
        return self.view()

    def confirm_draft(self, draft):
        validate_draft(draft)
        # Read cloud state before purchase confirmation; never replay a new order under a new ID.
        snapshot = self.sync()
        number = "PC_" + draft["id"]
        require(not any(r["metadata"]["orderNumber"] == number for r in snapshot["sales"]),
                "이미 판매 · 고객 관리로 이동한 견적입니다.")
        source = draft["sourceRequestId"]
        if source:
            request = next((r for r in snapshot["requests"] if r["requestId"] == source), None)
            require(request is not None, "접수된 문의가 삭제·만료되었습니다.")
            require(request.get("status", "신규") == "견적발송완료",
                    "먼저 고객에게 견적을 발송하고 견적발송완료로 처리해주세요.")
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

    def _pages(self, action, field):
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
                    if row.get("expired") or row["expiresAt"] <= now_ms():
                        deleted.add(reference)
                        continue
                    self.decode_request(row)
                else:
                    if row.get("expired"):
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
        sales, sale_cursor, _ = self._pages("list_sales", "sales")
        old = self.vault.snapshot_marker()
        if self.request_snapshot:
            require(not (set(old["deletedRequests"]) & {r["requestId"] for r in requests}), "삭제된 요청이 다시 나타났습니다.")
            require(request_cursor >= old["requests"], "서버 순번이 이전보다 작아 기존 기록을 유지합니다.")
            deleted |= set(old["deletedRequests"])
        require(request_cursor >= old["requests"] and sale_cursor >= old["sales"], "서버 순번이 이전보다 작아 기존 기록을 유지합니다.")
        require(set(old["deletedRequests"]) <= deleted, "삭제된 문의가 서버에 다시 나타나 기존 기록을 유지합니다.")
        request_ids = {r["requestId"] for r in requests} | deleted
        require(self.request_snapshot or all(r["requestId"] in request_ids for r in self.vault.rows("requests")), "서버 문의 목록 일부가 누락되어 기존 기록을 유지합니다.")
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

    def sync_requests(self):
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        self.verify_server()
        requests, cursor, deleted = self._pages("list_requests", "requests")
        ids = {r["requestId"] for r in requests} | deleted
        require(self.request_snapshot or all(r["requestId"] in ids for r in self.vault.rows("requests")),
                "서버 문의 목록 일부가 누락되어 기존 기록을 유지합니다.")
        if self.request_snapshot:
            old = self.vault.snapshot_marker()
            require(not (set(old["deletedRequests"]) & {r["requestId"] for r in requests}), "삭제된 요청이 다시 나타났습니다.")
            require(cursor >= old["requests"], "서버 순번이 이전보다 작아 기존 기록을 유지합니다.")
            deleted |= set(old["deletedRequests"])
        self.vault.replace_requests(requests, cursor, deleted)
        return self.local_copy_view()

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
        require(any(r["requestId"] == request_id for r in self.vault.rows("requests")), "먼저 문의를 다시 조회해주세요.")
        require(self.credentials.get("paired") is True, "PC를 먼저 연결해주세요.")
        self.vault.queue({"id": str(uuid.uuid4()), "action": "close_request", "payload": {"requestId": request_id}})
        self.verify_server()
        self.flush()
        # A verified deletion acknowledgement is sufficient. A later list pull
        # must not turn a completed deletion into an apparent save failure.
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


