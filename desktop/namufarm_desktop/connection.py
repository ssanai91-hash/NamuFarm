"""Validate manually entered shared credentials without a connection file."""
import re

from .api import validate_url
from .domain import require

def valid_connection_key(password):
    return isinstance(password, str) and re.fullmatch(r"[a-f0-9]{64}", password.strip()) is not None


def connection_input(url, password):
    require(isinstance(url, str), "나무팜 서버 주소를 확인해주세요.")
    require(isinstance(password, str), "공통 연결 암호를 입력해주세요.")
    key = password.strip()
    require(valid_connection_key(key),
            "공통 연결 암호를 확인해주세요. 현재 연결키 64자리를 입력합니다.")
    return validate_url(url.strip()), key
