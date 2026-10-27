"""Run actual Apps Script delivery endpoints against isolated Sheet/provider fixtures."""
import copy
import tempfile
import unittest
from pathlib import Path

from namufarm_desktop.commerce import new_draft, quote_delivery_fingerprint
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
        self.assertEqual(quote_delivery_fingerprint(self.draft), result['delivery']['emailQuoteFingerprint'])
        self.assertEqual(result['delivery'], Manager(self.vault, self.api).quote_delivery_state(self.draft['id']))
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
        self.assertEqual(quote_delivery_fingerprint(self.draft), state['smsQuoteFingerprint'])
        edited = copy.deepcopy(self.draft)
        edited['shippingFee'] += 1000
        self.assertNotEqual(quote_delivery_fingerprint(edited), state['smsQuoteFingerprint'])
        edited = copy.deepcopy(self.draft)
        edited['items'][0]['unitPrice'] += 500
        self.assertNotEqual(quote_delivery_fingerprint(edited), state['smsQuoteFingerprint'])
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


    def test_timeout_retains_id_retries_same_id_and_blocks_changed_payload(self):
        from namufarm_desktop.errors import UncertainDeliveryError
        call = self.api.call
        seen = []
        def timeout(action, **fields):
            if action == 'send_quote_email':
                seen.append(fields['deliveryId'])
                raise NetworkError('timeout')
            return call(action, **fields)
        self.api.call = timeout
        for _ in range(2):
            with self.assertRaises(UncertainDeliveryError):
                self.manager.send_quote_email(self.draft, '견적', '본문', '<p>본문</p>')
        self.assertEqual(seen[0], seen[1])
        pending = Manager(self.vault, self.api).quote_delivery_state(self.draft['id'])['pendingDelivery']
        self.assertEqual(seen[0], pending['id'])
        with self.assertRaisesRegex(UserError, '이전 발송 결과'):
            self.manager.send_quote_email(self.draft, '견적', '변경', '<p>변경</p>')
        self.assertEqual(2, len(seen))
        self.api.call = call
        state = self.manager.check_quote_delivery(self.draft['id'], 'email')
        self.assertEqual('not_found', state['deliveryResolution'])
        self.assertNotIn('pendingDelivery', state)
        self.assertTrue(self.manager.send_quote_email(self.draft, '견적', '본문', '<p>본문</p>')['sent'])

    def test_lost_accepted_response_recovers_email_and_payment_requires_resolution(self):
        from namufarm_desktop.errors import UncertainDeliveryError
        call = self.api.call
        def lose_ack(action, **fields):
            result = call(action, **fields)
            if action == 'send_quote_email':
                raise NetworkError('lost acknowledgement')
            return result
        self.api.call = lose_ack
        with self.assertRaises(UncertainDeliveryError):
            self.manager.send_quote_email(self.draft, '견적', '본문', '<p>본문</p>')
        with self.assertRaisesRegex(UserError, '이전 발송 결과'):
            self.manager.mark_quote_sent(self.draft)
        self.api.call = call
        state = self.manager.check_quote_delivery(self.draft['id'], 'email')
        self.assertGreater(state['emailSentAt'], 0)
        self.assertEqual(quote_delivery_fingerprint(self.draft), state['emailQuoteFingerprint'])
        self.assertNotIn('pendingDelivery', state)

    def test_sms_acceptance_recovery_retains_carrier_success_check(self):
        from namufarm_desktop.errors import UncertainDeliveryError
        self.bridge.send(control='configure_sms')
        call = self.api.call
        def lose_ack(action, **fields):
            result = call(action, **fields)
            if action == 'send_quote_sms':
                raise NetworkError('lost acknowledgement')
            return result
        self.api.call = lose_ack
        with self.assertRaises(UncertainDeliveryError):
            self.manager.send_quote_sms(self.draft, '견적 안내')
        self.api.call = call
        state = self.manager.check_quote_delivery(self.draft['id'], 'sms')
        self.assertEqual('success', state['smsState'])
        self.assertTrue(state['smsMessageId'])
        self.assertNotIn('pendingDelivery', state)

    def test_definite_rejection_clears_pending_unknown_retains_it_and_dismiss_is_audited(self):
        from namufarm_desktop.errors import UncertainDeliveryError
        call = self.api.call
        def unavailable(action, **fields):
            if action == 'send_quote_email':
                return {'sent': False, 'state': 'unknown'}
            return call(action, **fields)
        self.api.call = unavailable
        with self.assertRaises(UncertainDeliveryError):
            self.manager.send_quote_email(self.draft, '견적', '본문', '<p>본문</p>')
        first = self.manager.quote_delivery_state(self.draft['id'])['pendingDelivery']['id']
        state = self.manager.dismiss_uncertain_delivery(self.draft['id'], 'email')
        self.assertEqual(first, state['dismissedDeliveries'][-1]['id'])
        self.assertNotIn('pendingDelivery', state)
        def rejected(action, **fields):
            if action == 'send_quote_email':
                raise UserError('invalid', code='INVALID')
            return call(action, **fields)
        self.api.call = rejected
        with self.assertRaises(UserError):
            self.manager.send_quote_email(self.draft, '견적', '본문', '<p>본문</p>')
        self.assertNotIn('pendingDelivery', self.manager.quote_delivery_state(self.draft['id']))

    def test_old_server_stops_send_and_report_timeout_keeps_export_and_id(self):
        from namufarm_desktop.errors import UncertainDeliveryError
        original = self.manager._require_delivery_server
        self.manager._server_info_cache = None
        self.api.info_filter = lambda info: {**info, 'quoteDeliveryIdempotency': 0}
        with self.assertRaisesRegex(UserError, '새 버전 배포'):
            self.manager.send_quote_email(self.draft, '견적', '본문', '<p>본문</p>')
        self.api.info_filter = None
        self.manager._server_info_cache = None
        call = self.api.call
        ids = []
        def timeout(action, **fields):
            if action == 'send_sales_report_email':
                ids.append(fields['deliveryId'])
                raise NetworkError('timeout')
            return call(action, **fields)
        self.api.call = timeout
        for _ in range(2):
            with self.assertRaises(UncertainDeliveryError):
                self.manager.send_sales_report_email('seller@example.com', 'report.xlsx', b'PK\x03\x04test', '매출', '집계')
        self.assertEqual(ids[0], ids[1])
        self.assertEqual(ids[0], self.vault.preference('sales_report_delivery')['pendingDelivery']['id'])
