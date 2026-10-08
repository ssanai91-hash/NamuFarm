import copy
import csv
import io
import os
os.environ.setdefault('QT_QPA_PLATFORM', 'offscreen')
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from PySide6.QtWidgets import QApplication, QTableWidgetItem
from PySide6.QtGui import QTextDocument
from PySide6.QtPrintSupport import QPrinter
from namufarm_desktop.commerce import (new_draft, draft_amounts, order_from_draft, quote_document,
    quote_html, shipping_html, shipping_csv, parse_tracking_csv, tracking_fields, tracking_number, TRACKING_PREFIX)
from namufarm_desktop.domain import metadata, now_ms
from namufarm_desktop.errors import UserError, NetworkError
from namufarm_desktop.workspace import SellerWindow, QuoteDialog
from namufarm_desktop.demo import show_demo
from support import Bridge, manager_at


def draft_for_test():
    draft = new_draft()
    draft.update(customerName='테스트 고객', customerPhone='01012345678', receiverName='수령인', zipNo='01234',
                 roadAddress='서울 테스트로 1', detailAddress='100호', note='문 앞에 놓아주세요.',
                 shippingFee=3500, isPrepaid=True, taxAmount=2350,
                 items=[dict(species='소나무', specification='H1.0m', quantity=2, unitPrice=10000, unitCost=5000)])
    return draft


def row_for_test():
    order = order_from_draft(draft_for_test())
    order.update(status='입금확인', paidAt=order['purchasedAt'])
    return {'metadata': metadata(order), 'private': {'order': order}}


class CommerceTest(unittest.TestCase):
    def test_totals_and_android_manual_item_wire_compatibility(self):
        draft = draft_for_test()
        self.assertEqual(dict(quantity=2, supply=23500, tax=2350, total=25850, cost=10000), draft_amounts(draft))
        order, doc = order_from_draft(draft), quote_document(draft)
        self.assertIn('|수기견적|5000', order['itemSummary'])
        self.assertEqual(order['totalPrice'], doc['supplyAmount'])
        self.assertEqual(sum(x['quantity']*x['unitPrice'] for x in doc['lines']), doc['supplyAmount'])
        draft['isPrepaid'] = False
        self.assertEqual(20000, draft_amounts(draft)['supply'])

    def test_invalid_amount_overflow_and_pipe_are_rejected(self):
        for key, value in [('quantity', 0), ('quantity', True), ('unitPrice', -1), ('unitPrice', 2147483647), ('species', 'a|b')]:
            draft = draft_for_test()
            draft['items'][0][key] = value
            with self.assertRaises(UserError):
                order_from_draft(draft)

    def test_tracking_preserves_human_note_and_is_not_a_delivery_confirmation(self):
        row = row_for_test()
        order = row['private']['order']
        fields = tracking_fields(order, '012-3456-7890')
        self.assertEqual('문 앞에 놓아주세요.\n' + TRACKING_PREFIX + '01234567890', fields['note'])
        order.update(fields)
        self.assertEqual('01234567890', tracking_number(order))
        order.update(tracking_fields(order, '12345678901'))
        self.assertEqual(1, order['note'].count(TRACKING_PREFIX))
        self.assertEqual('입금확인', order['status'])
        order['status'] = '판매완료'
        with self.assertRaises(UserError):
            tracking_fields(order, '12345678901')

    def test_customer_note_cannot_crash_tracking_view_or_inject_print_html(self):
        row = row_for_test()
        order = row['private']['order']
        order['note'] = TRACKING_PREFIX + '<bad>'
        self.assertEqual('', tracking_number(order))
        draft = draft_for_test()
        draft['customerName'] = '<script>alert(1)</script>'
        self.assertNotIn('<script>', quote_html(draft))
        self.assertIn('&lt;script&gt;', quote_html(draft))

    def test_export_and_import_keep_leading_zero_and_block_formulas(self):
        row = row_for_test()
        order = row['private']['order']
        order['receiverName'] = '=HYPERLINK("evil")'
        exported = list(csv.DictReader(io.StringIO(shipping_csv([row]))))[0]
        self.assertEqual('01234', exported['우편번호'])
        self.assertEqual('01012345678', exported['수하인전화'])
        self.assertTrue(exported['수하인명'].startswith("'="))
        raw = f"주문번호,송장번호\r\n{order['orderNumber']},01234567890\r\n".encode('utf-8-sig')
        self.assertEqual({order['orderNumber']: '01234567890'}, parse_tracking_csv(raw, [row]))
        order['zipNo'] = ''
        with self.assertRaises(UserError):
            shipping_csv([row])

    def test_csv_duplicate_unknown_changed_and_malformed_tracking_rejected(self):
        row = row_for_test()
        number = row['metadata']['orderNumber']
        for text in [f'주문번호,송장번호\n{number},123\n', f'주문번호,송장번호\n{number},12345678901\n{number},12345678901\n', '주문번호,송장번호\nunknown,12345678901\n', '주문번호,주문번호,송장번호\nx,x,12345678901\n']:
            with self.assertRaises(UserError):
                parse_tracking_csv(text.encode(), [row])
        row['private']['order'].update(tracking_fields(row['private']['order'], '12345678901'))
        with self.assertRaises(UserError):
            parse_tracking_csv(f'주문번호,송장번호\n{number},01234567890\n'.encode(), [row])

    def test_print_requires_registered_number_and_identifies_output(self):
        row = row_for_test()
        with self.assertRaises(UserError):
            shipping_html([row])
        row['private']['order'].update(tracking_fields(row['private']['order'], '01234567890'))
        html = shipping_html([row])
        self.assertIn('01234567890', html)
        self.assertIn('출고 확인용', html)
        self.assertIn('공식 운송장', html)


class WorkspaceServiceTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.bridge = Bridge()
        self.manager = manager_at(Path(self.tmp.name)/'pc.db', self.bridge)

    def tearDown(self):
        self.bridge.close()
        self.tmp.cleanup()

    def test_draft_encrypted_survives_sync_and_expires(self):
        draft = draft_for_test()
        self.manager.save_draft(draft)
        self.assertEqual(1, len(self.manager.sync()['drafts']))
        self.assertNotIn('테스트 고객'.encode(), self.manager.vault.path.read_bytes())
        expired = copy.deepcopy(draft)
        expired['createdAt'] = now_ms() - 31 * 86400000
        self.manager.vault.save_preference('drafts', [expired])
        self.assertEqual([], self.manager.view()['drafts'])
        self.assertEqual([], self.manager.vault.preference('drafts'))

    def test_shipping_export_preferences_are_encrypted_and_do_not_advance_sale(self):
        row = self.manager.confirm_draft(draft_for_test())['sales'][0]
        before = self.manager.view()['sales']
        sender = {'name': '출고 설정 테스트', 'address': '테스트 출고지', 'phone': '01012345678', 'mobile': ''}
        number = row['metadata']['orderNumber']
        self.manager.remember_shipping_export(sender, {number: {}})
        settings = self.manager.shipping_export_preferences()
        self.assertEqual(sender, settings['sender'])
        self.assertIn(number, settings['history'])
        self.assertEqual(before, self.manager.view()['sales'])
        self.assertNotIn(sender['name'].encode(), self.manager.vault.path.read_bytes())

    def test_request_to_sale_closes_request_and_preserves_document(self):
        self.bridge.send(control='submit')
        view = self.manager.sync()
        draft = draft_for_test()
        draft['sourceRequestId'] = view['requests'][0]['requestId']
        self.manager.save_draft(draft)
        view = self.manager.confirm_draft(draft)
        self.assertEqual([], view['requests'])
        self.assertEqual([], view['drafts'])
        self.assertEqual(1, len(view['sales']))
        self.assertIn('quoteDocumentJson', view['sales'][0]['private'])
        with self.assertRaises(UserError):
            self.manager.confirm_draft(draft)
        self.assertEqual(1, len(self.manager.view()['sales']))

    def test_lost_new_order_ack_reuses_event_and_never_duplicates(self):
        draft = draft_for_test()
        self.manager.api.lose_ack = True
        with self.assertRaises(NetworkError):
            self.manager.confirm_draft(draft)
        operation = self.manager.vault.pending()[0]
        self.assertEqual('save_sale', operation['action'])
        view = self.manager.sync()
        self.assertEqual(1, len(view['sales']))
        self.assertEqual(1, view['sales'][0]['metadata']['revision'])
        self.assertEqual([], view['pending'])

    def test_deleted_source_draft_is_purged(self):
        self.bridge.send(control='submit')
        request = self.manager.sync()['requests'][0]
        draft = draft_for_test()
        draft['sourceRequestId'] = request['requestId']
        self.manager.save_draft(draft)
        self.assertEqual([], self.manager.close_request(request['requestId'])['drafts'])
        with self.assertRaises(UserError):
            self.manager.confirm_draft(draft)

    def test_tracking_sync_roundtrip_retry_and_duplicate_guard(self):
        draft = draft_for_test()
        row = self.manager.confirm_draft(draft)['sales'][0]
        number = row['metadata']['orderNumber']
        view = self.manager.save_sale(number, 1, {}, '입금확인')
        self.manager.api.lose_ack = True
        with self.assertRaises(NetworkError):
            self.manager.register_tracking(number, 2, '01234567890')
        row = self.manager.sync()['sales'][0]
        self.assertEqual('01234567890', tracking_number(row['private']['order']))
        self.assertEqual('입금확인', row['metadata']['status'])
        self.assertEqual(3, row['metadata']['revision'])
        second = self.manager.confirm_draft(draft_for_test())['sales'][0]
        other = second['metadata']['orderNumber']
        self.manager.save_sale(other, 1, {}, '입금확인')
        with self.assertRaises(UserError):
            self.manager.register_tracking(other, 2, '01234567890')
        self.manager.import_tracking({number: '01234567890'})
        row = next(r for r in self.manager.view()['sales'] if r['metadata']['orderNumber'] == number)
        self.assertEqual(3, row['metadata']['revision'])


class SellerUiTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])

    def test_fhd_pages_readonly_demo_and_clear_on_lock(self):
        window = SellerWindow()
        show_demo(window)
        window.resize(1920, 1080)
        window.show()
        self.app.processEvents()
        self.assertEqual(7, window.stack.count())
        self.assertFalse(window.sync_button.isEnabled())
        self.assertEqual(3, len(window.shipping_rows))
        for index in range(7):
            window.navigate(index)
            self.app.processEvents()
            self.assertEqual(index, window.stack.currentIndex())
            self.assertLessEqual(window.stack.geometry().right(), window.width())
        window.demo_mode = False
        window.lock()
        self.assertEqual(0, window.shipping_table.rowCount())
        self.assertEqual(0, window.work_table.rowCount())
        self.assertEqual(0, window.draft_table.rowCount())
        window.close()

    def test_quote_editor_calculates_and_prints_pdf(self):
        draft = draft_for_test()
        dialog = QuoteDialog(draft)
        dialog.show()
        self.app.processEvents()
        self.assertIn('25,850', dialog.total.text())
        dialog.items.item(0, 2).setText('3')
        self.assertEqual(3, dialog.collect()['items'][0]['quantity'])
        self.assertIn('35,850', dialog.total.text())
        self.assertLessEqual(dialog.items.geometry().right(), dialog.width())
        with tempfile.TemporaryDirectory() as directory:
            printer = QPrinter()
            printer.setOutputFormat(QPrinter.OutputFormat.PdfFormat)
            path = Path(directory)/'quote.pdf'
            printer.setOutputFileName(str(path))
            doc = QTextDocument()
            doc.setHtml(quote_html(dialog.collect()))
            doc.print_(printer)
            self.assertGreater(path.stat().st_size, 1000)
            self.assertTrue(path.read_bytes().startswith(b'%PDF'))
        dialog.initial = dialog.raw_state()
        dialog.close()


if __name__ == '__main__':
    unittest.main()
