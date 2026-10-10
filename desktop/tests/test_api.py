import io
import json
import unittest
import urllib.error

from namufarm_desktop.api import DEFAULT_URL, REQUEST_TIMEOUT, GoogleRedirect, ScriptApi
from namufarm_desktop.errors import NetworkError


class FakeOpener:
    def __init__(self, outcome):
        self.outcome = outcome
        self.calls = []

    def open(self, request, timeout):
        self.calls.append((request, timeout))
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return io.BytesIO(self.outcome)


class ApiTest(unittest.TestCase):
    def assert_failure(self, outcome, expected, action='close_request'):
        opener = FakeOpener(outcome)
        api = ScriptApi(token='a' * 64, opener=opener)
        with self.assertRaises(NetworkError) as caught:
            if action:
                api.call(action, requestId='private-reference')
            else:
                api.info()
        message = str(caught.exception)
        self.assertIn(expected, message)
        self.assertIn('전송 대기를 유지', message)
        self.assertEqual(1, len(opener.calls))
        self.assertNotIn('a' * 64, message)
        self.assertNotIn('private-reference', message)
        self.assertNotIn('secret-details', message)
        return message

    def test_delete_timeout_identifies_stage_without_automatic_post_retry(self):
        message = self.assert_failure(TimeoutError('secret-details'), '대기 시간이 초과')
        self.assertIn('문의 삭제 중', message)

    def test_wrapped_timeout_is_reported_as_timeout(self):
        self.assert_failure(urllib.error.URLError(TimeoutError('secret-details')), '대기 시간이 초과')

    def test_connection_failure_reports_lookup_stage_without_raw_error(self):
        message = self.assert_failure(urllib.error.URLError('secret-details'), '연결하지 못했습니다', 'list_requests')
        self.assertIn('문의 목록 조회 중', message)

    def test_http_failure_preserves_status_without_body_or_redirect_url(self):
        error = urllib.error.HTTPError(DEFAULT_URL + '?secret-details', 503, 'secret-details', {}, None)
        self.assert_failure(error, 'HTTP 503')

    def test_non_json_response_reports_deployment_check(self):
        self.assert_failure(b'<html>secret-details</html>', '배포와 접근 설정')

    def test_server_info_failure_identifies_security_check(self):
        message = self.assert_failure(TimeoutError(), '대기 시간이 초과', action=None)
        self.assertIn('서버 보안 확인 중', message)

    def test_redirect_allows_apps_script_one_time_content_hosts(self):
        redirect = GoogleRedirect()
        self.assertTrue(redirect._allowed_host("script.googleusercontent.com"))
        self.assertTrue(redirect._allowed_host("n-abc123-script.googleusercontent.com"))
        self.assertFalse(redirect._allowed_host("googleusercontent.com"))
        self.assertFalse(redirect._allowed_host("evil-script.googleusercontent.com.attacker.example"))

    def test_redirect_error_reports_only_blocked_host(self):
        redirect = GoogleRedirect()
        request = urllib.request.Request(DEFAULT_URL)
        with self.assertRaises(NetworkError) as caught:
            redirect.redirect_request(request, None, 302, "Found", {}, "https://accounts.google.com/login?secret=hidden")
        message = str(caught.exception)
        self.assertIn("accounts.google.com", message)
        self.assertNotIn("secret", message)
        self.assertNotIn("/login", message)

    def test_valid_ack_is_returned_with_one_bounded_post(self):
        opener = FakeOpener(b'{"ok":true,"closed":true,"requestId":"test-request"}')
        api = ScriptApi(token='a' * 64, opener=opener)
        result = api.call('close_request', requestId='test-request')
        self.assertTrue(result['closed'])
        request, timeout = opener.calls[0]
        self.assertEqual(REQUEST_TIMEOUT, timeout)
        self.assertEqual('POST', request.get_method())
        self.assertEqual('test-request', json.loads(request.data)['requestId'])
        self.assertEqual(1, len(opener.calls))


if __name__ == '__main__':
    unittest.main()
