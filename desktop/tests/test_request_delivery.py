"""Run actual Apps Script delivery endpoints against isolated Sheet/provider fixtures."""
import tempfile
import unittest
from pathlib import Path

from namufarm_desktop.commerce import new_draft
from namufarm_desktop.errors import UserError, NetworkError
from namufarm_desktop.service import Manager
from namufarm_desktop.store import Vault
from test_simple_connection import TestAccount
from support import Bridge, TestApi


class RequestDeliveryTest(unittest.TestCase):
    def setUp(self):
        self.bridge = Bridge()
        self.tmp = tempfile.TemporaryDirectory()
        self.vault = Vault.create_device(Path(self.tmp.name) / 'test.db', TestAccount())
        self.api = TestApi(self.bridge)
        self.api.token = self.vault.credentials()['deviceToken']
        self.manager = Manager(self.vault, self.api)
        self.manager.connect()
        self.bridge.send(control='submit_plain')
        request = self.manager.sync_requests()['requests'][0]
        self.draft = new_draft(request)
        self.draft.update(customerEmail='sample@example.com', shippingFee=7000, isPrepaid=True)
        self.draft['items'][0]['unitPrice'] = 2500

    def tearDown(self):
        self.bridge.close()
        self.tmp.cleanup()

    def assert_request_still_new(self):
        view = self.manager.sync_requests()
        self.assertEqual('신규', view['requests'][0]['status'])
        self.assertEqual([], view['confirmedQuotes'])
        recovered = next(d for d in view['drafts'] if d['id'] == self.draft['id'])
        self.assertEqual(7000, recovered['shippingFee'])
        self.assertTrue(recovered['isPrepaid'])
        self.assertEqual(2500, recovered['items'][0]['unitPrice'])

    def test_email_with_pdf_from_new_request_does_not_confirm_until_explicit_action(self):
        pdf = Path(self.tmp.name) / 'test_quote.pdf'
        pdf.write_bytes(b'%PDF-1.4\nFixture quotation\n')
        result = self.manager.send_quote_email(self.draft, '견적 안내', '37,000원 택배비 포함',
                                               '<p>37,000원 택배비 포함</p>', attachment_path=pdf)
        self.assertEqual(pdf.name, result['attachmentName'])
        self.assertGreater(result['delivery']['emailSentAt'], 0)
        self.assert_request_still_new()
        view = self.manager.confirm_quote(self.draft)
        self.assertEqual(1, len(view['confirmedQuotes']))
        quote = view['confirmedQuotes'][0]
        self.assertEqual('견적확정', quote['quoteStatus'])
        self.assertEqual(7000, quote['shippingFee'])
        self.assertTrue(quote['isPrepaid'])

    def test_sms_from_new_request_keeps_pending_and_final_delivery_separate_from_confirmation(self):
        self.bridge.send(control='configure_sms')
        result = self.manager.send_quote_sms(self.draft, '묘목 30,000원 + 선불 택배비 7,000원 = 37,000원')
        self.assertEqual('pending', result['delivery']['smsState'])
        self.assertEqual(0, result['delivery']['smsSentAt'])
        self.assert_request_still_new()
        state = self.manager.refresh_quote_sms_status(self.draft['id'])
        self.assertEqual('success', state['smsState'])
        self.assert_request_still_new()

    def test_stale_requests_are_blocked_and_failed_delivery_preserves_recovery_inputs(self):
        self.manager.inbox_requests[0]['status'] = '입금확인'
        count = self.api.calls.count('send_quote_email')
        with self.assertRaises(UserError):
            self.manager.send_quote_email(self.draft, '견적 안내', '본문', '<p>본문</p>')
        self.assertEqual(count, self.api.calls.count('send_quote_email'))
        self.manager.sync_requests()
        # Persist input before an external provider/transport failure, without confirmation.
        call = self.api.call
        def fail_send(action, **fields):
            if action == 'send_quote_email':
                raise NetworkError('isolated transport failure')
            return call(action, **fields)
        self.api.call = fail_send
        with self.assertRaises(NetworkError):
            self.manager.send_quote_email(self.draft, '견적 안내', '본문', '<p>본문</p>')
        self.assert_request_still_new()
