"""Versioned, resumable inbox reads. Temporary payloads use the existing vault cipher."""
import hashlib
import sqlite3
import shutil
from contextlib import closing
from time import monotonic

from .crypto import canonical
from .domain import integer, now_ms, require, valid_id
from .errors import UserError, NetworkError


class ReceivePending(UserError):
    pass


class ReceiveStage:
    def __init__(self, vault, scope):
        self.vault = vault
        self.closed = False
        self.path = vault.path.with_name(vault.path.name + ".quote-receive-v2.tmp")
        self.db = sqlite3.connect(self.path)
        self.db.execute("PRAGMA secure_delete=ON")
        self.db.executescript("CREATE TABLE IF NOT EXISTS rows(id TEXT PRIMARY KEY, seq INTEGER UNIQUE, body BLOB NOT NULL);"
                              "CREATE TABLE IF NOT EXISTS meta(body BLOB NOT NULL);")
        saved = self.db.execute("SELECT body FROM meta").fetchone()
        try:
            self.meta = vault._open(saved[0], "quote-receive-v2:meta") if saved else {}
        except UserError:
            # A restored vault may have a different key. This file is disposable, never business data.
            self.meta = {}
        if self.meta.get("scope") != scope or not 0 <= now_ms() - self.meta.get("startedAt", 0) < 3600000:
            self.reset(scope)

    def reset(self, scope):
        with self.db:
            self.db.execute("DELETE FROM rows")
            self.meta = {"scope": scope, "startedAt": now_ms(), "cursor": 0, "received": 0}
            self._save()

    def _save(self):
        self.db.execute("DELETE FROM meta")
        self.db.execute("INSERT INTO meta VALUES (?)", (self.vault._seal(self.meta, "quote-receive-v2:meta"),))

    def save(self):
        with self.db:
            self._save()

    def append(self, page, cursor):
        require(shutil.disk_usage(self.path.parent).free > 32 * 1024 * 1024,
                "견적 임시 수신 공간이 부족합니다. 저장 공간 확보 후 다시 가져와주세요. 기존 기록을 유지합니다.")
        with self.db:
            for row in page:
                key = self.vault._index("quote-receive-v2", row["requestId"])
                self.db.execute("INSERT INTO rows VALUES (?,?,?)",
                                (key, row["sequence"], self.vault._seal(row, "quote-receive-v2:" + key)))
            self.meta.update(cursor=cursor, received=self.db.execute("SELECT COUNT(*) FROM rows").fetchone()[0])
            self._save()

    def rows(self):
        for key, body in self.db.execute("SELECT id,body FROM rows ORDER BY seq"):
            yield self.vault._open(body, "quote-receive-v2:" + key)

    def close(self):
        if not self.closed:
            self.db.close()
            self.closed = True


class ReceivedRequests:
    """Reiterable disk stream; never a customer-sized Python list."""
    def __init__(self, vault, path, excluded=()):
        self.vault, self.path, self.excluded = vault, path, frozenset(excluded)

    def __iter__(self):
        with closing(sqlite3.connect(self.path.resolve().as_uri() + "?mode=ro", uri=True)) as db:
            for key, body in db.execute("SELECT id,body FROM rows ORDER BY seq"):
                row = self.vault._open(body, "quote-receive-v2:" + key)
                if row["requestId"] not in self.excluded and not row.get("expired") and row["expiresAt"] > now_ms():
                    yield row

    def discard(self):
        self.path.unlink(missing_ok=True)


