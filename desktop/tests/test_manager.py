import copy
from contextlib import closing
import json
import sqlite3
import tempfile
import unittest
from pathlib import Path

from namufarm_desktop.api import DEFAULT_URL, ScriptApi, validate_url
from namufarm_desktop.crypto import SellerKey, digest, encode
from namufarm_desktop.domain import changed, metadata, now_ms, profit, validate_request
from namufarm_desktop.errors import ConflictError, NetworkError, UserError
from namufarm_desktop.store import Vault
from support import Bridge, manager_at, PASSWORD


class ManagerTest(unittest.TestCase):
    def setUp(self):
        # Reset actual server fixture by restarting; keeps tests independent.
        self.bridge = Bridge()
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'vault.db'
        self.manager = manager_at(self.path, self.bridge)
        self.bridge.send(control='seed')
        self.manager.sync()

    def tearDown(self):
        self.bridge.close()
        self.tmp.cleanup()

    def test_inbox_sync_preserves_pending_sale_and_fetches_new_request(self):
        self.manager.api.offline = True
        with self.assertRaises(NetworkError):
            self.manager.save_sale('ORDER_TEST', 1, {'receiverName': '전송 대기 고객'})
        pending = self.manager.vault.pending()
        sale = self.manager.vault.rows('sales')
        self.manager.api.offline = False
        request = self.bridge.send(control='submit_new')
        self.manager.api.calls.clear()
        view = self.manager.sync_requests()
        self.assertIn(request['requestId'], [r['requestId'] for r in view['requests']])
        self.assertEqual(pending, self.manager.vault.pending())
        self.assertEqual(sale, self.manager.vault.rows('sales'))
        self.assertEqual(['list_requests'], self.manager.api.calls)

    def test_node_backup_restore_and_python_encryption_match_real_server(self):
        self.manager.save_sale('ORDER_TEST', 1, {'receiverName': '변경 수령인', 'note': '첫 줄\n두 번째 줄'})
        stored = self.bridge.send(control='stored')
        self.assertEqual('변경 수령인', stored['order']['receiverName'])
        self.assertEqual('첫 줄\n두 번째 줄', stored['order']['note'])
        self.assertNotIn('변경 수령인', json.dumps(stored['cells'], ensure_ascii=False))
        self.assertFalse(self.manager.vault.pending())

    def test_website_crypto_fixture_decrypts_in_python(self):
        root = Path(__file__).resolve().parents[2]
        fixture = json.loads((root / 'app/src/test/resources/website-crypto-test.json').read_text())
        key = SellerKey(fixture['privateKey'], fixture['publicKey'])
        self.assertEqual(json.loads(fixture['plaintext']), key.open(fixture['encrypted'], 'quote', fixture['reference']))
        with self.assertRaises(UserError):
            key.open(fixture['encrypted'], 'order', fixture['reference'])

    def test_wrong_backup_password_and_tampering_rejected(self):
        with self.assertRaises(UserError):
            SellerKey.from_backup(self.bridge.initial['backup'], 'wrong-password-12345')
        row = self.manager.vault.rows('sales')[0]
        row['encryptedPersonal']['iv'] = encode(b'X' * 12)
        with self.assertRaises(UserError):
            self.manager.decode_sale(row)

    def test_vault_customer_key_token_and_outbox_are_encrypted(self):
        self.manager.api.offline = True
        with self.assertRaises(NetworkError):
            self.manager.save_sale('ORDER_TEST', 1, {'receiverName': '개인정보검사용수령인'})
        raw = self.path.read_bytes()
        for plain in ('개인정보검사용수령인', '01012345678', self.manager.credentials['deviceToken'], self.manager.credentials['privateKey']):
            self.assertNotIn(plain.encode(), raw)
        restored = Vault.unlock(self.path, PASSWORD)
        self.assertEqual(1, len(restored.pending()))
        with self.assertRaises(UserError):
            Vault.unlock(self.path, 'wrong-password-12345')

    def test_offline_edit_is_durable_and_flushes_before_pull(self):
        self.manager.api.offline = True
        with self.assertRaises(NetworkError):
            self.manager.save_sale('ORDER_TEST', 1, {'receiverName': '오프라인 변경'})
        self.assertEqual(1, len(self.manager.vault.pending()))
        self.manager.api.offline = False
        self.manager.api.calls.clear()
        view = self.manager.sync()
        self.assertEqual('오프라인 변경', view['sales'][0]['private']['order']['receiverName'])
        self.assertLess(self.manager.api.calls.index('save_sale'), self.manager.api.calls.index('list_sales'))

    def test_lost_server_ack_retries_same_event_without_second_revision(self):
        self.manager.api.lose_ack = True
        with self.assertRaises(NetworkError):
            self.manager.save_sale('ORDER_TEST', 1, {'receiverName': '응답 유실'})
        identifier = self.manager.vault.pending()[0]['id']
        self.manager.sync()
        self.assertEqual(2, self.bridge.send(control='stored')['order']['revision'])
        self.assertEqual(identifier, self.manager.vault.rows('sales')[0]['eventId'])
        self.assertFalse(self.manager.vault.pending())

    def test_mismatched_ack_keeps_pending(self):
        self.manager.api.bad_ack = True
        with self.assertRaises(UserError):
            self.manager.save_sale('ORDER_TEST', 1, {'receiverName': '잘못된 응답'})
        self.assertEqual(1, len(self.manager.vault.pending()))
        self.manager.sync()
        self.assertFalse(self.manager.vault.pending())

    def test_sheet_write_failure_keeps_pending_and_original_server(self):
        self.bridge.send(control='fail_write')
        with self.assertRaises(UserError):
            self.manager.save_sale('ORDER_TEST', 1, {'receiverName': '저장 실패'})
        self.assertEqual(1, self.bridge.send(control='stored')['order']['revision'])
        self.assertTrue(self.manager.vault.pending())
        self.manager.sync()
        self.assertEqual(2, self.bridge.send(control='stored')['order']['revision'])

    def test_receipt_failure_retries_after_main_row_commit(self):
        self.bridge.send(control='fail_receipt')
        with self.assertRaises(UserError):
            self.manager.save_sale('ORDER_TEST', 1, {'receiverName': '영수증 실패'})
        self.assertEqual(2, self.bridge.send(control='stored')['order']['revision'])
        self.manager.sync()
        self.assertFalse(self.manager.vault.pending())

    def test_two_pcs_conflict_does_not_overwrite_and_requires_explicit_discard(self):
        other = manager_at(Path(self.tmp.name) / 'second.db', self.bridge)
        self.manager.save_sale('ORDER_TEST', 1, {'receiverName': '서버 최신 수령인'})
        with self.assertRaises(ConflictError):
            other.save_sale('ORDER_TEST', 1, {'receiverName': '두 번째 PC 입력'})
        self.assertEqual('서버 최신 수령인', self.bridge.send(control='stored')['order']['receiverName'])
        preview = other.conflict_preview()
        self.assertEqual(1, len(other.vault.pending()))
        view = other.use_server_version(preview)
        self.assertEqual('서버 최신 수령인', view['sales'][0]['private']['order']['receiverName'])
        self.assertFalse(other.vault.pending())

    def test_changed_server_after_preview_keeps_unacknowledged_pc_edit(self):
        other = manager_at(Path(self.tmp.name) / 'second.db', self.bridge)
        self.manager.save_sale('ORDER_TEST', 1, {'receiverName': '최신1'})
        with self.assertRaises(ConflictError):
            other.save_sale('ORDER_TEST', 1, {'receiverName': 'PC 수정'})
        preview = other.conflict_preview()
        self.manager.save_sale('ORDER_TEST', 2, {'receiverName': '최신2'})
        with self.assertRaises(UserError):
            other.use_server_version(preview)
        self.assertEqual(1, len(other.vault.pending()))

    def test_state_workflow_requires_cost_confirmation_and_reports_commission(self):
        with self.assertRaises(UserError):
            self.manager.save_sale('ORDER_TEST', 1, {}, '배송확인')
        self.manager.save_sale('ORDER_TEST', 1, {}, '입금확인')
        self.manager.save_sale('ORDER_TEST', 2, {}, '배송확인')
        with self.assertRaises(UserError):
            self.manager.save_sale('ORDER_TEST', 3, {}, '판매완료')
        self.manager.save_sale('ORDER_TEST', 3, {'costConfirmed': True, 'profitMode': 'commission', 'commissionBps': 3000}, '판매완료')
        for unit in ('day', 'month', 'year'):
            report = self.manager.report(unit, '2000-01-01', '2099-12-31')
            self.assertEqual(1, report['buckets'][0]['orders'])
            self.assertEqual(530, report['buckets'][0]['knownProfit'])
        with self.assertRaises(UserError):
            self.manager.save_sale('ORDER_TEST', 4, {'costTotal': 0})

    def test_request_deletion_is_readable_after_previously_imported_cursor(self):
        request = self.bridge.send(control='submit')
        view = self.manager.sync()
        self.assertEqual(1, len(view['requests']))
        cursor = view['requests'][0]['sequence']
        self.manager.close_request(request['requestId'])
        tombstones = self.manager.api.call('list_requests', afterSequence=cursor)['requests']
        self.assertEqual(1, len(tombstones))
        self.assertTrue(tombstones[0]['expired'])
        self.assertFalse(self.manager.view()['requests'])

    def test_confirmed_delete_does_not_wait_for_unrelated_list_pulls(self):
        request = self.bridge.send(control='submit')
        self.manager.sync()
        old_sales = self.manager.vault.rows('sales')
        self.manager.api.calls.clear()
        self.manager.api.info_calls = 0
        def unavailable(action, result):
            raise NetworkError('list pull unavailable')
        self.manager.api.page_filter = unavailable
        view = self.manager.close_request(request['requestId'])
        self.assertEqual(['close_request'], self.manager.api.calls)
        self.assertEqual(1, self.manager.api.info_calls)
        self.assertFalse(view['requests'])
        self.assertFalse(view['pending'])
        self.assertEqual(old_sales, self.manager.vault.rows('sales'))
        self.assertIn(request['requestId'], self.manager.vault.snapshot_marker()['deletedRequests'])

    def test_sync_checks_pinned_server_once_before_flush_and_pull(self):
        self.manager.api.info_calls = 0
        self.manager.sync()
        self.assertEqual(1, self.manager.api.info_calls)

    def test_lost_delete_ack_keeps_request_and_durable_pending_for_idempotent_retry(self):
        request = self.bridge.send(control='submit')
        self.manager.sync()
        self.manager.api.lose_ack = True
        with self.assertRaises(NetworkError):
            self.manager.close_request(request['requestId'])
        pending = self.manager.vault.pending()
        self.assertEqual(1, len(pending))
        self.assertEqual(1, len(self.manager.view()['requests']))
        self.assertNotIn(request['requestId'], self.manager.vault.snapshot_marker()['deletedRequests'])
        tombstone = self.manager.api.call('list_requests', afterSequence=0)['requests'][0]
        self.assertTrue(tombstone['expired'])
        self.manager.vault = Vault.unlock(self.path, PASSWORD)
        self.assertEqual(pending, self.manager.vault.pending())
        view = self.manager.sync()
        self.assertFalse(view['requests'])
        self.assertFalse(view['pending'])
        retried = self.manager.api.call('list_requests', afterSequence=0)['requests'][0]
        self.assertEqual(tombstone['sequence'], retried['sequence'])

    def test_mismatched_delete_ack_keeps_local_request_and_pending(self):
        request = self.bridge.send(control='submit')
        self.manager.sync()
        self.manager.api.bad_ack = True
        with self.assertRaises(UserError):
            self.manager.close_request(request['requestId'])
        self.assertEqual(1, len(self.manager.view()['requests']))
        self.assertEqual(1, len(self.manager.vault.pending()))
        self.assertFalse(self.manager.sync()['requests'])

    def test_local_delete_ack_failure_rolls_back_cache_marker_and_pending_together(self):
        request = self.bridge.send(control='submit')
        self.manager.sync()
        old_marker = self.manager.vault.snapshot_marker()
        with closing(sqlite3.connect(self.path)) as db:
            db.execute("CREATE TRIGGER reject_ack BEFORE DELETE ON outbox BEGIN SELECT RAISE(ABORT,'injected'); END")
            db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            self.manager.close_request(request['requestId'])
        self.assertEqual(1, len(self.manager.view()['requests']))
        self.assertEqual(old_marker, self.manager.vault.snapshot_marker())
        self.assertEqual(1, len(self.manager.vault.pending()))
        with closing(sqlite3.connect(self.path)) as db:
            db.execute('DROP TRIGGER reject_ack')
            db.commit()
        self.assertFalse(self.manager.sync()['requests'])

    def test_failed_pull_after_retry_cannot_restore_confirmed_deleted_request(self):
        request = self.bridge.send(control='submit')
        self.manager.sync()
        self.manager.api.offline = True
        with self.assertRaises(NetworkError):
            self.manager.close_request(request['requestId'])
        self.manager.api.offline = False
        def unavailable(action, result):
            raise NetworkError('list pull unavailable')
        self.manager.api.page_filter = unavailable
        with self.assertRaises(NetworkError):
            self.manager.sync()
        self.assertFalse(self.manager.view()['requests'])
        self.assertFalse(self.manager.vault.pending())
        self.assertIn(request['requestId'], self.manager.vault.snapshot_marker()['deletedRequests'])
        self.manager.api.page_filter = None
        self.assertFalse(self.manager.sync()['requests'])

    def test_partial_or_corrupt_second_snapshot_does_not_replace_cache(self):
        original = self.manager.vault.rows('sales')
        self.bridge.send(control='submit')
        def corrupt(action, result):
            if action == 'list_sales':
                result['sales'][0]['metadata']['costTotal'] += 1
            return result
        self.manager.api.page_filter = corrupt
        with self.assertRaises(UserError):
            self.manager.sync()
        self.assertEqual(original, self.manager.vault.rows('sales'))
        self.assertFalse(self.manager.vault.rows('requests'))

    def test_empty_incomplete_sale_list_cannot_erase_existing_sale(self):
        original = self.manager.vault.rows('sales')
        self.manager.api.page_filter = lambda action, result: {**result, 'sales': []} if action == 'list_sales' else result
        with self.assertRaises(UserError):
            self.manager.sync()
        self.assertEqual(original, self.manager.vault.rows('sales'))

    def test_deleted_request_cannot_resurrect_even_with_newer_other_requests(self):
        request = self.bridge.send(control='submit')
        self.manager.sync()
        original = self.manager.vault.rows('requests')[0]
        self.manager.close_request(request['requestId'])
        self.bridge.send(control='submit_new')
        def rollback(action, result):
            if action == 'list_requests':
                result['requests'][0] = original
            return result
        self.manager.api.page_filter = rollback
        with self.assertRaises(UserError):
            self.manager.sync()
        self.assertFalse(self.manager.vault.rows('requests'))

    def test_pending_created_during_pull_blocks_whole_snapshot(self):
        def queue(action, result):
            if action == 'list_sales':
                self.manager.vault.queue({'id': 'race', 'action': 'close_request', 'payload': {}})
            return result
        original = self.manager.vault.rows('sales')
        self.manager.api.page_filter = queue
        with self.assertRaises(UserError):
            self.manager.sync()
        self.assertEqual(original, self.manager.vault.rows('sales'))
        self.assertTrue(self.manager.vault.pending())

    def test_old_server_and_wrong_pinned_key_rejected_before_pending_send(self):
        request = self.bridge.send(control='submit')
        self.manager.sync()
        self.manager.api.offline = True
        with self.assertRaises(NetworkError):
            self.manager.close_request(request['requestId'])
        self.manager.api.offline = False
        self.manager.api.calls.clear()
        for field, value in (('requestDeletionSync', False), ('sellerKey', {'keyId': 'wrong', 'publicKey': 'wrong'})):
            self.manager.api.info_filter = lambda info, field=field, value=value: {**info, field: value}
            with self.assertRaises(UserError):
                self.manager.sync()
            self.assertFalse(self.manager.api.calls)
            self.assertEqual(1, len(self.manager.vault.pending()))
            self.assertEqual(1, len(self.manager.view()['requests']))

    def test_snapshot_database_failure_rolls_back_existing_cache(self):
        with closing(sqlite3.connect(self.path)) as db:
            db.execute("CREATE TRIGGER reject_cache BEFORE INSERT ON cache BEGIN SELECT RAISE(ABORT,'injected'); END")
            db.commit()
        old = self.manager.vault.rows('sales')
        with self.assertRaises(sqlite3.IntegrityError):
            self.manager.sync()
        self.assertEqual(old, self.manager.vault.rows('sales'))

    def test_urls_and_quantity_match_server_contract(self):
        for url in ('http://script.google.com/macros/s/abc/exec', DEFAULT_URL + '?token=secret', DEFAULT_URL.replace('script.google.com', 'evil.example')):
            with self.assertRaises(UserError):
                validate_url(url)
        self.assertEqual(DEFAULT_URL, validate_url(DEFAULT_URL))
        plain = {'requestId': '11111111-1111-4111-8111-111111111111', 'customerName': '김나무', 'customerPhone': '01012345678', 'region': '옥천', 'items': [{'species': '소나무', 'quantity': 1000000}]}
        validate_request({'requestId': plain['requestId']}, plain)


if __name__ == '__main__':
    unittest.main()
