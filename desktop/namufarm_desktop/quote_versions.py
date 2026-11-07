"""Shared confirmed web quotes (Apps Script confirmedQuoteVersion:1).

The server keeps one current version per web request UUID. This PC keeps its own
confirmed-quote IDs, costs, delivery history and Excel copy, and links a local
quote to the server copy only through ``sourceRequestId``. Divergent copies are
never chosen by timestamp; the seller decides and the other copy is archived.
"""
import copy
import hashlib
import re
import uuid

from .commerce import draft_amounts, new_draft, validate_draft
from .crypto import canonical
from .domain import integer, now_ms, require
from .errors import ConflictError, UserError

SERVER_ISSUER_FIELDS = ("businessName", "representative", "businessNumber", "address", "phone",
                        "email", "businessType", "category", "fax")
SERVER_QUOTE_STATES = ("견적확정", "견적발송완료", "입금확인")
BUSY_DELIVERY = "다른 기기에서 이 견적을 발송 처리 중입니다. 그 기기에서 결과를 확인한 뒤 다시 보내주세요."


def _trimmed(value):
    """The server stores detail strings trimmed; compare the same way."""
    if isinstance(value, str):
        return value.strip()
    if isinstance(value, list):
        return [_trimmed(v) for v in value]
    if isinstance(value, dict):
        return {k: _trimmed(v) for k, v in value.items()}
    return value


def server_pricing(draft):
    return {"items": [{"species": i["species"], "specification": i.get("specification", ""),
                       "quantity": i["quantity"], "unitPrice": i["unitPrice"]} for i in draft["items"]],
            "isPrepaid": draft["isPrepaid"], "shippingFee": draft["shippingFee"], "taxAmount": draft["taxAmount"]}


def _put(target, key, value, default):
    # Keep fields another device stored; add a new field only when it carries a value.
    if key in target or value != default:
        target[key] = value


def server_details(draft, base=None):
    """Seller-edited quote fields. Customer originals stay in the request row."""
    details = copy.deepcopy(base) if isinstance(base, dict) else {}
    _put(details, "customerName", draft.get("customerName", ""), "")
    _put(details, "customerPhone", draft.get("customerPhone", ""), "")
    _put(details, "customerEmail", draft.get("customerEmail", ""), "")
    _put(details, "recipientName", draft.get("quoteRecipientName", ""), "")
    _put(details, "region", draft.get("requestRegion", ""), "")
    _put(details, "notes", draft.get("note", ""), "")
    notes = [item.get("remarks", "") or "" for item in draft["items"]]
    if "itemNotes" in details or any(note.strip() for note in notes):
        details["itemNotes"] = notes
    issuer = copy.deepcopy(details.get("issuer")) if isinstance(details.get("issuer"), dict) else {}
    for key in SERVER_ISSUER_FIELDS:
        _put(issuer, key, str((draft.get("issuer") or {}).get(key, "") or ""), "")
    if issuer or "issuer" in details:
        details["issuer"] = issuer
    _put(details, "validUntil", draft.get("validUntil", ""), "")
    _put(details, "paymentAccount", draft.get("paymentAccount", ""), "")
    _put(details, "replyChannel", draft.get("quoteReplyChannel", "sms"), "sms")
    _put(details, "formalQuoteRequested", bool(draft.get("formalQuoteRequested", False)), False)
    if "issuedAt" not in details:
        details["issuedAt"] = int(draft["createdAt"])
    return details


def content_key(pricing, details):
    return hashlib.sha256(canonical({"quotePricing": pricing, "quoteDetails": _trimmed(details or {})})
                          .encode("utf-8")).hexdigest()


def local_key(draft):
    """This PC's own fields only, independent of what other devices added."""
    return content_key(server_pricing(draft), server_details(draft))


def quote_version(quote):
    version = quote.get("quoteVersion") if isinstance(quote, dict) else None
    if not isinstance(version, dict):
        return None
    integer(version.get("revision"), 0)
    require(isinstance(version.get("fingerprint"), str), "서버 확정견적 버전을 확인하지 못했습니다.")
    return version


def delivered_current(version):
    delivered = (version or {}).get("lastDelivered") or {}
    return (delivered.get("revision") == version.get("revision") and
            delivered.get("fingerprint") == version.get("fingerprint"))


