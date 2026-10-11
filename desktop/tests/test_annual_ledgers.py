"""PC -> real Apps Script source contract, using synthetic data and fake Sheets."""
import copy
import tempfile
import unittest
from datetime import datetime
from pathlib import Path

from namufarm_desktop.commerce import new_draft, new_manual_draft
from namufarm_desktop.domain import SEOUL
from namufarm_desktop.errors import ConflictError, NetworkError, UserError
from namufarm_desktop.service import Manager
from namufarm_desktop.store import Vault
from support import Bridge, TestApi
from test_simple_connection import TestAccount


class AnnualLedgerFlowTest(unittest.TestCase):
    def setUp(self):
        self.bridge = Bridge()
        self.assertEqual('annual-v1', self.bridge.send(control='annual_setup')['storage'])
        self.tmp = tempfile.TemporaryDirectory()
        self.manager = self.other_manager('pc')

    def tearDown(self):
        self.bridge.close()
        self.tmp.cleanup()

    def other_manager(self, name):
        vault = Vault.create_device(Path(self.tmp.name) / (name + '.db'), TestAccount())
        api = TestApi(self.bridge)
        api.token = vault.credentials()['deviceToken']
        manager = Manager(vault, api)
        manager.pair(self.bridge.send(control='invite')['invite'])
        return manager

    def sent_quote(self):
        self.assertTrue(self.bridge.send(control='submit_plain')['ok'])
        request = self.manager.sync()['requests'][0]
        draft = new_draft(request)
        draft['items'][0].update(unitPrice=1000, unitCost=500)
        self.manager.confirm_quote(draft)
        self.manager.mark_quote_sent(draft)
        return draft

    def locations(self):
        return self.bridge.send(control='annual_locations')

    def test_web_quote_keeps_sent_status_and_receipt_when_payment_is_synced(self):
        draft = self.sent_quote()
        before = self.locations()['requests']
        view = self.manager.confirm_draft(draft)
        self.assertEqual('견적발송완료', view['requests'][0]['status'])
        self.assertEqual(before, self.locations()['requests'])
        self.assertEqual([], view['drafts'])
        row = view['sales'][0]
        self.assertEqual('입금확인', row['metadata']['status'])
        self.assertEqual(draft['requestReceiptNumber'], row['private']['order']['sourceQuoteNumber'])
        month = datetime.fromtimestamp(row['metadata']['paidAt'] / 1000, SEOUL)
        location = self.locations()['sales'][0]
        self.assertEqual(f'나무팜_주문_{month:%Y}', location['file'])
        self.assertEqual(f'{month:%m}월', location['sheet'])

    def test_offline_payment_and_shipping_are_durable_and_replay_once(self):
        draft = self.sent_quote()
        self.manager.api.offline = True
        with self.assertRaises(NetworkError):
            self.manager.confirm_draft(draft)
        local = self.manager.view()
        paid = local['sales'][0]['metadata']['paidAt']
        payload = copy.deepcopy(local['pending'][0])
        self.assertEqual('입금확인', local['sales'][0]['metadata']['status'])
        self.assertEqual(0, len(self.locations()['sales']))
        with self.assertRaises(UserError):
            self.manager.confirm_draft(draft)
        self.assertEqual([payload], self.manager.vault.pending())
        # Manager recreation simulates restart without recalculating payment time.
        restarted = Manager(self.manager.vault, self.manager.api)
        self.manager.api.offline = False
        view = restarted.sync()
        self.assertEqual(paid, view['sales'][0]['metadata']['paidAt'])
        self.assertEqual(1, len(self.locations()['sales']))
        number = view['sales'][0]['metadata']['orderNumber']
        location = self.locations()['sales'][0]
        self.manager.api.offline = True
        with self.assertRaises(NetworkError):
            restarted.save_sale(number, 1, {'costConfirmed': True}, target='배송완료')
        self.assertEqual('배송완료', restarted.view()['sales'][0]['metadata']['status'])
        self.manager.api.offline = False
        final = restarted.sync()
        self.assertEqual('배송완료', final['sales'][0]['metadata']['status'])
        self.assertEqual(location['sheet'], self.locations()['sales'][0]['sheet'])
        self.assertEqual('견적발송완료', final['requests'][0]['status'])
        self.assertEqual([], final['pending'])

    def test_lost_server_ack_keeps_same_event_and_does_not_duplicate(self):
        draft = self.sent_quote()
        self.manager.api.lose_ack = True
        with self.assertRaises(NetworkError):
            self.manager.confirm_draft(draft)
        self.assertEqual(1, len(self.manager.vault.pending()))
        self.assertEqual(1, len(self.locations()['sales']))
        view = self.manager.sync()
        self.assertEqual(1, len(view['sales']))
        self.assertEqual([], view['pending'])
        self.assertEqual('견적발송완료', view['requests'][0]['status'])

    def test_second_pc_duplicate_is_rejected_and_can_adopt_existing_order(self):
        draft = self.sent_quote()
        other = self.other_manager('other')
        other_draft = new_draft(other.view()['requests'][0])
        other_draft['items'][0].update(unitPrice=1000, unitCost=500)
        other.save_draft(other_draft)
        mine = self.manager.confirm_draft(draft)['sales'][0]
        # The second device still has a sent quote and no order in its local cache.
        with self.assertRaises(ConflictError):
            other.confirm_draft(other_draft)
        self.assertEqual(1, len(self.locations()['sales']))
        preview = other.conflict_preview()
        self.assertEqual(mine['metadata']['orderNumber'], preview['remote']['metadata']['orderNumber'])
        view = other.use_server_version(preview)
        self.assertEqual([], view['pending'])
        self.assertEqual([], view['drafts'])
        self.assertEqual(1, len(view['sales']))

    def test_phone_quote_payment_uses_same_annual_order_route(self):
        draft = new_manual_draft()
        draft.update(customerName='테스트고객', customerPhone='01012345678', receiverName='테스트',
                     items=[{'species': '소나무', 'specification': '', 'quantity': 2, 'unitPrice': 1000, 'unitCost': 500}])
        draft = self.manager.mark_quote_sent(draft)['drafts'][0]
        view = self.manager.confirm_draft(draft)
        self.assertEqual(draft['requestReceiptNumber'], view['sales'][0]['private']['order']['sourceQuoteNumber'])
        self.assertTrue(self.locations()['sales'][0]['file'].startswith('나무팜_주문_'))
        self.assertEqual([], view['requests'])

    def test_rejected_create_can_be_held_and_replayed_with_identical_payment_event(self):
        draft = self.sent_quote()
        real_call = self.manager.api.call
        def reject_first_write(action, **fields):
            if action == 'save_sale':
                raise ConflictError('definitive refusal')
            return real_call(action, **fields)
        self.manager.api.call = reject_first_write
        try:
            with self.assertRaises(ConflictError):
                self.manager.confirm_draft(draft)
        finally:
            self.manager.api.call = real_call
        original = copy.deepcopy(self.manager.vault.pending()[0])
        self.assertTrue(original['rejected'])
        preview = self.manager.conflict_preview()
        self.assertEqual('rejected_create', preview['kind'])
        view = self.manager.hold_conflicted_payment(preview)
        self.assertEqual([], view['pending'])
        self.assertEqual([], view['sales'])
        self.assertEqual(original, view['heldPayments'][0]['operation'])
        self.assertNotIn(draft['customerName'].encode('utf-8'), self.manager.vault.path.read_bytes())
        restarted = Manager(self.manager.vault, self.manager.api)
        self.assertEqual(original, restarted.view()['heldPayments'][0]['operation'])
        saved = restarted.retry_held_payment(original['id'])
        self.assertEqual([], saved['heldPayments'])
        self.assertEqual([], saved['pending'])
        self.assertEqual(1, len(saved['sales']))
        self.assertEqual(original['payload']['eventId'], saved['sales'][0]['eventId'])
        self.assertEqual(original['payload']['metadata']['paidAt'], saved['sales'][0]['metadata']['paidAt'])
        self.assertEqual(1, len(self.locations()['sales']))

    def test_closed_source_keeps_rejected_payment_safe_without_blocking_other_orders(self):
        draft = self.sent_quote()
        other = self.other_manager('closer')
        other.close_request(draft['sourceRequestId'])
        with self.assertRaises(ConflictError):
            self.manager.confirm_draft(draft)
        operation = copy.deepcopy(self.manager.vault.pending()[0])
        preview = self.manager.conflict_preview()
        self.assertEqual('rejected_create', preview['kind'])
        held = self.manager.hold_conflicted_payment(preview)
        self.assertEqual([], held['pending'])
        self.assertEqual(operation, held['heldPayments'][0]['operation'])
        with self.assertRaises(UserError):
            self.manager.retry_held_payment(operation['id'])
        self.assertEqual([], self.manager.vault.pending())
        manual = new_manual_draft()
        manual.update(customerName='새 고객', customerPhone='01012345678', receiverName='새 고객',
                      items=[{'species': '소나무', 'specification': '', 'quantity': 1, 'unitPrice': 1000, 'unitCost': 500}])
        sent = self.manager.mark_quote_sent(manual)['drafts'][0]
        view = self.manager.confirm_draft(sent)
        self.assertEqual(1, len(view['sales']))
        self.assertEqual(operation, view['heldPayments'][0]['operation'])

    def test_offline_payment_is_not_mistaken_for_definite_rejection(self):
        draft = self.sent_quote()
        self.manager.api.offline = True
        with self.assertRaises(NetworkError):
            self.manager.confirm_draft(draft)
        self.manager.api.offline = False
        with self.assertRaises(UserError):
            self.manager.conflict_preview()
        self.assertFalse(self.manager.vault.pending()[0].get('rejected', False))
        self.assertEqual([], self.manager.vault.held_payments())

    def test_stale_pc_update_never_overwrites_newer_shipping_record(self):
        draft = self.sent_quote()
        row = self.manager.confirm_draft(draft)['sales'][0]
        number = row['metadata']['orderNumber']
        other = self.other_manager('other')
        self.manager.save_sale(number, 1, {'note': '최신 메모'})
        with self.assertRaises(ConflictError):
            other.save_sale(number, 1, {'note': '오래된 화면 수정'})
        preview = other.conflict_preview()
        self.assertEqual('최신 메모', other.decode_sale(preview['remote'])['order']['note'])
        view = other.use_server_version(preview)
        self.assertEqual('최신 메모', view['sales'][0]['private']['order']['note'])


if __name__ == '__main__':
    unittest.main()
