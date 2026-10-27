"""No network/provider calls: inquiry durability, retries, expiry and workspace wiring."""
import copy
import tempfile
import unittest
from datetime import datetime, timezone, timedelta
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from zipfile import ZipFile

from namufarm_desktop.api import DEFAULT_URL
from namufarm_desktop.store import Vault
from namufarm_desktop.errors import NetworkError, UserError
from namufarm_desktop.inquiries import InquiryService, InquiryStore

IDENTIFIER = '00000001-0000-4000-8000-000000000001'

def record(sequence=1):
    now = datetime.now(timezone.utc).isoformat()
    return {'kind':'customer_inquiry','requestId':IDENTIFIER,'inquiryNumber':'INQ-20261024-0001',
            'sequence':sequence,'revision':sequence,'createdAt':now,'updatedAt':now,'status':'open','answeredAt':'',
            'inquiry':{'requestId':IDENTIFIER,'category':'구매·배송','customerName':'테스트고객',
                       'customerPhone':'01000000000','customerEmail':'fixture@example.invalid','message':'=문의 원문'},
            'reply':{'text':'','channel':'sms'},'delivery':None,'history':[], 'lastEventId':''}

class FakeApi:
    url = DEFAULT_URL
    def __init__(self):
        self.row = record()
        self.calls = []
        self.send_count = 0
        self.lose_send_ack = False
        self.bad_page = False
    def info(self):
        return {'customerInquiry':1, 'customerInquiryReady':True, 'customerInquiryWorkflow':2}
    def call(self, action, **p):
        self.calls.append((action, copy.deepcopy(p)))
        r = self.row
        if action == 'list_customer_inquiries':
            return {'inquiries':[copy.deepcopy(r)] if p['afterSequence'] < r['sequence'] else [],
                    'nextSequence':r['sequence'] + (1 if self.bad_page else 0), 'hasMore':False}
        if action == 'save_inquiry_reply':
            r['revision'] += 1; r['sequence'] += 1; r['lastEventId'] = p['eventId']
            r['reply'] = {'text':p['replyText'], 'channel':p['replyChannel']}; r['delivery'] = None
        if action == 'send_inquiry_reply':
            if not r['delivery'] or r['delivery']['id'] != p['deliveryId']:
                self.send_count += 1
                r['revision'] += 2; r['sequence'] += 2
                r['delivery'] = {'id':p['deliveryId'], 'channel':r['reply']['channel'], 'state':'pending', 'messageId':'fixture'}
            if self.lose_send_ack:
                self.lose_send_ack = False
                raise NetworkError('ack lost')
        if action == 'inquiry_sms_status':
            r['revision'] += 1; r['sequence'] += 1
            r['delivery']['state'] = 'success'; r['status'] = 'closed'
            r['answeredAt'] = datetime.now(timezone.utc).isoformat()
        return {'inquiry':copy.deepcopy(r)}

class InquiryFixtureTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.vault = Vault._create(Path(self.tmp.name)/'namufarm-vault.db', b'x'*32, {'version':b'2'}, {})
        self.api = FakeApi()
        self.manager = SimpleNamespace(vault=self.vault, api=self.api, credentials={'paired':True})
        self.service = InquiryService(self.manager)

