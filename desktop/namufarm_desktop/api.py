import json
import re
import urllib.error
import urllib.parse
import urllib.request

from .crypto import canonical
from .errors import ConflictError, NetworkError, UserError

RETIRED_URL = "https://script.google.com/macros/s/AKfycbw8uqBWveIhqmXielALjSLnY1yDenKAwl070FT6Lsxsj7Xiod1C5JSBDZGfQtlrl6A-CQ/exec"
PREVIOUS_URL = "https://script.google.com/macros/s/AKfycbzY7JKzXYmS9APLNFDt15k8dv09zb4EXKFHOOv_T1vpFmkr6TLwbqGznGLgpd2cIVgD/exec"
DEFAULT_URL = "https://script.google.com/macros/s/AKfycbw4GnfIlXyjMo9TPZmCmRnmIIzd016piTDma-xrh7w/exec"
MAX_RESPONSE = 8 * 1024 * 1024
# Allow the server's 15-second lock wait plus connection/startup overhead.
# urllib applies this to blocking I/O, not the entire redirect/request chain.
REQUEST_TIMEOUT = 75
ACTION_LABELS = {"pair_device": "PC 연결", "close_request": "문의 삭제", "save_sale": "판매 저장",
                 "update_request_status": "견적 발송 상태 저장", "email_profile": "메일 발신 계정 확인",
                 "send_quote_email": "견적 메일 전송", "sms_profile": "문자 발신 설정 확인",
                 "send_quote_sms": "견적 문자 전송", "quote_sms_status": "문자 전송 결과 확인",
                 "list_requests": "문의 목록 조회",
                 "list_sales": "판매 목록 조회", "sales_report": "판매 통계 조회"}


def validate_url(url):
    parts = urllib.parse.urlsplit(url)
    if (parts.scheme != "https" or parts.netloc != "script.google.com" or parts.query or parts.fragment
            or not re.fullmatch(r"/macros/s/AK[A-Za-z0-9_-]{20,200}/exec", parts.path)):
        raise UserError("Google Apps Script 웹 앱의 HTTPS /exec 주소를 입력해주세요.")
    return url


class GoogleRedirect(urllib.request.HTTPRedirectHandler):
    @staticmethod
    def _allowed_host(host):
        return (host == "script.google.com"
                or host == "script.googleusercontent.com"
                or host.endswith("-script.googleusercontent.com"))

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        parts = urllib.parse.urlsplit(newurl)
        if parts.scheme != "https" or not self._allowed_host(parts.hostname or ""):
            host = parts.hostname or "(호스트 없음)"
            raise NetworkError(f"허용되지 않은 서버 이동을 차단했습니다. 이동 대상: {host}")
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class ScriptApi:
    def __init__(self, url=DEFAULT_URL, token="", opener=None):
        self.url = validate_url(url)
        self.token = token
        self.opener = opener or urllib.request.build_opener(GoogleRedirect())

    def _send(self, payload=None):
        label = "서버 보안 확인" if payload is None else ACTION_LABELS.get(payload.get("action"), "서버 요청")

        def failure(reason):
            return NetworkError(f"{label} 중 {reason} 로컬 기록과 전송 대기를 유지합니다. '지금 동기화'로 재시도해주세요.")

        try:
            data = None if payload is None else canonical(payload).encode("utf-8")
            if data is not None and len(data) > 65000:
                raise UserError("전송할 기록이 너무 큽니다.")
            request = urllib.request.Request(self.url, data=data,
                       headers={"Content-Type": "application/json", "User-Agent": "NamuFarmDesktop/0.4"})
            with self.opener.open(request, timeout=REQUEST_TIMEOUT) as response:
                raw = response.read(MAX_RESPONSE + 1)
                if len(raw) > MAX_RESPONSE:
                    raise UserError("서버 응답이 너무 큽니다. 기존 기록을 유지합니다.")
            result = json.loads(raw)
            if not isinstance(result, dict) or result.get("ok") is not True:
                code = result.get("code") if isinstance(result, dict) else None
                if code == "CONFLICT":
                    raise ConflictError("앱이나 다른 PC에서 먼저 수정했습니다. 전송 대기를 확인해주세요.")
                messages = {"UNAUTHORIZED": "연결파일을 다시 불러와주세요. 이전 방식에서는 연결 코드를 확인해주세요.",
                            "NOT_READY": "구글시트 서버 설정과 배포 버전을 확인해주세요.",
                            "UPGRADE_REQUIRED": "앱과 구글시트 서버를 최신 버전으로 업데이트해주세요.",
                            "INVALID_ACTION": "현재 연결된 Apps Script /exec 배포가 이전 버전입니다. 기존 웹앱을 새 버전으로 다시 배포해주세요.",
                            "UNAVAILABLE": "Apps Script 실행 중 오류가 발생했습니다. Apps Script의 실행 기록에서 실패 원인을 확인해주세요.",
                            "INVALID": "입력값과 앱·서버 버전을 확인해주세요.", "BUSY": "서버가 처리 중입니다. 잠시 후 동기화해주세요."}
                raise UserError(messages.get(code, "서버가 저장을 확인하지 못했습니다. 전송 대기는 유지됩니다."))
            return result
        except (UserError, ConflictError):
            raise
        except urllib.error.HTTPError as error:
            raise failure(f"서버가 HTTP {error.code} 오류를 반환했습니다.") from None
        except TimeoutError:
            raise failure("서버 응답 대기 시간이 초과되었습니다.") from None
        except urllib.error.URLError as error:
            reason = "서버 응답 대기 시간이 초과되었습니다." if isinstance(error.reason, TimeoutError) else "서버에 연결하지 못했습니다."
            raise failure(reason) from None
        except (json.JSONDecodeError, UnicodeError):
            raise failure("올바른 서버 응답을 받지 못했습니다. Apps Script 웹 앱 배포와 접근 설정을 확인해주세요.") from None
        except Exception:
            raise failure("서버에 연결하지 못했습니다.") from None

    def info(self):
        result = self._send()
        if result.get("service") != "namufarm-quote-requests" or result.get("version") != 5:
            raise UserError("구글시트 서버를 나무팜 API v5로 업데이트해주세요.")
        return result

    def call(self, action, **fields):
        if not re.fullmatch(r"[a-f0-9]{64}", self.token):
            raise UserError("PC 기기 연결을 먼저 완료해주세요.")
        return self._send({"action": action, "deviceToken": self.token, **fields})


