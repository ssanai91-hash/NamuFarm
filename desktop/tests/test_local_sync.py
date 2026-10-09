import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile
from xml.etree import ElementTree as ET

from namufarm_desktop.connection import read_connection
from namufarm_desktop.errors import UserError, NetworkError
from namufarm_desktop.service import Manager
from namufarm_desktop.store import Vault
from namufarm_desktop.commerce import new_draft
from test_simple_connection import TestAccount
from support import Bridge, TestApi


class LocalSyncTest(unittest.TestCase):
    def setUp(self):
        self.bridge = Bridge()
        self.tmp = tempfile.TemporaryDirectory()
        self.vault = Vault.create_device(Path(self.tmp.name) / 'local.db', TestAccount())
        self.api = TestApi(self.bridge)
        self.api.token = self.vault.credentials()['deviceToken']
        self.manager = Manager(self.vault, self.api)
        self.profile = self.bridge.send(control='connection_file')
        self.manager.connect_file(json.dumps(self.profile).encode(), lambda url, token: self.api)

    def tearDown(self):
        self.bridge.close()
        self.tmp.cleanup()

    def test_file_connects_and_cleared_sheet_replaces_cache_but_preserves_draft(self):
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

    def test_invalid_file_does_not_change_connection(self):
        before = self.vault.credentials()
        for overrides in ({'url':'https://example.com/exec'}, {'connectionKey':'bad'}, {'version':True}):
            with self.assertRaises(UserError):
                read_connection(json.dumps({**self.profile, **overrides}).encode())
        with self.assertRaises(UserError):
            read_connection(b' '*8193)
        self.assertEqual(before, self.vault.credentials())