class InquiryTests(InquiryFixtureTest):
    def test_import_commits_cursor_and_literal_excel(self):
        result = self.service.sync()
        self.assertEqual(self.service.store.cursor(), 1)
        with ZipFile(result['paths'][0]) as book:
            xml = book.read('xl/worksheets/sheet1.xml').decode()
        self.assertIn('=문의 원문', xml)
        self.assertIn('01000000000', xml)
        self.assertNotIn('<f>', xml)
        self.assertEqual(self.api.send_count, 0)

    def test_quote_snapshot_does_not_erase_inquiries_or_draft(self):
        self.service.sync(); self.service.remember(IDENTIFIER, '내 초안')
        self.vault.replace_snapshot([], [], {'requests':0,'sales':0,'deletedRequests':[]})
        self.assertEqual(self.service.row(IDENTIFIER)['inquiry']['message'], '=문의 원문')
        self.assertEqual(self.service.draft(self.service.row(IDENTIFIER))['text'], '내 초안')
        raw = self.vault.path.read_bytes()
        self.assertNotIn('내 초안'.encode(), raw)
        self.assertNotIn(b'01000000000', raw)

    def test_invalid_page_does_not_advance_cursor(self):
        self.api.bad_page = True
        with self.assertRaises(UserError): self.service.sync()
        self.assertEqual(self.service.store.cursor(), 0)
        self.assertEqual(self.service.store.rows(), [])

    def test_new_import_keeps_local_draft_and_detects_conflict(self):
        self.service.sync(); self.service.remember(IDENTIFIER, 'PC 답변')
        self.api.row['revision'] = 2; self.api.row['sequence'] = 2
        self.api.row['reply']['text'] = '다른 기기 답변'
        self.service.sync()
        with self.assertRaises(UserError): self.service.save_reply(IDENTIFIER)
        self.assertEqual(self.service.draft(self.service.row(IDENTIFIER))['text'], 'PC 답변')
        self.service.save_reply(IDENTIFIER, rebase=True)
        self.assertEqual(self.api.row['reply']['text'], 'PC 답변')

    def test_excel_lock_leaves_committed_db_and_only_export_retry(self):
        with patch('namufarm_desktop.inquiries._write_copy', side_effect=PermissionError('locked')):
            result = self.service.sync()
        self.assertTrue(result['copyError'])
        self.assertEqual(self.service.store.cursor(), 1)
        before = len(self.api.calls)
        self.service.export()
        self.assertEqual(len(self.api.calls), before)

    def test_lost_send_ack_reuses_delivery_id_after_restart(self):
        self.service.sync(); self.service.remember(IDENTIFIER, ' 답변입니다. ')
        self.api.lose_send_ack = True
        with self.assertRaises(NetworkError): self.service.send(IDENTIFIER, 'sms')
        operation = self.service.store.read('operations', IDENTIFIER)
        restarted = InquiryService(self.manager)
        self.assertEqual(restarted.store.read('operations', IDENTIFIER), operation)
        restarted.check(IDENTIFIER)
        self.assertEqual(self.api.send_count, 1)
        self.assertIsNone(restarted.store.read('operations', IDENTIFIER))
        row = restarted.row(IDENTIFIER)
        self.assertEqual(row['status'], 'closed')
        self.assertEqual(restarted.draft(row)['baseRevision'], row['revision'])
        sends = [p['deliveryId'] for a,p in self.api.calls if a=='send_inquiry_reply']
        self.assertEqual(sends[0], sends[1])

    def test_pending_sms_stays_open_until_final_success(self):
        self.service.sync(); self.service.remember(IDENTIFIER, '문자 답변')
        self.service.send(IDENTIFIER, 'sms')
        self.assertEqual(self.service.row(IDENTIFIER)['status'], 'open')
        self.service.check(IDENTIFIER)
        self.assertEqual(self.service.row(IDENTIFIER)['status'], 'closed')

    def test_expired_tombstone_removes_local_draft_and_excel_pii(self):
        self.service.sync(); self.service.remember(IDENTIFIER, '비공개 초안')
        self.api.row.update(expired=True, revision=2, sequence=2, status='closed')
        result = self.service.sync()
        self.assertIsNone(self.service.store.read('drafts', IDENTIFIER))
        self.assertNotIn('inquiry', self.service.store.read('records', IDENTIFIER))
        with ZipFile(result['paths'][0]) as book: xml=book.read('xl/worksheets/sheet1.xml').decode()
        self.assertNotIn('테스트고객', xml); self.assertNotIn('비공개 초안', xml)

    def test_offline_retention_removes_personal_data(self):
        row = record(); row.update(status='closed', answeredAt=(datetime.now(timezone.utc)-timedelta(days=31)).isoformat())
        self.service.store.merge([row])
        rows = self.service.store.rows()
        self.assertTrue(rows[0]['expired']); self.assertNotIn('inquiry', rows[0])
        self.service.store.merge([row])
        self.assertTrue(self.service.store.rows()[0]['expired'])
        row.update(status='open', answeredAt='', revision=2, sequence=2)
        self.service.store.merge([row])
        self.assertEqual(self.service.row(IDENTIFIER)['status'], 'open')

if __name__=='__main__': unittest.main()


# Enrollment errors must never invite deleting an app, vault, or customer records.
class EnrollmentNoticeTest(unittest.TestCase):
    def test_inquiry_enrollment_uses_owner_window_guidance(self):
        from unittest.mock import Mock
        from namufarm_desktop.errors import AuthenticationError
        from namufarm_desktop.inquiries import InquiryService
        manager = Mock()
        manager.server_info.return_value = {'customerInquiry': 1, 'customerInquiryReady': True,
                                            'customerInquiryWorkflow': 2, 'connectionAuth': 'auto-v1'}
        manager.credentials = {'paired': False}
        manager.api.call.side_effect = AuthenticationError('denied', code='UNAUTHORIZED')
        service = InquiryService.__new__(InquiryService)
        service.manager = manager
        with self.assertRaisesRegex(AuthenticationError, 'openAppEnrollment'):
            service.ensure_connection()
        manager.vault.save_credentials.assert_not_called()
