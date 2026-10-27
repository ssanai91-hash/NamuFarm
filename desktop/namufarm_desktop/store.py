import hashlib
import hmac
import json
import os
import re
import time
import sqlite3
import threading
from datetime import datetime
from contextlib import contextmanager, closing
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .crypto import canonical, password_key, SellerKey
from .domain import now_ms, require
from .errors import UserError


class Vault:
    """Encrypted SQLite cache/outbox. No plaintext customer, device token, or private key on disk."""
    def __init__(self, path, secret):
        self.path, self.secret = Path(path), secret
        self.mutex = threading.RLock()

    @contextmanager
    def _db(self):
        db = sqlite3.connect(self.path, timeout=10)
        try:
            db.execute("PRAGMA secure_delete=ON")
            db.execute("PRAGMA journal_mode=DELETE")
            with db:
                yield db
        finally:
            db.close()

    def _seal(self, value, scope):
        nonce = os.urandom(12)
        return nonce + AESGCM(self.secret).encrypt(nonce, canonical(value).encode("utf-8"),
                                                  ("namufarm-desktop:v1:" + scope).encode())

    def _open(self, blob, scope):
        try:
            return json.loads(AESGCM(self.secret).decrypt(blob[:12], blob[12:],
                               ("namufarm-desktop:v1:" + scope).encode()))
        except Exception:
            raise UserError("PC 보관함 비밀번호 또는 데이터 무결성을 확인할 수 없습니다.") from None

    def _index(self, kind, reference):
        return hmac.new(self.secret, (kind + ":" + reference).encode(), hashlib.sha256).hexdigest()

    @classmethod
    def create(cls, path, password, seller):
        """Legacy vault import; existing files are never replaced."""
        salt = os.urandom(16)
        return cls._create(path, password_key(password, salt), {"version": b"1", "salt": salt}, seller.material())

    @classmethod
    def create_device(cls, path, protector=None):
        from . import device_protection
        protector = protector or device_protection
        secret = os.urandom(32)
        wrapped = protector.protect(secret)
        require(protector.unprotect(wrapped) == secret, "Windows 연결 정보를 확인하지 못했습니다.")
        return cls._create(path, secret, {"version": b"2", "device_key": wrapped}, {})

    @classmethod
    def _create(cls, path, secret, settings, material):
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        vault = cls(path, secret)
        try:
            descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(descriptor)
        except FileExistsError:
            raise UserError("기존 PC 보관함이 있습니다. 기존 파일을 열어주세요.") from None
        try:
            with vault._db() as db:
                db.executescript("CREATE TABLE config (name TEXT PRIMARY KEY, value BLOB NOT NULL);"
                                 "CREATE TABLE cache (kind TEXT, id TEXT, body BLOB NOT NULL, PRIMARY KEY(kind,id));"
                                 "CREATE TABLE outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE, body BLOB NOT NULL);")
                db.executemany("INSERT INTO config VALUES (?,?)", settings.items())
                db.execute("INSERT INTO config VALUES ('credentials',?)",
                           (vault._seal({**material, "deviceToken": os.urandom(32).hex(), "paired": False}, "credentials"),))
        except Exception:
            path.unlink(missing_ok=True)
            raise
        return vault

    @staticmethod
    def _settings(path):
        require(Path(path).is_file(), "기존 PC 보관함을 찾을 수 없습니다.")
        with closing(sqlite3.connect(Path(path).resolve().as_uri() + "?mode=ro", uri=True)) as db:
            return dict(db.execute("SELECT name,value FROM config"))

    @classmethod
    def device_managed(cls, path):
        return cls._settings(path).get("version") == b"2"

    @classmethod
    def open_device(cls, path, protector=None):
        from . import device_protection
        protector = protector or device_protection
        try:
            settings = cls._settings(path)
            require(settings.get("version") == b"2")
            secret = protector.unprotect(settings["device_key"])
            require(len(secret) == 32)
            vault = cls(path, secret)
            vault.credentials()  # Verify before exposing any cache or outbox.
            vault.purge()
            return vault
        except Exception:
            raise UserError("이 Windows 계정에서 PC 보관함을 열 수 없습니다. 기존 파일은 보존했습니다.") from None

    def use_device_protection(self, protector=None):
        from . import device_protection
        protector = protector or device_protection
        wrapped = protector.protect(self.secret)
        require(protector.unprotect(wrapped) == self.secret)
        # Only rewrap the cache key. All customer rows, outbox IDs and tokens stay intact.
        with self.mutex, self._db() as db:
            db.execute("INSERT OR REPLACE INTO config VALUES ('device_key',?)", (wrapped,))
            db.execute("UPDATE config SET value=? WHERE name='version'", (b"2",))
            db.execute("DELETE FROM config WHERE name='salt'")

    @classmethod
    def unlock(cls, path, password):
        try:
            if not Path(path).is_file():
                raise ValueError()
            with closing(sqlite3.connect(Path(path).resolve().as_uri() + "?mode=ro", uri=True)) as db:
                settings = dict(db.execute("SELECT name,value FROM config"))
            if settings.get("version") != b"1" or len(settings["salt"]) != 16:
                raise ValueError()
            vault = cls(path, password_key(password, settings["salt"]))
            credentials = vault._open(settings["credentials"], "credentials")
            SellerKey(credentials["privateKey"], credentials["publicKey"])
            vault.purge()
            return vault
        except Exception:
            raise UserError("PC 보관함을 열 수 없습니다. 비밀번호와 파일을 확인해주세요.") from None

    def credentials(self):
        with self.mutex, self._db() as db:
            blob = db.execute("SELECT value FROM config WHERE name='credentials'").fetchone()[0]
            return self._open(blob, "credentials")

    def save_credentials(self, value):
        with self.mutex, self._db() as db:
            db.execute("UPDATE config SET value=? WHERE name='credentials'", (self._seal(value, "credentials"),))

    def preference(self, name, default=None):
        """Encrypted PC-only workspace data; survives cloud snapshot replacement."""
        with self.mutex, self._db() as db:
            row = db.execute("SELECT value FROM config WHERE name=?", ("workspace:" + name,)).fetchone()
            return self._open(row[0], "workspace:" + name) if row else default

    def save_preference(self, name, value):
        with self.mutex, self._db() as db:
            scope = "workspace:" + name
            db.execute("INSERT OR REPLACE INTO config VALUES (?,?)", (scope, self._seal(value, scope)))

    def rows(self, kind):
        with self.mutex, self._db() as db:
            return [self._open(blob, kind + ":" + index) for index, blob in db.execute("SELECT id,body FROM cache WHERE kind=?", (kind,))]

    def pending(self):
        with self.mutex, self._db() as db:
            return [self._open(blob, "outbox:" + identifier) for identifier, blob in db.execute("SELECT id,body FROM outbox ORDER BY seq")]

    def snapshot_marker(self):
        with self.mutex, self._db() as db:
            return self._read_snapshot_marker(db)

    def _read_snapshot_marker(self, db):
        row = db.execute("SELECT value FROM config WHERE name='snapshot'").fetchone()
        return self._open(row[0], "snapshot") if row else {"requests": 0, "sales": 0, "deletedRequests": []}

    def replace_snapshot(self, requests, sales, marker):
        """Both complete validated pages become visible together. Never overwrite an unsent edit."""
        with self.mutex, self._db() as db:
            if db.execute("SELECT COUNT(*) FROM outbox").fetchone()[0]:
                raise UserError("전송 대기가 있어 서버 최신 기록으로 덮어쓰지 않습니다.")
            db.execute("DELETE FROM cache")
            for kind, rows, ref_key in (("requests", requests, "requestId"), ("sales", sales, "orderNumber")):
                for row in rows:
                    reference = row[ref_key] if kind == "requests" else row["metadata"][ref_key]
                    index = self._index(kind, reference)
                    db.execute("INSERT INTO cache VALUES (?,?,?)", (kind, index, self._seal(row, kind + ":" + index)))
            db.execute("INSERT OR REPLACE INTO config VALUES ('snapshot',?)", (self._seal(marker, "snapshot"),))

    def replace_requests(self, requests, cursor, deleted, quote_read_mode=None, authoritative=False):
        """Commit an independent inbox snapshot without touching sales or their outbox."""
        with self.mutex, self._db() as db:
            marker = self._read_snapshot_marker(db)
            switching_to_two_tabs = (
                quote_read_mode == "annual-two-tabs" and
                marker.get("quoteReadMode") != "annual-two-tabs"
            )
            require(authoritative or switching_to_two_tabs or
                    (cursor >= marker["requests"] and set(marker["deletedRequests"]) <= set(deleted)),
                    "문의 접수 순번이나 삭제 기록이 이전 상태입니다.")
            db.execute("DELETE FROM cache WHERE kind='requests'")
            for row in requests:
                index = self._index("requests", row["requestId"])
                db.execute("INSERT INTO cache VALUES ('requests',?,?)", (index, self._seal(row, "requests:" + index)))
            marker.update(requests=cursor, deletedRequests=sorted(deleted),
                          quoteReadMode=quote_read_mode)
            db.execute("INSERT OR REPLACE INTO config VALUES ('snapshot',?)", (self._seal(marker, "snapshot"),))

    def queue(self, operation, optimistic=None):
        with self.mutex, self._db() as db:
            if operation["action"] != "save_sale" and db.execute("SELECT COUNT(*) FROM outbox").fetchone()[0]:
                raise UserError("먼저 기존 전송 대기를 동기화해주세요.")
            identifier = operation["id"]
            db.execute("INSERT INTO outbox(id,body) VALUES (?,?)", (identifier, self._seal(operation, "outbox:" + identifier)))
            if optimistic is not None:
                index = self._index("sales", optimistic["metadata"]["orderNumber"])
                db.execute("INSERT OR REPLACE INTO cache VALUES ('sales',?,?)", (index, self._seal(optimistic, "sales:" + index)))

    def backup(self, reason, min_interval=None):
        """Keep 30 generated snapshots; only routine downloads may be throttled (seconds)."""
        folder = self.path.parent / "백업"
        with self.mutex:
            folder.mkdir(parents=True, exist_ok=True)
            candidates = []
            for path in folder.glob("namufarm-*.db"):
                # Protect unrelated files, even if their name has the same prefix.
                if not re.fullmatch(r"namufarm-\d{8}-\d{6}-\d{6}\.db", path.name) or path.is_symlink():
                    continue
                try:
                    if path.is_file():
                        candidates.append((path.stat().st_mtime, path))
                except OSError:
                    continue
            if min_interval is not None and candidates and time.time() - max(t for t, _ in candidates) < min_interval:
                return None
            target = folder / (datetime.now().strftime("namufarm-%Y%m%d-%H%M%S-%f") + ".db")
            try:
                with closing(sqlite3.connect(self.path)) as source, closing(sqlite3.connect(target)) as destination:
                    source.backup(destination)
            except Exception:
                try:
                    target.unlink()
                except OSError:
                    pass
                raise
            # Pin this snapshot even if system clock/old mtimes are ahead of it.
            keep = {target, *(path for _, path in sorted(candidates, key=lambda item: (item[0], item[1].name), reverse=True)[:29])}
            for _, path in candidates:
                if path not in keep:
                    try:
                        path.unlink()
                    except OSError:
                        pass
            return target

    def save_local_sales(self, sales, operations, hidden, *, quotes=None, marker=None, sold_labels=None):
        """Commit a merge/delete, its upload journal and local deletion markers together."""
        with self.mutex, self._db() as db:
            db.execute("DELETE FROM cache WHERE kind='sales'")
            for row in sales:
                index = self._index("sales", row["metadata"]["orderNumber"])
                db.execute("INSERT INTO cache VALUES ('sales',?,?)", (index, self._seal(row, "sales:" + index)))
            db.execute("DELETE FROM outbox")
            for op in operations:
                db.execute("INSERT INTO outbox(id,body) VALUES (?,?)", (op["id"], self._seal(op, "outbox:" + op["id"])))
            preferences = {"hidden_sales": sorted(set(hidden))}
            if quotes is not None:
                preferences["confirmed_quotes"] = quotes
            if sold_labels is not None:
                preferences["sold_display_labels"] = sold_labels
            for name, value in preferences.items():
                scope = "workspace:" + name
                db.execute("INSERT OR REPLACE INTO config VALUES (?,?)", (scope, self._seal(value, scope)))
            if marker is not None:
                db.execute("INSERT OR REPLACE INTO config VALUES ('snapshot',?)", (self._seal(marker, "snapshot"),))

    def acknowledge(self, identifier):
        with self.mutex, self._db() as db:
            row = db.execute("SELECT body FROM outbox WHERE id=?", (identifier,)).fetchone()
            if row is None:
                raise UserError("전송 대기가 변경되었습니다. 다시 동기화해주세요.")
            operation = self._open(row[0], "outbox:" + identifier)
            if operation["action"] == "close_request":
                request_id = operation["payload"]["requestId"]
                marker = self._read_snapshot_marker(db)
                marker["deletedRequests"] = sorted(set(marker["deletedRequests"]) | {request_id})
                db.execute("DELETE FROM cache WHERE kind='requests' AND id=?", (self._index("requests", request_id),))
                db.execute("INSERT OR REPLACE INTO config VALUES ('snapshot',?)", (self._seal(marker, "snapshot"),))
            # Cache deletion, resurrection protection and clearing the outbox
            # commit together, only after the server acknowledgement was checked.
            if operation["action"] == "delete_sale":
                number = operation["payload"]["orderNumber"]
                marker = self._read_snapshot_marker(db)
                marker["deletedSales"] = sorted(set(marker.get("deletedSales", [])) | {number})
                db.execute("DELETE FROM cache WHERE kind='sales' AND id=?", (self._index("sales", number),))
                db.execute("INSERT OR REPLACE INTO config VALUES ('snapshot',?)", (self._seal(marker, "snapshot"),))
            db.execute("DELETE FROM outbox WHERE id=?", (identifier,))

    def discard_conflict(self, identifier, requests, sales, marker):
        with self.mutex, self._db() as db:
            db.execute("DELETE FROM outbox WHERE id=?", (identifier,))
            # Separate public methods cannot commit halfway through a discard/replace.
            db.execute("DELETE FROM cache")
            for kind, rows in (("requests", requests), ("sales", sales)):
                for row in rows:
                    reference = row["requestId"] if kind == "requests" else row["metadata"]["orderNumber"]
                    index = self._index(kind, reference)
                    db.execute("INSERT INTO cache VALUES (?,?,?)", (kind, index, self._seal(row, kind + ":" + index)))
            db.execute("INSERT OR REPLACE INTO config VALUES ('snapshot',?)", (self._seal(marker, "snapshot"),))

    def purge(self, clock=None):
        clock = now_ms() if clock is None else clock
        with self.mutex, self._db() as db:
            for kind, index, blob in list(db.execute("SELECT kind,id,body FROM cache")):
                row = self._open(blob, kind + ":" + index)
                expired = kind == "requests" and row["expiresAt"] <= clock
                # Server retention must never silently delete this PC's sales history.
                if expired:
                    db.execute("DELETE FROM cache WHERE kind=? AND id=?", (kind, index))


