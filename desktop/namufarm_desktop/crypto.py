import base64
import copy
import hashlib
import json
import os

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC

from .errors import UserError

ALGORITHM = "RSA-OAEP-256+A256GCM"


def canonical(value):
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"),
                      allow_nan=False).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")


def customer_digest(metadata, personal):
    personal = copy.deepcopy(personal)
    personal['order'].pop('orderId', None)
    if personal.get('quoteDocumentJson') is None:
        personal.pop('quoteDocumentJson', None)
    return hashlib.sha256(canonical({"metadata": metadata, "personalData": personal}).encode()).hexdigest()


def digest(metadata, envelope):
    return hashlib.sha256(canonical({"metadata": metadata, "encryptedPersonal": envelope}).encode()).hexdigest()


def encode(value):
    return base64.b64encode(value).decode("ascii")


def decode(value, minimum=0, maximum=45000):
    try:
        if not isinstance(value, str) or len(value) > maximum * 2:
            raise ValueError()
        result = base64.b64decode(value, validate=True)
        if encode(result) != value or not minimum <= len(result) <= maximum:
            raise ValueError()
        return result
    except (ValueError, TypeError):
        raise UserError("암호화 데이터 형식을 확인할 수 없습니다.") from None


def password_key(password, salt, algorithm="sha256"):
    if not isinstance(password, str) or len(password) < 12:
        raise UserError("보안 비밀번호는 12자 이상 입력해주세요.")
    return PBKDF2HMAC(algorithm=hashes.SHA1() if algorithm == "sha1" else hashes.SHA256(),
                     length=32, salt=salt, iterations=1300000).derive(password.encode("utf-8"))


class SellerKey:
    def __init__(self, private_encoded, public_encoded):
        try:
            private = serialization.load_der_private_key(decode(private_encoded, 100, 10000), password=None)
            public = serialization.load_der_public_key(decode(public_encoded, 422, 422))
            if not isinstance(private, rsa.RSAPrivateKey) or not isinstance(public, rsa.RSAPublicKey):
                raise ValueError()
            if private.key_size != 3072 or public.key_size != 3072 or public.public_numbers().e != 65537:
                raise ValueError()
            if private.public_key().public_numbers() != public.public_numbers():
                raise ValueError()
            self.private, self.public, self.public_encoded = private, public, public_encoded
            self.key_id = hashlib.sha256(public_encoded.encode("ascii")).hexdigest()
        except UserError:
            raise
        except Exception:
            raise UserError("앱의 정상적인 보안 백업 파일을 선택해주세요.") from None

    @classmethod
    def from_backup(cls, backup_text, password):
        try:
            if len(backup_text.encode("utf-8")) >= 20000:
                raise ValueError()
            backup = json.loads(backup_text)
            if (backup["version"] != 1 or backup["kdf"] != "PBKDF2-HMAC-SHA1"
                    or backup["iterations"] != 1300000):
                raise ValueError()
            salt, nonce = decode(backup["salt"], 16, 16), decode(backup["iv"], 12, 12)
            secret = password_key(password, salt, "sha1")
            material = json.loads(AESGCM(secret).decrypt(nonce, decode(backup["ciphertext"], 17, 18000),
                                                       b"namufarm-seller-backup-v1"))
            return cls(material["privateKey"], material["publicKey"])
        except Exception:
            raise UserError("보안 백업 파일이나 비밀번호가 맞지 않습니다.") from None

    def material(self):
        return {"privateKey": encode(self.private.private_bytes(serialization.Encoding.DER,
                 serialization.PrivateFormat.PKCS8, serialization.NoEncryption())),
                "publicKey": self.public_encoded}

    @staticmethod
    def _aad(kind, reference):
        if kind not in ("quote", "order") or not isinstance(reference, str) or not 1 <= len(reference) <= 120 or any(ord(c) < 32 for c in reference):
            raise UserError("암호화 기록 번호를 확인해주세요.")
        return f"namufarm:v1:{kind}:{reference}".encode("utf-8")

    def seal(self, value, kind, reference):
        plain = canonical(value).encode("utf-8")
        if not 1 <= len(plain) <= 30000:
            raise UserError("고객 기록이 너무 큽니다. 내용을 줄여주세요.")
        secret, nonce = os.urandom(32), os.urandom(12)
        ciphertext = AESGCM(secret).encrypt(nonce, plain, self._aad(kind, reference))
        wrapped = self.public.encrypt(secret, padding.OAEP(mgf=padding.MGF1(hashes.SHA256()),
                                     algorithm=hashes.SHA256(), label=None))
        return {"version": 1, "algorithm": ALGORITHM, "keyId": self.key_id,
                "wrappedKey": encode(wrapped), "iv": encode(nonce), "ciphertext": encode(ciphertext)}

    def open(self, envelope, kind, reference):
        try:
            if (set(envelope) != {"version", "algorithm", "keyId", "wrappedKey", "iv", "ciphertext"}
                    or envelope["version"] != 1 or envelope["algorithm"] != ALGORITHM
                    or envelope["keyId"] != self.key_id):
                raise ValueError()
            secret = self.private.decrypt(decode(envelope["wrappedKey"], 384, 384),
                       padding.OAEP(mgf=padding.MGF1(hashes.SHA256()), algorithm=hashes.SHA256(), label=None))
            if len(secret) != 32:
                raise ValueError()
            plain = AESGCM(secret).decrypt(decode(envelope["iv"], 12, 12),
                        decode(envelope["ciphertext"], 17, 30016), self._aad(kind, reference))
            return json.loads(plain)
        except Exception:
            raise UserError("고객정보의 보안키 또는 기록 무결성을 확인할 수 없습니다.") from None
