"""Draft expiry is explicit and announced; confirmed storage is independent."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from namufarm_desktop.commerce import new_manual_draft
from namufarm_desktop.service import Manager
from namufarm_desktop.store import Vault
from test_simple_connection import TestAccount


class DraftRetentionTest(unittest.TestCase):
    def test_day_29_warns_view_never_deletes_day_30_and_startup_announces_purge(self):
        with tempfile.TemporaryDirectory() as folder:
            vault = Vault.create_device(Path(folder) / 'test.db', TestAccount())
            clock = 1800000000000
            current, expired, confirmed = new_manual_draft(), new_manual_draft(), new_manual_draft()
            current.update(createdAt=clock - 29 * 86400000, workingCopy=True, manualStatus='작성중')
            expired.update(createdAt=clock - 30 * 86400000, workingCopy=True, manualStatus='작성중')
            confirmed.update(createdAt=clock - 60 * 86400000, quoteStatus='견적확정')
            vault.save_preference('confirmed_quotes', [confirmed])
            vault.save_preference('drafts', [current])
            with patch('namufarm_desktop.service.now_ms', return_value=clock):
                manager = Manager(vault)
                self.assertEqual(1, manager.view()['draftExpiryDays'][current['id']])
                vault.save_preference('drafts', [current, expired])
                self.assertEqual(2, len(manager.view()['drafts']))
                self.assertEqual(2, len(vault.preference('drafts')))
                self.assertEqual(1, manager.purge_expired_drafts())
                self.assertIn('30일 지난 작성중 견적 1건 정리', manager.view()['transferNotice'])
                self.assertEqual([confirmed], vault.preference('confirmed_quotes'))
                vault.save_preference('drafts', [current, expired])
                reopened = Manager(vault)
                self.assertIn('30일 지난 작성중 견적 1건 정리', reopened.view()['transferNotice'])
                self.assertEqual([current], vault.preference('drafts'))
