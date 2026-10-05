import hashlib
import hmac
import json
import os
import sqlite3
import threading
from contextlib import contextmanager, closing
from pathlib import Path

from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from .crypto import canonical, password_key, SellerKey
from .domain import now_ms, sale_expired
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
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        salt = os.urandom(16)
        vault = cls(path, password_key(password, salt))
        try:
            descriptor = os.open(path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            os.close(descriptor)
        except FileExistsError:
            raise UserError("기존 PC 보관함이 있습니다. 먼저 잠금을 해제해주세요.") from None
        try:
            with vault._db() as db:
                db.executescript("CREATE TABLE config (name TEXT PRIMARY KEY, value BLOB NOT NULL);"
                                 "CREATE TABLE cache (kind TEXT, id TEXT, body BLOB NOT NULL, PRIMARY KEY(kind,id));"
                                 "CREATE TABLE outbox (seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT UNIQUE, body BLOB NOT NULL);")
                db.execute("INSERT INTO config VALUES ('version',?)", (b"1",))
                db.execute("INSERT INTO config VALUES ('salt',?)", (salt,))
                db.execute("INSERT INTO config VALUES ('credentials',?)",
                           (vault._seal({**seller.material(), "deviceToken": os.urandom(32).hex(), "paired": False}, "credentials"),))
        except Exception:
            path.unlink(missing_ok=True)
            raise
        return vault

    @classmethod
    def unlock(cls, path, password):
        try:
            if not Path(path).is_file():
                raise ValueError()
            with closing(sqlite3.connect(f"file:{Path(path).resolve().as_posix()}?mode=ro", uri=True)) as db:
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

    def queue(self, operation, optimistic=None):
        with self.mutex, self._db() as db:
            if db.execute("SELECT COUNT(*) FROM outbox").fetchone()[0]:
                raise UserError("먼저 기존 전송 대기를 동기화해주세요.")
            identifier = operation["id"]
            db.execute("INSERT INTO outbox(id,body) VALUES (?,?)", (identifier, self._seal(operation, "outbox:" + identifier)))
            if optimistic is not None:
                index = self._index("sales", optimistic["metadata"]["orderNumber"])
                db.execute("INSERT OR REPLACE INTO cache VALUES ('sales',?,?)", (index, self._seal(optimistic, "sales:" + index)))

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
            pending = db.execute("SELECT COUNT(*) FROM outbox").fetchone()[0]
            for kind, index, blob in list(db.execute("SELECT kind,id,body FROM cache")):
                row = self._open(blob, kind + ":" + index)
                expired = kind == "requests" and row["expiresAt"] <= clock
                if kind == "sales" and not pending:
                    expired = sale_expired(row["metadata"], clock)
                if expired:
                    db.execute("DELETE FROM cache WHERE kind=? AND id=?", (kind, index))
