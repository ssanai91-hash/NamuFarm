class UserError(Exception):
    """Messages are deliberately safe to display without customer data."""

    def __init__(self, message, *, code=None):
        super().__init__(message)
        self.code = code


class ConflictError(UserError):
    pass


class AuthenticationError(UserError):
    """The server explicitly rejected this saved connection."""
    pass


class NetworkError(UserError):
    pass



class UncertainDeliveryError(NetworkError):
    """The external side effect may have completed; retain its delivery ID."""


ENROLLMENT_NOTICE = ("새 PC 등록은 서버 소유자의 허용이 필요합니다. Apps Script 편집기에서 openAppEnrollment를 "
                     "실행한 뒤 15분 안에 ‘서버 동기화’를 눌러주세요. 기존 자료는 유지됩니다.")
