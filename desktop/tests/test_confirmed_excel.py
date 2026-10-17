"""Synthetic server fixture + temporary vault/workbooks; no production connection."""
import copy
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from xml.etree import ElementTree as ET
from zipfile import ZipFile, ZIP_DEFLATED

from namufarm_desktop.commerce import new_draft
from namufarm_desktop.confirmed_excel import NS, read_confirmed_quotes, write_confirmed_quotes
from namufarm_desktop.errors import ConflictError, NetworkError, UserError
from namufarm_desktop.service import Manager
from namufarm_desktop.store import Vault
from support import Bridge, manager_at, PASSWORD


class ConfirmedExcelTest(unittest.TestCase):
    def setUp(self):
        self.bridge = Bridge()
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / 'vault.db'
        self.manager = manager_at(self.path, self.bridge)

    def tearDown(self):
        self.bridge.close()
        self.tmp.cleanup()

    def request_draft(self):
        self.bridge.send(control='submit_plain')
        request = self.manager.sync_requests()['requests'][0]
        draft = new_draft(request)
        draft['items'][0].update(unitPrice=13000, unitCost=5000)
        draft.update(zipNo='01234', roadAddress='테스트 주소', detailAddress='테스트 상세',
                     shippingFee=6000, isPrepaid=True, taxAmount=1300, paymentAccount='테스트 계좌')
        return draft

    def restart(self):
        self.manager = Manager(Vault.unlock(self.path, PASSWORD), self.manager.api)
        return self.manager

    def test_inbox_replaces_old_counter_and_deleted_marker_without_reading_sales(self):
        draft = self.request_draft()
        row = self.manager.vault.rows('requests')[0]
        self.manager.vault.replace_snapshot([row], [], {'requests': 9999999, 'sales': 0,
                                           'deletedRequests': [draft['sourceRequestId']], 'quoteReadMode': 'annual-two-tabs'})
        self.manager.api.calls.clear()
        view = self.manager.connect()
        self.assertEqual([draft['sourceRequestId']], [r['requestId'] for r in view['requests']])
        self.assertEqual(['list_requests'], self.manager.api.calls)
        self.assertEqual([], self.manager.vault.snapshot_marker()['deletedRequests'])

    def test_server_ack_precedes_excel_and_confirmation_does_not_fetch_again(self):
        draft = self.request_draft()
        self.manager.api.calls.clear()
        original = write_confirmed_quotes
        def observe(path, quotes):
            self.assertIn('save_quote_pricing', self.manager.api.calls)
            original(path, quotes)
        with patch('namufarm_desktop.confirmed_excel.write_confirmed_quotes', side_effect=observe):
            result = self.manager.confirm_quote(draft)
        self.assertEqual(['save_quote_pricing'], self.manager.api.calls)
        self.assertEqual([], result['requests'])
        self.assertEqual('견적확정', result['confirmedQuotes'][0]['quoteStatus'])
        self.assertEqual({**draft, 'quoteStatus': '견적확정'}, read_confirmed_quotes(self.manager.confirmed_quotes_path)[0])

    def test_restart_reads_confirmed_excel_without_network_or_cached_inbox(self):
        draft = self.request_draft()
        self.manager.confirm_quote(draft)
        manager = self.restart()
        manager.api.offline = True
        manager.api.calls.clear()
        view = manager.view()
        self.assertEqual([], manager.api.calls)
        self.assertEqual([], view['requests'])
        self.assertEqual(draft['id'], view['confirmedQuotes'][0]['id'])

    def test_server_only_confirmation_is_not_imported(self):
        draft = self.request_draft()
        self.manager.api.call('save_quote_pricing', requestId=draft['sourceRequestId'], quotePricing={
            'items': [{k: i[k] for k in ('species', 'specification', 'quantity', 'unitPrice')} for i in draft['items']],
            'isPrepaid': draft['isPrepaid'], 'shippingFee': draft['shippingFee'], 'taxAmount': draft['taxAmount']})
        self.assertEqual([], self.manager.sync_requests()['requests'])
        self.assertEqual([], self.manager.view()['confirmedQuotes'])
        self.assertEqual([], self.restart().view()['confirmedQuotes'])

    def test_current_two_tab_server_moves_request_and_keeps_local_quote(self):
        self.bridge.send(control='current_storage')
        draft = self.request_draft()
        result = self.manager.confirm_quote(draft)
        self.assertEqual([], result['requests'])
        self.assertEqual('견적확정', result['confirmedQuotes'][0]['quoteStatus'])
        self.assertEqual([], self.manager.sync_requests()['requests'])
        self.assertEqual(draft['id'], self.restart().view()['confirmedQuotes'][0]['id'])

    def test_unreadable_legacy_sale_does_not_block_new_requests(self):
        self.bridge.send(control='seed')
        self.manager.sync()
        self.manager.seller = None
        self.bridge.send(control='submit_plain')
        result = self.manager.sync_requests()
        self.assertEqual(1, len(result['requests']))
        self.assertEqual([], result['sales'])
        self.assertIn('판매자료', result['copyError'])

    def test_quote_conflict_clears_retry_without_blocking_the_inbox(self):
        draft = self.request_draft()
        original = self.manager.api.call
        def conflict(action, **fields):
            if action == 'save_quote_pricing':
                raise ConflictError('synthetic changed on Android')
            return original(action, **fields)
        with patch.object(self.manager.api, 'call', side_effect=conflict):
            with self.assertRaises(UserError):
                self.manager.confirm_quote(draft)
        self.assertIsNone(self.manager.vault.preference('quote_write_pending'))
        self.assertEqual(1, len(self.manager.sync_requests()['requests']))

    def test_failed_server_write_does_not_confirm_locally(self):
        draft = self.request_draft()
        before = self.manager.confirmed_quotes_path.read_bytes()
        original = self.manager.api.call
        def offline(action, **fields):
            if action == 'save_quote_pricing':
                raise NetworkError('synthetic offline')
            return original(action, **fields)
        with patch.object(self.manager.api, 'call', side_effect=offline):
            with self.assertRaises(NetworkError):
                self.manager.confirm_quote(draft)
        self.assertEqual([], self.manager.view()['confirmedQuotes'])
        self.assertEqual(before, self.manager.confirmed_quotes_path.read_bytes())

    def test_lost_ack_retries_exact_quote_and_keeps_one_row_after_restart(self):
        draft = self.request_draft()
        original = self.manager.api.call
        def lose_ack(action, **fields):
            result = original(action, **fields)
            if action == 'save_quote_pricing':
                raise NetworkError('synthetic lost response')
            return result
        with patch.object(self.manager.api, 'call', side_effect=lose_ack):
            with self.assertRaises(NetworkError):
                self.manager.confirm_quote(draft)
        self.assertEqual([], self.restart().view()['confirmedQuotes'])
        result = self.manager.sync_requests()
        self.assertEqual([draft['id']], [d['id'] for d in result['confirmedQuotes']])
        self.manager.sync_requests()
        self.assertEqual(1, len(read_confirmed_quotes(self.manager.confirmed_quotes_path)))
        self.assertIsNone(self.manager.vault.preference('quote_write_pending'))

    def test_bad_ack_is_not_treated_as_local_confirmation(self):
        draft = self.request_draft()
        original = self.manager.api.call
        def bad_ack(action, **fields):
            result = original(action, **fields)
            return {**result, 'requestId': 'wrong'} if action == 'save_quote_pricing' else result
        with patch.object(self.manager.api, 'call', side_effect=bad_ack):
            with self.assertRaises(UserError):
                self.manager.confirm_quote(draft)
        self.assertEqual([], read_confirmed_quotes(self.manager.confirmed_quotes_path))
        self.assertEqual(draft['id'], self.manager.sync_requests()['confirmedQuotes'][0]['id'])

    def test_locked_excel_recovers_acknowledged_quote_offline_on_restart(self):
        draft = self.request_draft()
        before = self.manager.confirmed_quotes_path.read_bytes()
        with patch('namufarm_desktop.confirmed_excel.write_confirmed_quotes', side_effect=PermissionError()):
            result = self.manager.confirm_quote(draft)
        self.assertIn('엑셀', result['copyError'])
        self.assertTrue(self.manager.vault.preference('quote_write_pending')['acknowledged'])
        self.assertEqual(before, self.manager.confirmed_quotes_path.read_bytes())
        manager = self.restart()
        manager.api.offline = True
        manager.api.calls.clear()
        result = manager.view()
        self.assertEqual(draft['id'], result['confirmedQuotes'][0]['id'])
        self.assertEqual([], manager.api.calls)
        self.assertEqual([], result['pending'])

    def test_corrupt_or_missing_excel_is_preserved_and_blocks_new_confirmation(self):
        draft = self.request_draft()
        self.manager.confirmed_quotes_path.write_bytes(b'broken workbook')
        self.manager.api.calls.clear()
        result = self.manager.view()
        self.assertIn('엑셀', result['copyError'])
        with self.assertRaises(UserError):
            self.manager.confirm_quote(draft)
        self.assertNotIn('save_quote_pricing', self.manager.api.calls)
        self.assertEqual(b'broken workbook', self.manager.confirmed_quotes_path.read_bytes())
        self.manager.confirmed_quotes_path.unlink()
        self.assertIn('복원', self.manager.view()['copyError'])
        self.assertFalse(self.manager.confirmed_quotes_path.exists())

    def test_shipping_edit_sent_status_and_purchase_work_after_restart(self):
        draft = self.request_draft()
        self.manager.confirm_quote(draft)
        self.restart()
        draft['roadAddress'] = '변경된 배송 주소'
        self.manager.save_draft(draft)
        self.assertEqual('변경된 배송 주소', self.manager.view()['confirmedQuotes'][0]['roadAddress'])
        result = self.manager.mark_quote_sent(draft)
        self.assertEqual('견적발송완료', result['confirmedQuotes'][0]['quoteStatus'])
        self.restart()
        self.assertEqual('견적발송완료', self.manager.view()['confirmedQuotes'][0]['quoteStatus'])
        result = self.manager.confirm_draft(draft)
        self.assertEqual([], result['confirmedQuotes'])
        self.assertEqual('PC_' + draft['id'], result['sales'][0]['metadata']['orderNumber'])
        self.assertEqual('입금확인', read_confirmed_quotes(self.manager.confirmed_quotes_path)[0]['quoteStatus'])

    def test_local_delete_does_not_delete_server_quote(self):
        draft = self.request_draft()
        self.manager.confirm_quote(draft)
        self.manager.api.calls.clear()
        self.assertEqual([], self.manager.delete_draft(draft['id'])['confirmedQuotes'])
        self.assertEqual([], self.manager.api.calls)
        self.assertEqual([], self.restart().view()['confirmedQuotes'])

    def test_pending_sale_does_not_block_request_view_or_confirmation(self):
        self.bridge.send(control='seed')
        self.manager.sync()
        self.manager.api.offline = True
        with self.assertRaises(NetworkError):
            self.manager.save_sale('ORDER_TEST', 1, {'receiverName': '대기중'})
        pending = self.manager.vault.pending()
        self.manager.api.offline = False
        draft = self.request_draft()
        self.manager.confirm_quote(draft)
        self.assertEqual(pending, self.manager.vault.pending())
        self.assertEqual(draft['id'], self.manager.view()['confirmedQuotes'][0]['id'])

    def test_migration_preserves_only_pc_drafts_with_confirmed_status(self):
        draft = self.request_draft()
        unconfirmed = copy.deepcopy(draft)
        unconfirmed['id'] = '00000000-0000-4000-8000-000000000001'
        unconfirmed['sourceRequestId'] = '00000000-0000-4000-8000-000000000002'
        rows = self.manager.vault.rows('requests')
        rows[0]['status'] = '견적확정'
        self.manager.vault.replace_requests(rows, 1, set(), authoritative=True)
        self.manager.vault.save_preference('drafts', [draft, unconfirmed])
        self.manager.confirmed_quotes_path.unlink()
        self.manager.vault.save_preference('confirmed_excel_initialized', False)
        self.assertEqual([draft['id']], [d['id'] for d in self.restart().view()['confirmedQuotes']])

    def test_literal_text_large_payload_and_atomic_replace(self):
        draft = self.request_draft()
        draft['customerName'] = '=1+1'
        draft['items'] = [{**draft['items'][0], 'species': '나무' * 80, 'quantity': i + 1} for i in range(50)]
        quote = {**draft, 'quoteStatus': '견적확정'}
        path = self.manager.confirmed_quotes_path
        write_confirmed_quotes(path, [quote])
        self.assertEqual([quote], read_confirmed_quotes(path))
        with ZipFile(path) as book:
            visible = ET.fromstring(book.read('xl/worksheets/sheet1.xml'))
            hidden = ET.fromstring(book.read('xl/worksheets/sheet2.xml'))
            self.assertFalse(visible.findall(f'.//{{{NS}}}f'))
            self.assertGreater(len(hidden.findall(f'{{{NS}}}sheetData/{{{NS}}}row')), 2)
            self.assertEqual('=1+1', visible.find(f'.//{{{NS}}}c[@r="D2"]/{{{NS}}}is/{{{NS}}}t').text)
            self.assertEqual(draft['customerPhone'], visible.find(f'.//{{{NS}}}c[@r="E2"]/{{{NS}}}is/{{{NS}}}t').text)
        before = path.read_bytes()
        with patch('namufarm_desktop.confirmed_excel.os.replace', side_effect=PermissionError()):
            with self.assertRaises(PermissionError):
                write_confirmed_quotes(path, [])
        self.assertEqual(before, path.read_bytes())
        self.assertEqual([], list(path.parent.glob('.confirmed-quotes-*')))

    def test_excel_shared_string_resave_is_readable(self):
        draft = {**self.request_draft(), 'quoteStatus': '견적확정'}
        path = self.manager.confirmed_quotes_path
        write_confirmed_quotes(path, [draft])
        with ZipFile(path) as book:
            files = {name: book.read(name) for name in book.namelist()}
        root = ET.fromstring(files['xl/worksheets/sheet2.xml'])
        strings = ET.Element(f'{{{NS}}}sst')
        for index, cell in enumerate(root.findall(f'.//{{{NS}}}c')):
            text = ''.join(cell.find(f'{{{NS}}}is').itertext())
            ET.SubElement(ET.SubElement(strings, f'{{{NS}}}si'), f'{{{NS}}}t').text = text
            cell.remove(cell.find(f'{{{NS}}}is'))
            cell.set('t', 's')
            ET.SubElement(cell, f'{{{NS}}}v').text = str(index)
        files['xl/worksheets/sheet2.xml'] = ET.tostring(root)
        files['xl/sharedStrings.xml'] = ET.tostring(strings)
        with ZipFile(path, 'w', ZIP_DEFLATED) as book:
            for name, data in files.items():
                book.writestr(name, data)
        self.assertEqual([draft], read_confirmed_quotes(path))


if __name__ == '__main__':
    unittest.main()
