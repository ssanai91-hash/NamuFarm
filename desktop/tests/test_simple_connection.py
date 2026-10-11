import copy
import os
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from namufarm_desktop.crypto import customer_digest
from namufarm_desktop.domain import metadata
from namufarm_desktop.errors import NetworkError, UserError
from namufarm_desktop.service import Manager
from namufarm_desktop.store import Vault
from namufarm_desktop.commerce import new_draft
from support import Bridge, TestApi, manager_at, PASSWORD
from namufarm_desktop.api import DEFAULT_URL, RETIRED_URL


class TestAccount:
    def __init__(self):
        self.key = AESGCM.generate_key(bit_length=256)

    def protect(self, data):
        iv = os.urandom(12)
        return iv + AESGCM(self.key).encrypt(iv, data, b'test account')

    def unprotect(self, data):
        return AESGCM(self.key).decrypt(data[:12], data[12:], b'test account')


class DeviceVaultTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'device.db'
        self.account = TestAccount()

    def tearDown(self):
        self.tmp.cleanup()

    def test_create_and_reopen_without_password_or_seller_key(self):
        vault = Vault.create_device(self.path, self.account)
        self.assertNotIn('privateKey', vault.credentials())
        vault.save_preference('drafts', [{'customerName': '로컬고객보호검증'}])
        token = vault.credentials()['deviceToken']
        self.assertNotIn(token.encode(), self.path.read_bytes())
        self.assertNotIn('로컬고객보호검증'.encode(), self.path.read_bytes())
        reopened = Vault.open_device(self.path, self.account)
        self.assertEqual(reopened.credentials()['deviceToken'], token)
        self.assertEqual(reopened.preference('drafts'), vault.preference('drafts'))
        original = self.path.read_bytes()
        with self.assertRaises(UserError):
            Vault.open_device(self.path, TestAccount())
        with self.assertRaises(UserError):
            Vault.create_device(self.path, self.account)
        self.assertEqual(original, self.path.read_bytes())

    def test_configured_backend_is_not_silently_repointed_with_pending_work(self):
        vault = Vault.create_device(self.path, self.account)
        credentials = vault.credentials()
        credentials.update(url=RETIRED_URL, paired=True)
        vault.save_credentials(credentials)
        vault.save_preference('drafts', [{'customerName': '저장한 초안'}])
        vault.queue({'id': 'unchanged-event', 'action': 'close_request', 'payload': {'requestId': 'test'}})
        pending = vault.pending()
        manager = Manager(vault)
        self.assertEqual(RETIRED_URL, manager.api.url)
        self.assertTrue(vault.credentials()['paired'])
        self.assertEqual(credentials['deviceToken'], vault.credentials()['deviceToken'])
        self.assertEqual(pending, vault.pending())
        self.assertEqual([{'customerName': '저장한 초안'}], vault.preference('drafts'))

    def test_custom_backend_is_preserved(self):
        vault = Vault.create_device(self.path, self.account)
        credentials = vault.credentials()
        credentials.update(url='https://script.google.com/macros/s/AKcustomBackend01234567890/exec', paired=True)
        vault.save_credentials(credentials)
        self.assertEqual(credentials['url'], Manager(vault).api.url)
        self.assertEqual(credentials, vault.credentials())

    def test_legacy_conversion_preserves_cache_token_and_pending_id(self):
        bridge = Bridge()
        try:
            manager = manager_at(self.path, bridge)
            before = manager.vault.credentials()
            manager.vault.queue({'id': 'pending-unchanged', 'action': 'close_request', 'payload': {'requestId': 'test'}})
            pending = manager.vault.pending()
            manager.vault.use_device_protection(self.account)
            reopened = Vault.open_device(self.path, self.account)
            self.assertEqual(before, reopened.credentials())
            self.assertEqual(pending, reopened.pending())
            self.assertTrue(Vault.device_managed(self.path))
        finally:
            bridge.close()

    @unittest.skipUnless(sys.platform == 'win32', 'Windows DPAPI integration runs in Windows CI')
    def test_real_windows_account_reopens_without_manual_password(self):
        vault = Vault.create_device(self.path)
        self.assertEqual(vault.credentials(), Vault.open_device(self.path).credentials())
        self.assertNotIn(vault.secret, self.path.read_bytes())


class KeylessFlowTest(unittest.TestCase):
    def setUp(self):
        self.bridge = Bridge()
        self.bridge.send(control='clear_key')
        self.tmp = tempfile.TemporaryDirectory()
        self.account = TestAccount()
        self.vault = Vault.create_device(Path(self.tmp.name) / 'new.db', self.account)
        api = TestApi(self.bridge)
        api.token = self.vault.credentials()['deviceToken']
        self.manager = Manager(self.vault, api)
        self.manager.pair(self.bridge.send(control='invite')['invite'])

    def tearDown(self):
        self.bridge.close()
        self.tmp.cleanup()

    def test_request_quote_purchase_payment_shipping_without_rsa_key(self):
        receipt = self.bridge.send(control='submit_plain')
        self.assertTrue(receipt['ok'])
        view = self.manager.sync()
        draft = new_draft(view['requests'][0])
        draft['items'][0]['unitPrice'] = 1000
        self.manager.confirm_quote(draft)
        self.manager.mark_quote_sent(draft)
        view = self.manager.confirm_draft(draft)
        row = view['sales'][0]
        self.assertIn('personalData', row)
        self.assertNotIn('encryptedPersonal', row)
        self.assertIsNone(self.manager.seller)
        self.assertEqual('견적발송완료', view['requests'][0]['status'])
        number = row['metadata']['orderNumber']
        view = self.manager.save_sale(number, 1, {})
        self.assertEqual('입금확인', view['sales'][0]['metadata']['status'])
        self.assertFalse(self.vault.pending())
        self.assertNotIn('김나무'.encode(), self.vault.path.read_bytes())
        # A new PC with its own local key reads server records without importing a backup.
        other_vault = Vault.create_device(Path(self.tmp.name) / 'replacement.db', TestAccount())
        api = TestApi(self.bridge)
        api.token = other_vault.credentials()['deviceToken']
        other = Manager(other_vault, api)
        recovered = other.pair(self.bridge.send(control='invite')['invite'])
        self.assertEqual(view['sales'][0]['private'], recovered['sales'][0]['private'])

    def test_legacy_record_without_key_keeps_cursor_and_reports_recovery(self):
        self.bridge.close()
        self.bridge = Bridge()  # Existing server with a v4 record and its pinned key.
        self.manager.api = TestApi(self.bridge)
        self.manager.api.token = self.vault.credentials()['deviceToken']
        self.manager.pair(self.bridge.send(control='invite')['invite'])
        self.bridge.send(control='submit')
        before = self.vault.snapshot_marker()
        with self.assertRaisesRegex(UserError, '이전 버전 자료'):
            self.manager.sync()
        self.assertEqual(before, self.vault.snapshot_marker())
        self.manager.restore_legacy(self.bridge.initial['backup'], PASSWORD)
        self.assertEqual(1, len(self.manager.sync()['requests']))

