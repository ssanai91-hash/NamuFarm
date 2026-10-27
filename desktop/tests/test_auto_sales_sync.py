"""Automatic upload sequencing with a synthetic outbox; no network."""
import copy
import unittest
from unittest.mock import Mock
from namufarm_desktop.errors import ConflictError, NetworkError
from namufarm_desktop.service import Manager


class AutoSalesSyncTest(unittest.TestCase):
    def manager(self):
        manager = Manager.__new__(Manager)
        manager.vault = Mock()
        manager.vault.pending.return_value = [{'id': 'event', 'action': 'save_sale', 'payload': {'kept': True}}]
        manager.sync_requests = Mock()
        manager.local_copy_view = Mock(side_effect=lambda: {'transferNotice': getattr(manager, 'transfer_notice', '')})
        manager.upload_sales = Mock(return_value={'uploaded': True})
        return manager

    def test_auto_sync_pulls_before_upload_and_skips_when_no_sale_waits(self):
        manager = self.manager()
        order = []
        manager.sync_requests.side_effect = lambda: order.append('pull')
        manager.upload_sales.side_effect = lambda: order.append('upload') or {'uploaded': True}
        self.assertTrue(manager.auto_sync()['uploaded'])
        self.assertEqual(['pull', 'upload'], order)
        manager.vault.pending.return_value = []
        manager.auto_sync()
        self.assertEqual(['pull', 'upload', 'pull'], order)

    def test_conflict_stops_and_preserves_outbox(self):
        manager = self.manager()
        before = copy.deepcopy(manager.vault.pending())
        manager.upload_sales.side_effect = ConflictError('conflict')
        with self.assertRaises(ConflictError):
            manager.auto_sync()
        self.assertEqual(before, manager.vault.pending())
        manager.sales_conflict_number = 'SOLD_TEST'
        manager.upload_sales.reset_mock()
        manager.auto_sync()
        manager.upload_sales.assert_not_called()

    def test_network_failure_is_notice_only_and_local_save_remains_committed(self):
        manager = self.manager()
        before = copy.deepcopy(manager.vault.pending())
        manager.upload_sales.side_effect = NetworkError('offline')
        self.assertIn('판매자료 서버 반영 대기 1건', manager.auto_sync()['transferNotice'])
        self.assertEqual(before, manager.vault.pending())
        self.assertIn('판매자료 서버 반영 대기 1건', manager._auto_upload_sales()['transferNotice'])