class QuoteReceive:
    def _expire_quote_receive_v2(self):
        path = self.vault.path.with_name(self.vault.path.name + ".quote-receive-v2.tmp")
        if path.exists() and not 0 <= now_ms() - path.stat().st_mtime * 1000 < 3600000:
            path.unlink()
            path.with_name(path.name + "-journal").unlink(missing_ok=True)

    def _quote_pages_v2(self, *, start=0, snapshot=True):
        if not snapshot and self.vault.preference("quote_receive_rebase_v2", False):
            start, snapshot = 0, True
            self._quote_rebased_v2 = True
        # A restored/different vault cache cannot resume a session made for a newer cache.
        marker = self.vault.snapshot_marker()
        scope = canonical([self.api.url, self.vault.request_stamp(),
                           [marker.get(k) for k in ("requests", "quoteReadMode", "quoteSyncVersion")], snapshot, start])
        stage = ReceiveStage(self.vault, scope)
        base = start
        clock, count = monotonic(), 0
        try:
            while not stage.meta.get("complete"):
                after = max(base, stage.meta["cursor"])
                payload = dict(quoteSyncVersion=2, afterSequence=after, fromSequence=base, snapshot=snapshot)
                if stage.meta.get("snapshotId"):
                    payload["snapshotId"] = stage.meta["snapshotId"]
                response = self.api.call("snapshot_requests" if snapshot else "list_requests", **payload)
                require(response.get("quoteSyncVersion") == 2 and response.get("schemaEpoch") == 1 and
                        response.get("scope") == ("active" if snapshot else "changes"),
                        "견적 수신 v2 응답을 확인하지 못했습니다. 기존 자료를 유지합니다.")
                if response.get("requiresSnapshot") is True:
                    require(not snapshot)
                    stage.reset(scope)
                    # Return a complete snapshot; the caller must replace rather than merge.
                    self._quote_rebased_v2 = True
                    self.vault.save_preference("quote_receive_rebase_v2", True)
                    stage.close()
                    return self._quote_pages_v2(start=0, snapshot=True)
                require(response.get("requiresSnapshot") is False)
                token = response.get("snapshotId")
                require(isinstance(token, str) and len(token) == 64 and all(c in "0123456789abcdef" for c in token))
                total, watermark = integer(response.get("total"), 0), integer(response.get("watermark"), base)
                require(watermark <= 9007199254740991)
                if "snapshotId" not in stage.meta:
                    stage.meta.update(snapshotId=token, total=total, watermark=watermark)
                require((token, total, watermark) == (stage.meta["snapshotId"], stage.meta["total"], stage.meta["watermark"]),
                        "견적 목록이 변경되었습니다. 다시 가져와주세요.")
                page = response.get("requests")
                require(isinstance(page, list) and len(page) <= 100 and type(response.get("hasMore")) is bool and
                        (page or not response["hasMore"]))
                cursor = after
                for row in page:
                    require(isinstance(row, dict) and valid_id(row.get("requestId")))
                    sequence = integer(row.get("sequence"), cursor + 1)
                    require(sequence <= watermark)
                    cursor = sequence
                    require(type(row.get("expired", False)) is bool and type(row.get("deleted", False)) is bool)
                    require(not row.get("deleted") or row.get("expired") is True)
                    self._quote_received_date(row)
                    if snapshot:
                        require(not row.get("expired"))
                    if not row.get("expired"):
                        self.decode_request(row)
                stage.append(page, cursor)
                require(stage.meta["received"] <= total)
                self.report_progress(f"견적 {stage.meta['received']:,}/{total:,}건 수신 중…")
                if not response["hasMore"]:
                    require(stage.meta["received"] == total)
                    stage.meta["complete"] = True
                    stage.save()
                count += 1
                if not stage.meta.get("complete") and (count >= 20 or monotonic() - clock >= 60):
                    raise ReceivePending(f"견적 {stage.meta['received']:,}/{total:,}건 수신 · 가져오기를 다시 누르면 이어받습니다. 기존 기록을 유지합니다.")
            deleted, removed = self._quote_states_v2(stage)
            self._quote_deleted_v2 = deleted
            return ReceivedRequests(self.vault, stage.path, removed), stage.meta["watermark"], removed
        except (ReceivePending, NetworkError):
            raise
        except Exception:
            if not stage.closed:
                stage.reset(scope)
            raise
        finally:
            stage.close()

    @staticmethod
    def _quote_received_date(row):
        from datetime import datetime
        try:
            created = datetime.fromisoformat(row["createdAt"].replace("Z", "+00:00"))
            require(created.tzinfo is not None and created.utcoffset().total_seconds() == 0)
            require(0 < created.timestamp() * 1000 <= now_ms() + 300000)
            integer(row.get("expiresAt"), 1)
            require(abs(row["expiresAt"] - (int(created.timestamp() * 1000) + 30 * 86400000)) <= 1)
        except (ValueError, KeyError, AttributeError, TypeError):
            raise UserError("접수 날짜를 확인하지 못했습니다.") from None

    def _quote_states_v2(self, stage):
        quotes, drafts = self._local_quotes(), self.vault.preference("drafts", [])
        ids = {d.get("sourceRequestId") for d in [*quotes, *drafts] if valid_id(d.get("sourceRequestId"))}
        ids.update(self.vault.request_ids())
        pending = self.vault.preference("quote_write_pending") or {}
        source = (pending.get("draft") or {}).get("sourceRequestId")
        if valid_id(source):
            ids.add(source)
        # Outbox source identities are checked too; their operations are never discarded here.
        for op in self.vault.pending():
            payload = op.get("payload") or {}
            for ref in (payload.get("requestId"), payload.get("sourceRequestId")):
                if valid_id(ref):
                    ids.add(ref)
        ids = sorted(ids)
        key = hashlib.sha256(canonical(ids).encode()).hexdigest()
        if stage.meta.get("statesKey") != key:
            stage.meta.update(statesKey=key, statesAfter="")
            # Preserve already verified closure evidence if local candidates change after a crash.
            stage.meta.setdefault("deleted", [])
            stage.meta.setdefault("closed", [])
        deleted = set(stage.meta["deleted"])
        closed = set(stage.meta["closed"])
        remaining = [ref for ref in ids if ref > stage.meta["statesAfter"]]
        clock = monotonic()
        for index in range(0, len(remaining), 50):
            batch = remaining[index:index + 50]
            response = self.api.call("quote_request_states", quoteSyncVersion=2, requestIds=batch)
            require(response.get("quoteSyncVersion") == 2 and response.get("schemaEpoch") == 1)
            states = response.get("states")
            require(isinstance(states, list) and len(states) == len(batch))
            seen = set()
            for row in states:
                require(isinstance(row, dict) and row.get("requestId") in batch and row["requestId"] not in seen)
                seen.add(row["requestId"])
                state = row.get("state")
                require(state in ("active", "expired", "admin_deleted", "unknown"))
                seq = integer(row.get("sequence"), 0)
                require(seq <= 9007199254740991 and ((state == "unknown" and seq == 0) or (state != "unknown" and seq > 0)))
                if state == "admin_deleted":
                    deleted.add(row["requestId"])
                if state in ("expired", "admin_deleted"):
                    closed.add(row["requestId"])
            stage.meta.update(statesAfter=batch[-1], deleted=sorted(deleted), closed=sorted(closed))
            stage.save()
            self.report_progress(f"견적 종료 상태 확인 중… {len(ids) - len(remaining) + index + len(batch):,}/{len(ids):,}")
            if index + 50 < len(remaining) and (index >= 950 or monotonic() - clock >= 60):
                raise ReceivePending("견적 종료 상태 확인 중 · 가져오기를 다시 누르면 이어받습니다. 기존 기록을 유지합니다.")
        removed = set(deleted) | closed
        for row in stage.rows():
            if row.get("expired") or row["expiresAt"] <= now_ms():
                removed.add(row["requestId"])
            if row.get("deleted") is True:
                deleted.add(row["requestId"])
        return deleted, removed

    def _apply_quote_deleted_v2(self, deleted=()):
        applied = set(self.vault.preference("quote_delete_applied_v2", []) or [])
        journal = (set(self.vault.preference("quote_delete_journal_v2", []) or []) | set(deleted)) - applied
        if not journal:
            return
        self.vault.save_preference("quote_delete_journal_v2", sorted(journal))
        quotes = self._local_quotes()
        original_drafts = self.vault.preference("drafts")
        drafts = original_drafts or []
        affected = [d for d in [*quotes, *drafts] if d.get("sourceRequestId") in journal]
        pending = self.vault.preference("quote_write_pending") or {}
        busy = (pending.get("draft") or {}).get("sourceRequestId") in journal
        for draft in affected:
            for scope in ("quote_delivery", "payment_delivery"):
                state = (self.vault.preference(scope, {}) or {}).get(draft["id"], {})
                busy |= bool(state.get("pendingDelivery")) or state.get("smsState") in ("pending", "unknown", "sending")
        for op in self.vault.pending():
            payload = op.get("payload") or {}
            busy |= payload.get("requestId") in journal or payload.get("sourceRequestId") in journal
        if busy:
            # Journal itself is the durable deletion evidence; leave originals and operations visible.
            raise UserError("서버에서 삭제한 견적에 미완료 작업이 있습니다. 삭제 근거와 기기 원본을 보존했습니다. 전송 대기를 확인해주세요.")
        kept_quotes = [d for d in quotes if d.get("sourceRequestId") not in journal]
        kept_drafts = [d for d in drafts if d.get("sourceRequestId") not in journal]
        dismissed = sorted(set(self.vault.preference("dismissed_server_quotes", []) or []) | journal)
        self.vault.archive_and_update("서버 명시 삭제 · 견적 원본과 발송 근거 보관",
            {"deletedRequests": sorted(journal), "quotes": affected,
             "quoteDelivery": self.vault.preference("quote_delivery", {}),
             "paymentDelivery": self.vault.preference("payment_delivery", {})},
            preferences={"confirmed_quotes": kept_quotes, "drafts": kept_drafts,
                         "dismissed_server_quotes": dismissed, "quote_delete_journal_v2": [],
                         "quote_delete_applied_v2": sorted(applied | journal)},
            expected={"confirmed_quotes": quotes, "drafts": original_drafts})
