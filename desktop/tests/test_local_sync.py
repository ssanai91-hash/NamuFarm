import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile
from xml.etree import ElementTree as ET

from namufarm_desktop.connection import connection_input
from namufarm_desktop.errors import AuthenticationError, UserError, NetworkError
from namufarm_desktop.service import Manager
from namufarm_desktop.store import Vault
from namufarm_desktop.commerce import new_draft
from test_simple_connection import TestAccount
from support import Bridge, TestApi, PASSWORD


class LocalSyncTest(unittest.TestCase):
    def setUp(self):
        self.bridge = Bridge()
        self.tmp = tempfile.TemporaryDirectory()
        self.vault = Vault.create_device(Path(self.tmp.name) / 'local.db', TestAccount())
        self.api = TestApi(self.bridge)
        self.api.token = self.vault.credentials()['deviceToken']
        self.manager = Manager(self.vault, self.api)
        self.manager.connect()

    def tearDown(self):
        self.bridge.close()
        self.tmp.cleanup()

    def test_auto_connects_and_cleared_sheet_replaces_cache_but_preserves_draft(self):
        self.bridge.send(control='submit_plain')
        view = self.manager.sync_requests()
        draft = new_draft(view['requests'][0])
        self.manager.save_draft(draft)
        self.assertTrue(self.manager.request_copy_path.exists())
        self.bridge.send(control='clear_requests')
        view = self.manager.sync_requests()
        self.assertEqual([], view['requests'])
        self.assertEqual(draft['id'], view['drafts'][0]['id'])
        self.assertTrue(self.vault.credentials()['paired'])
        self.assertNotIn('connectionKey', self.vault.credentials())

    def test_truncated_or_offline_snapshot_keeps_cache_and_excel(self):
        self.bridge.send(control='submit_plain')
        before = self.manager.sync_requests()['requests']
        book = self.manager.request_copy_path.read_bytes()
        self.api.page_filter = lambda action, response: {**response, 'requests': []}
        with self.assertRaises(UserError):
            self.manager.sync_requests()
        self.api.offline = True
        with self.assertRaises(NetworkError):
            self.manager.sync_requests()
        self.assertEqual(before, self.manager.view()['requests'])
        self.assertEqual(book, self.manager.request_copy_path.read_bytes())

    def test_excel_preserves_phone_and_literal_customer_text_and_reports_open_file(self):
        self.bridge.send(control='submit_plain')
        view = self.manager.sync_requests()
        view['requests'][0]['personal']['customerName'] = '=1+1'
        from namufarm_desktop.request_excel import write_request_copy
        write_request_copy(self.manager.request_copy_path, view['requests'])
        with ZipFile(self.manager.request_copy_path) as book:
            sheet = ET.fromstring(book.read('xl/worksheets/sheet1.xml'))
        ns = {'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
        self.assertFalse(sheet.findall('.//s:f',ns))
        self.assertEqual('=1+1', sheet.find('.//s:c[@r="C2"]/s:is/s:t',ns).text)
        self.assertEqual('01012345678', sheet.find('.//s:c[@r="D2"]/s:is/s:t',ns).text)
        before = self.manager.request_copy_path.read_bytes()
        with patch('namufarm_desktop.request_excel.os.replace', side_effect=PermissionError()):
            result = self.manager.sync_requests()
        self.assertIn('엑셀', result['copyError'])
        self.assertEqual(before, self.manager.request_copy_path.read_bytes())
        self.assertEqual(1, len(result['requests']))

    def test_invalid_manual_input_does_not_change_connection(self):
        before = self.vault.credentials()
        for url, key in (('https://example.com/exec', 'c'*64), (self.api.url, 'bad'), (True, 'c'*64)):
            with self.assertRaises(UserError):
                connection_input(url, key)
        self.assertEqual(before, self.vault.credentials())

    def test_missing_registration_recovers_without_losing_draft_or_duplicating_pending_sale(self):
        self.manager.restore_legacy(self.bridge.initial['backup'], PASSWORD)
        self.bridge.send(control='seed')
        self.bridge.send(control='submit_plain')
        before = self.manager.sync()
        draft = new_draft(before['requests'][0])
        self.manager.save_draft(draft)
        credentials = self.vault.credentials()
        self.api.offline = True
        with self.assertRaises(NetworkError):
            self.manager.save_sale('ORDER_TEST', 1, {'receiverName': '전송 대기 수정'})
        operation = self.vault.pending()[0]
        self.api.offline = False
        self.bridge.send(control='revoke_devices')
        self.api.calls.clear()
        view = self.manager.connect()
        self.assertEqual(1, self.api.calls.count('connect_app'))
        self.assertEqual(credentials['deviceToken'], self.vault.credentials()['deviceToken'])
        self.assertEqual(credentials['url'], self.api.url)
        self.assertEqual(before['requests'], view['requests'])
        self.assertEqual([draft], view['drafts'])
        self.assertEqual(operation['id'], view['sales'][0]['eventId'])
        self.assertEqual(2, view['sales'][0]['metadata']['revision'])
        self.assertEqual('전송 대기 수정', view['sales'][0]['private']['order']['receiverName'])
        self.assertEqual([], view['pending'])
        self.assertEqual(2, self.bridge.send(control='stored')['order']['revision'])

    def test_valid_connection_and_offline_failure_do_not_register_again(self):
        credentials = self.vault.credentials()
        self.api.calls.clear()
        self.manager.connect()
        self.assertNotIn('connect_app', self.api.calls)
        self.api.calls.clear()
        self.api.offline = True
        with self.assertRaises(NetworkError):
            self.manager.connect()
        self.assertNotIn('connect_app', self.api.calls)
        self.assertEqual(credentials, self.vault.credentials())

    def test_old_server_reports_required_deployment_without_changing_credentials(self):
        credentials = self.vault.credentials()
        self.bridge.send(control='revoke_devices')
        self.api.info_filter = lambda info: {**info, 'connectionAuth': 'shared-key-v1'}
        self.api.calls.clear()
        with self.assertRaisesRegex(UserError, 'Apps Script'):
            self.manager.connect()
        self.assertNotIn('connect_app', self.api.calls)
        self.assertEqual(credentials, self.vault.credentials())

    def test_repeated_auth_rejection_stops_after_one_reenrollment(self):
        self.api.calls.clear()
        with patch.object(self.manager, 'sync', side_effect=AuthenticationError('rejected')) as sync:
            with self.assertRaises(AuthenticationError):
                self.manager.connect()
        self.assertEqual(2, sync.call_count)
        self.assertEqual(1, self.api.calls.count('connect_app'))