class QuoteVersions:
    """Mixed into Manager. Every write keeps an operation/delivery ID before the network call."""

    quote_versions = False
    sync_head_supported = False

    def _versioned(self, draft=None):
        return bool(self.quote_versions) and (draft is None or bool(draft.get("sourceRequestId")))

    def server_quote(self, request_id):
        """Fresh server copy for a write decision; never a cached list row."""
        result = self.api.call("get_confirmed_quote", requestId=request_id)
        quote = result.get("quote")
        require(isinstance(quote, dict) and quote.get("requestId") == request_id and not quote.get("expired"),
                "서버 확정견적을 확인하지 못했습니다. 가져오기 후 다시 확인해주세요.")
        quote_version(quote)
        return quote

    def check_sync_head(self):
        result = self.api.call("sync_head")
        return {key: integer(result.get(key), 0) for key in ("requestSequence", "saleSequence", "inquirySequence")}

    # ---- server copy <-> local quote -------------------------------------------------
    def _server_link(self, quote, draft):
        version = quote_version(quote)
        payment = (version or {}).get("payment") or {}
        return {"revision": version["revision"] if version else (1 if quote.get("quotePricing") else 0),
                "fingerprint": version["fingerprint"] if version else "",
                "localKey": local_key(draft), "status": quote.get("status", ""),
                "delivered": bool(version) and delivered_current(version),
                "paymentOrder": payment.get("orderNumber", ""), "linkedAt": now_ms()}

    def _linked(self, draft, quote):
        linked = copy.deepcopy(draft)
        linked["serverQuote"] = self._server_link(quote, draft)
        linked.pop("serverConflict", None)
        return linked

    def _draft_from_server(self, quote, existing=None):
        """Use the existing request-to-draft conversion, then the seller's shared details."""
        personal = self.decode_request(quote)
        require(isinstance(quote.get("quotePricing"), dict), "서버 확정견적 가격이 없습니다.")
        draft = new_draft({**quote, "personal": personal})
        details = (quote_version(quote) or {}).get("quoteDetails") or {}
        if existing:
            for key in ("id", "createdAt", "confirmedAt", "validUntil", "paymentAccount", "receiverName",
                        "zipNo", "roadAddress", "detailAddress"):
                if key in existing:
                    draft[key] = copy.deepcopy(existing[key])
            draft["issuer"] = copy.deepcopy(existing.get("issuer") or draft["issuer"])
        costs = {(i.get("species", "").strip(), i.get("specification", "").strip()): i.get("unitCost", 0)
                 for i in (existing or {}).get("items", [])}
        known = bool(costs)
        for item in draft["items"]:
            key = (item["species"].strip(), item.get("specification", "").strip())
            if key in costs:
                item["unitCost"] = costs[key]
            else:
                known = False  # Another device's new row: '원가 미확인'.
        draft["costConfirmed"] = bool(existing and existing.get("costConfirmed") and known)
        for source, target in (("customerName", "customerName"), ("customerEmail", "customerEmail"),
                               ("recipientName", "quoteRecipientName"), ("region", "requestRegion"),
                               ("notes", "note"), ("validUntil", "validUntil"), ("paymentAccount", "paymentAccount"),
                               ("replyChannel", "quoteReplyChannel")):
            if isinstance(details.get(source), str):
                draft[target] = details[source]
        if isinstance(details.get("customerPhone"), str) and details["customerPhone"]:
            draft["customerPhone"] = re.sub(r"\D", "", details["customerPhone"])
        if type(details.get("formalQuoteRequested")) is bool:
            draft["formalQuoteRequested"] = details["formalQuoteRequested"]
        notes = details.get("itemNotes")
        if isinstance(notes, list) and len(notes) == len(draft["items"]):
            for item, note in zip(draft["items"], notes):
                item["remarks"] = note if isinstance(note, str) else ""
        if isinstance(details.get("issuer"), dict):
            draft["issuer"] = {**draft["issuer"], **{k: v for k, v in details["issuer"].items() if isinstance(v, str)}}
        if not existing and isinstance(details.get("issuedAt"), int) and details["issuedAt"] > 0:
            draft["createdAt"] = details["issuedAt"]
        draft["requestReceiptNumber"] = quote.get("receiptNumber") or quote["requestId"]
        draft["requestNote"] = personal.get("message", "")
        draft.pop("workingCopy", None)
        draft.pop("manualStatus", None)
        return validate_draft(draft)

    def _merge_server_quotes(self):
        """Hand over web quotes confirmed on another device. Never overwrite an unresolved local edit."""
        rows = [r for r in self.vault.rows("requests")
                if r.get("status") in SERVER_QUOTE_STATES and isinstance(r.get("quotePricing"), dict)
                and not r.get("expired")]
        if not rows:
            return ""
        quotes = self._local_quotes()
        by_source = {q.get("sourceRequestId"): q for q in quotes if q.get("sourceRequestId")}
        pending = self.vault.preference("quote_write_pending") or {}
        busy = (pending.get("draft") or {}).get("sourceRequestId")
        dismissed = set(self.vault.preference("dismissed_server_quotes", []) or [])
        changed, added, skipped = False, [], 0
        for row in rows:
            source = row["requestId"]
            if source == busy or (source in dismissed and source not in by_source):
                continue  # Removed from this PC's list by the seller: do not hand it over again.
            try:
                version = quote_version(row)
                local = by_source.get(source)
                if local is None:
                    if row["status"] == "입금확인":
                        continue  # Paid on another device; its order is managed there.
                    draft = self._draft_from_server(row)
                    draft.update(quoteStatus=row["status"], confirmedAt=now_ms())
                    added.append(self._linked(draft, row))
                    changed = True
                    continue
                if version is None:
                    continue  # Legacy server copy: keep the existing local contract.
                previous = local.get("serverQuote") or {}
                server_key = content_key(row["quotePricing"], version.get("quoteDetails"))
                same = content_key(server_pricing(local), server_details(local, version.get("quoteDetails"))) == server_key
                if same:
                    link = self._server_link(row, local)
                    status = row["status"] if row["status"] in ("견적확정", "견적발송완료") else local["quoteStatus"]
                    if local.get("serverQuote") != link or local.get("serverConflict") or local["quoteStatus"] != status:
                        local.update(serverQuote=link, quoteStatus=status)
                        local.pop("serverConflict", None)
                        changed = True
                elif previous.get("localKey") == local_key(local) and version["revision"] > integer(previous.get("revision", 0), 0):
                    # Unchanged here since the last link; the other device's newer version wins.
                    updated = self._linked(self._draft_from_server(row, local), row)
                    updated["quoteStatus"] = row["status"] if row["status"] in ("견적확정", "견적발송완료") else local["quoteStatus"]
                    local.clear()
                    local.update(updated)
                    changed = True
                elif (local.get("serverConflict") or {}).get("revision") != version["revision"]:
                    local["serverConflict"] = {"revision": version["revision"], "fingerprint": version["fingerprint"]}
                    changed = True
            except UserError:
                skipped += 1
        if changed:
            self.vault.save_preference("confirmed_quotes", [*added, *quotes])
        return f"서버 확정견적 {skipped:,}건은 형식이 맞지 않아 PC 목록에 넣지 못했습니다." if skipped else ""

    def resolve_quote_conflict(self, identifier, use_server):
        """'서버 내용 사용' or '이 PC 수정본 저장'. The unchosen copy is archived first."""
        quote = next((q for q in self._local_quotes() if q["id"] == identifier), None)
        require(quote is not None and quote.get("sourceRequestId"), "확정견적을 다시 선택해주세요.")
        require(self._versioned(quote), "서버의 확정견적 공유 기능을 먼저 배포해주세요.")
        self.verify_server()
        server = self.server_quote(quote["sourceRequestId"])
        require(quote_version(server) is not None, "서버 확정견적 버전을 확인하지 못했습니다.")
        self.vault.backup("quote_conflict")
        archive = list(self.vault.preference("quote_conflict_archive", []) or [])
        archive.append({"at": now_ms(), "kept": "server" if use_server else "local",
                        "local": copy.deepcopy(quote), "server": copy.deepcopy(server)})
        self.vault.save_preference("quote_conflict_archive", archive[-200:])
        if use_server:
            updated = self._linked(self._draft_from_server(server, quote), server)
            status = server["status"] if server["status"] in ("견적확정", "견적발송완료") else quote["quoteStatus"]
            self._store_local_quote(updated, status)
            return self.local_copy_view()
        local = copy.deepcopy(quote)
        local.pop("serverConflict", None)
        return self._revise_quote(local, quote["quoteStatus"], server=server)

    # ---- writes ----------------------------------------------------------------------
    def _revise_quote(self, draft, status="견적확정", *, server=None):
        """Create or update the shared version. Same content only links locally."""
        source = draft["sourceRequestId"]
        require(len(draft["items"]) <= 50, "견적은 최대 50품목까지 저장할 수 있습니다.")
        self._resume_quote_write()
        quote = server or self.server_quote(source)
        version = quote_version(quote)
        if version:
            require(not version.get("payment"), "입금확인된 견적은 수정할 수 없습니다.")
            if content_key(server_pricing(draft), server_details(draft, version.get("quoteDetails"))) == \
                    content_key(quote["quotePricing"], version.get("quoteDetails")):
                current = quote["status"] if quote["status"] in ("견적확정", "견적발송완료") else status
                self._store_local_quote(self._linked(draft, quote), current)
                return self.local_copy_view()
        expected = version["revision"] if version else (1 if quote.get("quotePricing") else 0)
        if expected > 0 and server is None:
            # Only this PC's own copy of the version it last saw may replace the shared quote.
            local = next((q for q in self._local_quotes() if q.get("sourceRequestId") == source), None)
            known = local is not None and local["id"] == draft["id"] and (
                version is None or (local.get("serverQuote") or {}).get("revision") == version["revision"])
            require(known, "다른 기기에서 먼저 확정·수정한 견적입니다. 가져오기 후 확정견적 목록에서 확인해주세요.")
        base = (version or {}).get("quoteDetails") or {}
        payload = {"requestId": source, "operationId": str(uuid.uuid4()), "expectedRevision": expected,
                   "quotePricing": server_pricing(draft), "quoteDetails": server_details(draft, base)}
        require(len(canonical(payload).encode("utf-8")) <= 60000, "견적 품목이나 메모를 줄여주세요.")
        return self._write_quote_state(draft, status, "revise_confirmed_quote", payload)

    def _accept_revision(self, pending, response):
        """Validate a revise response and keep the server's revision beside the local quote."""
        source = pending["draft"]["sourceRequestId"]
        quote = response.get("quote")
        version = quote_version(quote)
        applied = response.get("appliedRevision")
        require(isinstance(quote, dict) and quote.get("requestId") == source and version is not None and
                type(applied) is int and 0 < applied <= version["revision"],
                "확정견적 저장을 확인하지 못했습니다. 가져오기로 같은 견적을 다시 확인해주세요.")
        draft = copy.deepcopy(pending["draft"])
        if applied == version["revision"]:
            draft = self._linked(draft, quote)
        status = quote.get("status") if quote.get("status") in ("견적확정", "견적발송완료") else pending["status"]
        return draft, status

    def _archive_conflict(self, pending):
        archive = list(self.vault.preference("quote_conflict_archive", []) or [])
        archive.append({"at": now_ms(), "kept": "server", "local": copy.deepcopy(pending.get("draft"))})
        self.vault.save_preference("quote_conflict_archive", archive[-200:])

    def _delivery_version(self, draft):
        """Make the server copy equal to what is about to be sent; return its revision."""
        self._revise_quote(draft, "견적확정")
        quote = next((q for q in self._local_quotes() if q.get("sourceRequestId") == draft["sourceRequestId"]), None)
        link = (quote or {}).get("serverQuote") or {}
        require(link.get("fingerprint") and link.get("localKey") == local_key(draft),
                "서버 확정견적과 보낼 내용이 다릅니다. 가져오기 후 다시 보내주세요.")
        return {"requestId": draft["sourceRequestId"], "expectedRevision": link["revision"],
                "quoteFingerprint": link["fingerprint"]}

    def _reserve_delivery(self, version, delivery_id, channel):
        try:
            self.api.call("reserve_quote_delivery", requestId=version["requestId"], deliveryId=delivery_id,
                          expectedRevision=version["expectedRevision"], quoteFingerprint=version["quoteFingerprint"],
                          channel=channel, mode="server")
        except ConflictError:
            raise UserError("서버 확정견적이 바뀌었거나 입금확인되었습니다. 가져오기 후 다시 확인해주세요.", code="CONFLICT") from None
        except UserError as error:
            if error.code == "BUSY":
                raise UserError(BUSY_DELIVERY, code="BUSY") from None
            raise

    def _cancel_unsent_reservation(self, pending):
        """Only for a server-mode reservation whose send journal does not exist."""
        reserved = pending.get("reservation")
        if not reserved:
            return
        try:
            self.api.call("cancel_quote_delivery", requestId=reserved["requestId"], deliveryId=pending["id"],
                          expectedRevision=reserved["expectedRevision"], quoteFingerprint=reserved["quoteFingerprint"],
                          notSent=True)
        except ConflictError:
            pass  # Already sending/sent/cancelled on the server: leave it as recorded there.

    # ---- payment ---------------------------------------------------------------------
    def _payment_ready(self, draft, number):
        """Online check before a payment notice or sale. Returns (quote, version) or (None, None)."""
        if not self._versioned(draft):
            return None, None
        self.verify_server()
        if not self.quote_versions:
            return None, None
        quote = self.server_quote(draft["sourceRequestId"])
        version = quote_version(quote)
        if version is None:
            return quote, None  # Legacy copy: existing save_sale contract.
        payment = version.get("payment") or {}
        claim = (self.vault.preference("payment_claims", {}) or {}).get(draft["id"]) or {}
        if payment:
            require(payment.get("orderNumber") and payment.get("orderNumber") in (claim.get("orderNumber"), number),
                    f"다른 기기에서 이미 입금확인한 견적입니다 · 주문번호 {payment.get('orderNumber', '')}")
            return quote, version
        require(content_key(server_pricing(draft), server_details(draft, version.get("quoteDetails"))) ==
                content_key(quote["quotePricing"], version.get("quoteDetails")),
                "서버의 최신 확정견적과 내용이 다릅니다. 가져오기 후 다시 확인해주세요.")
        require(delivered_current(version),
                "수정한 견적을 아직 고객에게 보내지 않았습니다. 현재 견적을 먼저 보내주세요.")
        require(draft_amounts(draft)["total"] == sum(i["quantity"] * i["unitPrice"] for i in quote["quotePricing"]["items"]) +
                quote["quotePricing"]["taxAmount"] + (quote["quotePricing"]["shippingFee"] if quote["quotePricing"]["isPrepaid"] else 0),
                "서버 확정견적 금액과 다릅니다. 가져오기 후 다시 확인해주세요.")
        return quote, version

    def _payment_number(self, draft):
        claim = (self.vault.preference("payment_claims", {}) or {}).get(draft["id"])
        return claim["orderNumber"] if claim else self._sold_number_for_draft(draft)

    def _claim_quote_payment(self, draft, number):
        """Pre-claim the web quote under the server lock, so no other device can register it."""
        quote, version = self._payment_ready(draft, number)
        if version is None:
            return {}
        claims = dict(self.vault.preference("payment_claims", {}) or {})
        payment = version.get("payment")
        if payment:
            return {"expectedRevision": payment["revision"], "quoteFingerprint": payment["fingerprint"]}
        claim = claims.get(draft["id"])
        if not claim or claim.get("revision") != version["revision"] or claim.get("orderNumber") != number:
            claim = {"operationId": str(uuid.uuid4()), "orderNumber": number, "revision": version["revision"],
                     "fingerprint": version["fingerprint"], "amount": draft_amounts(draft)["total"],
                     "requestId": draft["sourceRequestId"], "createdAt": now_ms()}
            claims[draft["id"]] = claim
            self.vault.save_preference("payment_claims", claims)  # Commit BEFORE network.
        try:
            result = self.api.call("confirm_quote_payment", requestId=claim["requestId"], operationId=claim["operationId"],
                                   orderNumber=claim["orderNumber"], expectedRevision=claim["revision"],
                                   quoteFingerprint=claim["fingerprint"], amount=claim["amount"])
        except ConflictError:
            raise UserError("다른 기기에서 먼저 입금확인했거나 견적이 바뀌었습니다. 가져오기 후 확인해주세요. 판매 등록은 하지 않았습니다.",
                            code="CONFLICT") from None
        confirmed = result.get("payment") or {}
        require(confirmed.get("orderNumber") == claim["orderNumber"] and confirmed.get("revision") == claim["revision"],
                "서버 입금확인 응답을 확인하지 못했습니다. 같은 견적으로 다시 확인해주세요.")
        return {"expectedRevision": claim["revision"], "quoteFingerprint": claim["fingerprint"]}
