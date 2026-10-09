"""The same small connection file is imported by the PC and Android apps."""
import json

from .api import validate_url
from .domain import require
from .errors import UserError


def read_connection(raw):
    try:
        require(isinstance(raw, bytes) and len(raw) <= 8192, "연결파일 크기를 확인해주세요.")
        value = json.loads(raw.decode("utf-8-sig"))
        require(isinstance(value, dict) and value.get("format") == "namufarm-connection"
                and type(value.get("version")) is int and value["version"] == 1,
                "나무팜 연결파일을 선택해주세요.")
        key = value.get("connectionKey")
        require(isinstance(key, str) and len(key) == 64 and all(c in "0123456789abcdef" for c in key),
                "연결파일이 올바르지 않습니다.")
        return validate_url(value.get("url")), key
    except (ValueError, UnicodeError, TypeError):
        raise UserError("나무팜 연결파일을 읽을 수 없습니다.") from None
