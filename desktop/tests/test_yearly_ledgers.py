"""Real desktop Manager -> actual Apps Script, using synthetic Sheet storage."""
import tempfile
import unittest
from pathlib import Path

from namufarm_desktop.commerce import new_draft
from namufarm_desktop.errors import NetworkError, UserError
from support import Bridge, manager_at


class YearlyLedgerFlowTest(unittest.TestCase):
    def setUp(self):
        self.bridge = Bridge()
        self.tmp = tempfile.TemporaryDirectory()
        self.manager = manager_at(Path(self.tmp.name) / 'vault.db', self.bridge)
        result = self.bridge.send(control='setup_yearly')
        self.assertTrue(result.get('ready'), result)
        self.bridge.send(control='submit_plain')
        request = self.manager.sync()['requests'][0]
        self.draft = new_draft(request)
        self.draft.update(zipNo='12345', roadAddress='서울 테스트로 1', detailAddress='100호',
                          items=[dict(species='소나무', specification='H1.0m', quantity=2,
                                      unitPrice=10000, unitCost=5000)])
        self.manager.confirm_quote(self.draft)
        self.manager.mark_quote_sent(self.draft)

    def tearDown(self):
        self.bridge.close()
        self.tmp.cleanup()

    def test_payment_and_delivery_update_order_but_keep_original_quote_sent(self):
        view = self.manager.confirm_draft(self.draft)
        self.assertEqual('입금확인', view['requests'][0]['status'])
        self.assertEqual('견적발송완료', view['requests'][0]['quoteStatus'])
        self.assertEqual('견적발송완료', self.manager.vault.rows('requests')[0]['status'])
        number = view['sales'][0]['metadata']['orderNumber']
        self.assertEqual(number, view['requests'][0]['linkedOrderNumber'])
        view = self.manager.save_sale(number, 1, {'costConfirmed': True}, target='배송완료')
        self.assertEqual('배송완료', view['requests'][0]['status'])
        state = self.bridge.send(control='ledger_summary')
        self.assertEqual('견적발송완료', state['quotes'][0]['status'])
        self.assertTrue(state['quotes'][0]['book'].startswith('나무팜_견적_'))
        self.assertEqual([dict(number=number, status='배송완료', revision=2)], state['orders'])

    def test_lost_payment_ack_keeps_outbox_then_retries_the_same_order_once(self):
        self.manager.api.lose_ack = True
        with self.assertRaises(NetworkError):
            self.manager.confirm_draft(self.draft)
        pending = self.manager.vault.pending()
        self.assertEqual(1, len(pending))
        view = self.manager.sync()
        self.assertEqual([], view['pending'])
        self.assertEqual(1, len(view['sales']))
        state = self.bridge.send(control='ledger_summary')
        self.assertEqual(1, len(state['orders']))
        self.assertEqual('견적발송완료', state['quotes'][0]['status'])

    def test_payment_reclick_is_rejected_without_creating_another_order(self):
        self.manager.confirm_draft(self.draft)
        with self.assertRaises(UserError):
            self.manager.confirm_draft(self.draft)
        self.assertEqual(1, len(self.bridge.send(control='ledger_summary')['orders']))
        self.assertEqual([], self.manager.vault.pending())
