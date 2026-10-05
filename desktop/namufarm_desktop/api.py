import json
import re
import urllib.error
import urllib.parse
import urllib.request

from .crypto import canonical
from .errors import ConflictError, NetworkError, UserError

DEFAULT_URL = "https://script.google.com/macros/s/AKfycby0dlMj7qKxXZDsShc92WQn0DW0YyOHjJIEsVCM4xrd8PDzVqVL1JuQLkPuxPxxyGDR/exec"
MAX_RESPONSE = 8 * 1024 * 1024


def validate_url(url):
    parts = urllib.parse.urlsplit(url)
    if (parts.scheme != "https" or parts.netloc != "script.google.com" or parts.query or parts.fragment
            or not re.fullmatch(r"/macros/s/AK[A-Za-z0-9_-]{20,200}/exec", parts.path)):
        raise UserError("Google Apps Script 웹 앱의 HTTPS /exec 주소를 입력해주세요.")
    return url


class GoogleRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parts = urllib.parse.urlsplit(newurl)
        if parts.scheme != "https" or parts.netloc not in ("script.google.com", "script.googleusercontent.com"):
            raise NetworkError("허용되지 않은 서버 이동을 차단했습니다.")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class ScriptApi:
    def __init__(self, url=DEFAULT_URL, token="", opener=None):
        self.url = validate_url(url)
        self.token = token
        self.opener = opener or urllib.request.build_opener(GoogleRedirect())

    def _send(self, payload=None):
        try:
            data = None if payload is None else canonical(payload).encode("utf-8")
            if data is not None and len(data) > 65000:
                raise UserError("전송할 기록이 너무 큽니다.")
            request = urllib.request.Request(self.url, data=data,
                       headers={"Content-Type": "application/json", "User-Agent": "NamuFarmDesktop/0.1"})
            with self.opener.open(request, timeout=20) as response:
                raw = response.read(MAX_RESPONSE + 1)
                if len(raw) > MAX_RESPONSE:
                    raise UserError("서버 응답이 너무 큽니다. 기존 기록을 유지합니다.")
            result = json.loads(raw)
            if not isinstance(result, dict) or result.get("ok") is not True:
                code = result.get("code") if isinstance(result, dict) else None
                if code == "CONFLICT":
                    raise ConflictError("앱이나 다른 PC에서 먼저 수정했습니다. 전송 대기를 확인해주세요.")
                messages = {"UNAUTHORIZED": "기기 연결 코드를 다시 확인해주세요.",
                            "NOT_READY": "앱 보안 연결과 서버 설정을 먼저 완료해주세요.",
                            "INVALID": "입력값을 확인해주세요.", "BUSY": "서버가 처리 중입니다. 잠시 후 동기화해주세요."}
                raise UserError(messages.get(code, "서버가 저장을 확인하지 못했습니다. 전송 대기는 유지됩니다."))
            return result
        except (UserError, ConflictError):
            raise
        except Exception:
            raise NetworkError("서버에 연결하지 못했습니다. 로컬 기록과 전송 대기를 유지합니다.") from None

    def info(self):
        result = self._send()
        if result.get("service") != "namufarm-quote-requests" or result.get("version") != 4:
            raise UserError("나무팜 API v4 서버를 연결해주세요.")
        return result

    def call(self, action, **fields):
        if not re.fullmatch(r"[a-f0-9]{64}", self.token):
            raise UserError("PC 기기 연결을 먼저 완료해주세요.")
        return self._send({"action": action, "deviceToken": self.token, **fields})
